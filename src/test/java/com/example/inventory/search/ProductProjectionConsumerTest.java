package com.example.inventory.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductProjectionConsumerTest {

    @Mock
    private ElasticsearchClient client;

    private SimpleMeterRegistry meterRegistry;
    private ProductProjectionConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new ProductProjectionConsumer(
                client,
                new ElasticsearchProperties("http://localhost:9200", "products-v1", "products", false),
                new ObjectMapper(),
                meterRegistry);
    }

    @Test
    void indexesCreateEventWithExternalVersion() throws IOException {
        String payload = """
                {
                  "before": null,
                  "after": {
                    "id": "3e71ea24-4bf8-4ad8-9188-c1b03293a9ce",
                    "sku": "PHONE-15",
                    "title": "iPhone 15",
                    "description": "Phone",
                    "category": "Electronics",
                    "price": "799.00",
                    "stock_quantity": 12,
                    "active": true,
                    "created_at": "2026-09-27T12:00:00Z",
                    "updated_at": "2026-09-27T12:00:00Z",
                    "version": 0
                  },
                  "op": "c"
                }
                """;

        BulkResponseItem item = BulkResponseItem.of(b -> b
                .operationType(OperationType.Index)
                .id("3e71ea24-4bf8-4ad8-9188-c1b03293a9ce")
                .index("products")
                .status(200));
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b -> b.took(2L).errors(false).items(List.of(item))));

        consumer.consume(new ConsumerRecord<>("inventory.public.products", 0, 1L, "{}", payload));

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        BulkRequest request = captor.getValue();

        assertThat(request.operations()).hasSize(1);
        assertThat(request.operations().get(0).index().id()).isEqualTo("3e71ea24-4bf8-4ad8-9188-c1b03293a9ce");
        assertThat(request.operations().get(0).index().version()).isEqualTo(1L);
        assertThat(request.operations().get(0).index().versionType()).isEqualTo(VersionType.ExternalGte);
        assertThat(meterRegistry.get("products.cdc.indexed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void ignoresStaleVersionConflictOnIndex() throws IOException {
        String payload = """
                {
                  "before": null,
                  "after": {
                    "id": "3e71ea24-4bf8-4ad8-9188-c1b03293a9ce",
                    "sku": "PHONE-15",
                    "title": "iPhone 15",
                    "description": "Phone",
                    "category": "Electronics",
                    "price": "799.00",
                    "stock_quantity": 12,
                    "active": true,
                    "created_at": "2026-09-27T12:00:00Z",
                    "updated_at": "2026-09-27T12:00:00Z",
                    "version": 0
                  },
                  "op": "u"
                }
                """;

        BulkResponseItem conflictItem = BulkResponseItem.of(b -> b
                .operationType(OperationType.Index)
                .id("3e71ea24-4bf8-4ad8-9188-c1b03293a9ce")
                .index("products")
                .status(409)
                .error(e -> e.reason("version conflict").type("version_conflict_engine_exception")));
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b -> b.took(2L).errors(true).items(List.of(conflictItem))));

        assertThatNoException().isThrownBy(() -> consumer.consume(
                new ConsumerRecord<>("inventory.public.products", 0, 1L, "{}", payload)));

        assertThat(meterRegistry.get("products.cdc.indexed").counter().count()).isEqualTo(0.0);
    }

    @Test
    void deletesFromBeforeImage() throws IOException {
        String payload = """
                {"before":{"id":"3e71ea24-4bf8-4ad8-9188-c1b03293a9ce","version":2},"after":null,"op":"d"}
                """;

        BulkResponseItem item = BulkResponseItem.of(b -> b
                .operationType(OperationType.Delete)
                .id("3e71ea24-4bf8-4ad8-9188-c1b03293a9ce")
                .index("products")
                .status(200));
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b -> b.took(2L).errors(false).items(List.of(item))));

        consumer.consume(new ConsumerRecord<>("inventory.public.products", 0, 2L, "{}", payload));

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        BulkRequest request = captor.getValue();

        assertThat(request.operations()).hasSize(1);
        assertThat(request.operations().get(0).delete().id()).isEqualTo("3e71ea24-4bf8-4ad8-9188-c1b03293a9ce");
        assertThat(request.operations().get(0).delete().version()).isEqualTo(3L);
        assertThat(request.operations().get(0).delete().versionType()).isEqualTo(VersionType.ExternalGte);
        assertThat(meterRegistry.get("products.cdc.deleted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void ignoresNotFoundOnDelete() throws IOException {
        String payload = """
                {"before":{"id":"3e71ea24-4bf8-4ad8-9188-c1b03293a9ce"},"after":null,"op":"d"}
                """;

        BulkResponseItem notFoundItem = BulkResponseItem.of(b -> b
                .operationType(OperationType.Delete)
                .id("3e71ea24-4bf8-4ad8-9188-c1b03293a9ce")
                .index("products")
                .status(404)
                .error(e -> e.reason("not found").type("index_not_found_exception")));
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b -> b.took(2L).errors(true).items(List.of(notFoundItem))));

        assertThatNoException().isThrownBy(() -> consumer.consume(
                new ConsumerRecord<>("inventory.public.products", 0, 2L, "{}", payload)));

        assertThat(meterRegistry.get("products.cdc.deleted").counter().count()).isEqualTo(0.0);
    }

    @Test
    void ignoresStaleVersionConflictOnDelete() throws IOException {
        String payload = """
                {"before":{"id":"3e71ea24-4bf8-4ad8-9188-c1b03293a9ce","version":1},"after":null,"op":"d"}
                """;

        BulkResponseItem conflictItem = BulkResponseItem.of(b -> b
                .operationType(OperationType.Delete)
                .id("3e71ea24-4bf8-4ad8-9188-c1b03293a9ce")
                .index("products")
                .status(409)
                .error(e -> e.reason("conflict").type("version_conflict_engine_exception")));
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b -> b.took(2L).errors(true).items(List.of(conflictItem))));

        assertThatNoException().isThrownBy(() -> consumer.consume(
                new ConsumerRecord<>("inventory.public.products", 0, 2L, "{}", payload)));

        assertThat(meterRegistry.get("products.cdc.deleted").counter().count()).isEqualTo(0.0);
    }

    @Test
    void ignoresTombstone() throws IOException {
        assertThatNoException().isThrownBy(() -> consumer.consume(
                new ConsumerRecord<>("inventory.public.products", 0, 3L, "{}", null)));

        verify(client, never()).bulk(any(BulkRequest.class));
        assertThat(meterRegistry.get("products.cdc.tombstones").counter().count()).isEqualTo(1.0);
    }

    @Test
    void rejectsUnknownOperation() {
        assertThatThrownBy(() -> consumer.consume(
                new ConsumerRecord<>("inventory.public.products", 0, 4L, "{}", "{\"op\":\"x\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stock_quantity", "active", "version"})
    void rejectsMissingRequiredTypedField(String missingField) throws Exception {
        ObjectNode envelope = (ObjectNode) new ObjectMapper().readTree("""
                {
                  "after": {
                    "id": "3e71ea24-4bf8-4ad8-9188-c1b03293a9ce",
                    "sku": "PHONE-15",
                    "title": "iPhone 15",
                    "description": "Phone",
                    "category": "Electronics",
                    "price": "799.00",
                    "stock_quantity": 12,
                    "active": true,
                    "created_at": "2026-09-27T12:00:00Z",
                    "updated_at": "2026-09-27T12:00:00Z",
                    "version": 0
                  },
                  "op": "c"
                }
                """);
        ((ObjectNode) envelope.get("after")).remove(missingField);

        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>(
                "inventory.public.products", 0, 5L, "{}", envelope.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(missingField);
        verify(client, never()).bulk(any(BulkRequest.class));
    }

    @Test
    void processesBatchOfMultipleEventsInSingleBulkCall() throws IOException {
        String createPayload = """
                {
                  "after": {
                    "id": "id-1",
                    "sku": "SKU-1",
                    "title": "Item 1",
                    "category": "Cat",
                    "price": "10.00",
                    "stock_quantity": 5,
                    "active": true,
                    "created_at": "2026-09-27T12:00:00Z",
                    "updated_at": "2026-09-27T12:00:00Z",
                    "version": 0
                  },
                  "op": "c"
                }
                """;

        String updatePayload = """
                {
                  "after": {
                    "id": "id-2",
                    "sku": "SKU-2",
                    "title": "Item 2",
                    "category": "Cat",
                    "price": "20.00",
                    "stock_quantity": 10,
                    "active": true,
                    "created_at": "2026-09-27T12:00:00Z",
                    "updated_at": "2026-09-27T12:00:00Z",
                    "version": 1
                  },
                  "op": "u"
                }
                """;

        String deletePayload = """
                {"before":{"id":"id-3","version":0},"after":null,"op":"d"}
                """;

        ConsumerRecord<String, String> rec1 = new ConsumerRecord<>("inventory.public.products", 0, 10L, "key1", createPayload);
        ConsumerRecord<String, String> rec2 = new ConsumerRecord<>("inventory.public.products", 0, 11L, "key2", updatePayload);
        ConsumerRecord<String, String> rec3 = new ConsumerRecord<>("inventory.public.products", 0, 12L, "key3", deletePayload);

        BulkResponseItem item1 = BulkResponseItem.of(b -> b.operationType(OperationType.Index).id("id-1").index("products").status(200));
        BulkResponseItem item2 = BulkResponseItem.of(b -> b.operationType(OperationType.Index).id("id-2").index("products").status(200));
        BulkResponseItem item3 = BulkResponseItem.of(b -> b.operationType(OperationType.Delete).id("id-3").index("products").status(200));
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b -> b.took(5L).errors(false).items(List.of(item1, item2, item3))));

        consumer.consume(List.of(rec1, rec2, rec3));

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        BulkRequest request = captor.getValue();

        assertThat(request.operations()).hasSize(3);
        assertThat(meterRegistry.get("products.cdc.indexed").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("products.cdc.deleted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsCdcLagMetricFromSourceTsMs() throws IOException {
        long sourceCommitTs = System.currentTimeMillis() - 150L;
        String payload = """
                {
                  "source": {
                    "ts_ms": %d
                  },
                  "after": {
                    "id": "3e71ea24-4bf8-4ad8-9188-c1b03293a9ce",
                    "sku": "PHONE-15",
                    "title": "iPhone 15",
                    "category": "Electronics",
                    "price": "799.00",
                    "stock_quantity": 12,
                    "active": true,
                    "created_at": "2026-09-27T12:00:00Z",
                    "updated_at": "2026-09-27T12:00:00Z",
                    "version": 0
                  },
                  "op": "c"
                }
                """.formatted(sourceCommitTs);

        BulkResponseItem item = BulkResponseItem.of(b -> b
                .operationType(OperationType.Index)
                .id("3e71ea24-4bf8-4ad8-9188-c1b03293a9ce")
                .index("products")
                .status(200));
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b -> b.took(2L).errors(false).items(List.of(item))));

        consumer.consume(new ConsumerRecord<>("inventory.public.products", 0, 1L, "{}", payload));

        assertThat(meterRegistry.get("products.cdc.lag").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("products.cdc.lag").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(100.0);
    }
}
