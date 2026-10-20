package com.example.inventory.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ProductProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductProjectionConsumer.class);

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final Counter indexed;
    private final Counter deleted;
    private final Counter tombstones;
    private final Timer cdcLagTimer;

    public ProductProjectionConsumer(ElasticsearchClient client, ElasticsearchProperties properties,
                                     ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.indexed = meterRegistry.counter("products.cdc.indexed");
        this.deleted = meterRegistry.counter("products.cdc.deleted");
        this.tombstones = meterRegistry.counter("products.cdc.tombstones");
        this.cdcLagTimer = Timer.builder("products.cdc.lag")
                .description("Replication lag from Postgres commit (source.ts_ms) to Elasticsearch indexing completion")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public void consume(ConsumerRecord<String, String> record) throws IOException {
        if (record != null) {
            consume(List.of(record));
        }
    }

    @KafkaListener(topics = "${app.kafka.product-topic}", batch = "true")
    public void consume(List<ConsumerRecord<String, String>> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return;
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        List<Long> commitTimestamps = new ArrayList<>();
        int operationCount = 0;

        for (ConsumerRecord<String, String> record : records) {
            if (record.value() == null) {
                tombstones.increment();
                log.debug("Ignoring delete tombstone at {}-{}@{}", record.topic(), record.partition(), record.offset());
                continue;
            }

            JsonNode envelope = objectMapper.readTree(record.value());
            long commitTs = extractCommitTimestamp(envelope);
            String operation = requiredText(envelope, "op");

            switch (operation) {
                case "c", "u", "r" -> {
                    ProductSearchDocument document = parseProductSearchDocument(requiredNode(envelope, "after"));
                    long esVersion = document.version() + 1L;
                    bulkBuilder.operations(op -> op.index(idx -> idx
                            .index(properties.aliasName())
                            .id(document.id())
                            .document(document)
                            .versionType(VersionType.ExternalGte)
                            .version(esVersion)
                    ));
                    commitTimestamps.add(commitTs);
                    operationCount++;
                }
                case "d" -> {
                    DeleteTarget target = extractDeleteTarget(envelope, record.key());
                    bulkBuilder.operations(op -> op.delete(del -> {
                        del.index(properties.aliasName()).id(target.id());
                        if (target.version() != null) {
                            del.versionType(VersionType.ExternalGte)
                                    .version(target.version() + 1L);
                        }
                        return del;
                    }));
                    commitTimestamps.add(commitTs);
                    operationCount++;
                }
                default -> throw new IllegalArgumentException("Unsupported Debezium operation: " + operation);
            }
        }

        if (operationCount > 0) {
            BulkResponse response = client.bulk(bulkBuilder.build());

            long now = System.currentTimeMillis();
            for (Long commitTs : commitTimestamps) {
                if (commitTs != null && commitTs > 0L) {
                    long lag = Math.max(0L, now - commitTs);
                    cdcLagTimer.record(lag, TimeUnit.MILLISECONDS);
                }
            }

            List<String> criticalErrors = new ArrayList<>();
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    int status = item.status();
                    if (status == 409) {
                        log.info("Ignoring stale version conflict for product ID {} during bulk operation: {}",
                                item.id(), item.error().reason());
                    } else if (status == 404 && item.operationType() == OperationType.Delete) {
                        log.debug("Product document {} already deleted or not found in Elasticsearch", item.id());
                    } else {
                        log.error("Failed to execute bulk operation for product ID {} (status {}): {}",
                                item.id(), status, item.error().reason());
                        criticalErrors.add("Item " + item.id() + ": " + item.error().reason());
                    }
                } else {
                    if (item.operationType() == OperationType.Delete) {
                        deleted.increment();
                    } else {
                        indexed.increment();
                    }
                }
            }

            if (!criticalErrors.isEmpty()) {
                throw new IllegalStateException("Bulk indexing completed with errors: " + criticalErrors);
            }
        }
    }

    private ProductSearchDocument parseProductSearchDocument(JsonNode row) {
        return new ProductSearchDocument(
                requiredText(row, "id"),
                requiredText(row, "sku"),
                requiredText(row, "title"),
                nullableText(row, "description"),
                requiredText(row, "category"),
                new BigDecimal(requiredText(row, "price")),
                requiredInt(row, "stock_quantity"),
                requiredBoolean(row, "active"),
                requiredText(row, "created_at"),
                requiredText(row, "updated_at"),
                requiredLong(row, "version"));
    }

    private long extractCommitTimestamp(JsonNode envelope) {
        JsonNode source = envelope.path("source");
        if (source.hasNonNull("ts_ms")) {
            return source.get("ts_ms").asLong();
        }
        if (envelope.hasNonNull("ts_ms")) {
            return envelope.get("ts_ms").asLong();
        }
        return 0L;
    }

    private record DeleteTarget(String id, Long version) {}

    private DeleteTarget extractDeleteTarget(JsonNode envelope, String rawKey) throws IOException {
        JsonNode before = envelope.path("before");
        String id = null;
        Long version = null;
        if (!before.isMissingNode() && !before.isNull()) {
            if (before.hasNonNull("id")) {
                id = before.get("id").asText();
            }
            if (before.hasNonNull("version")) {
                version = before.get("version").asLong();
            }
        }
        if (id == null && rawKey != null) {
            JsonNode key = objectMapper.readTree(rawKey);
            if (key.hasNonNull("id")) {
                id = key.get("id").asText();
            }
        }
        if (id == null) {
            throw new IllegalArgumentException("Delete event contains no product id");
        }
        return new DeleteTarget(id, version);
    }

    private static JsonNode requiredNode(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing required Debezium field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value.asText();
    }

    private static String nullableText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Missing or invalid integer field: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Missing or invalid long field: " + field);
        }
        return value.longValue();
    }

    private static boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("Missing or invalid boolean field: " + field);
        }
        return value.booleanValue();
    }
}
