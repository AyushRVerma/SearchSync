package com.example.inventory.admin;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import com.example.inventory.product.Product;
import com.example.inventory.product.ProductRepository;
import com.example.inventory.search.ElasticsearchProperties;
import com.example.inventory.search.ProductSearchDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProductReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ProductReconciliationService.class);
    public static final int DEFAULT_BATCH_SIZE = 500;

    private final ProductRepository productRepository;
    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public ProductReconciliationService(ProductRepository productRepository,
                                        ElasticsearchClient client,
                                        ElasticsearchProperties properties) {
        this.productRepository = productRepository;
        this.client = client;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ReindexResponse reindex(ReindexRequest request) throws IOException {
        long startTime = System.currentTimeMillis();
        String targetIndex = (request != null && request.targetIndex() != null && !request.targetIndex().isBlank())
                ? request.targetIndex().trim()
                : properties.indexName();
        int batchSize = (request != null && request.batchSize() != null && request.batchSize() > 0)
                ? request.batchSize()
                : DEFAULT_BATCH_SIZE;
        boolean switchAlias = (request != null && request.switchAlias() != null && request.switchAlias());

        ensureIndexExists(targetIndex);

        long totalCount = productRepository.count();
        long indexed = 0;
        long failed = 0;

        int pageIndex = 0;
        Page<Product> page;
        do {
            page = productRepository.findAll(PageRequest.of(pageIndex, batchSize, Sort.by("id")));
            if (!page.isEmpty()) {
                BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
                for (Product product : page.getContent()) {
                    ProductSearchDocument doc = toSearchDocument(product);
                    long esVersion = doc.version() + 1L;
                    bulkBuilder.operations(op -> op.index(idx -> idx
                            .index(targetIndex)
                            .id(doc.id())
                            .document(doc)
                            .versionType(VersionType.ExternalGte)
                            .version(esVersion)
                    ));
                }

                BulkResponse bulkResponse = client.bulk(bulkBuilder.build());
                for (BulkResponseItem item : bulkResponse.items()) {
                    if (item.error() != null) {
                        failed++;
                        log.error("Failed to index product ID {} during reindex: {}", item.id(), item.error().reason());
                    } else {
                        indexed++;
                    }
                }
            }
            pageIndex++;
        } while (page.hasNext());

        client.indices().refresh(r -> r.index(targetIndex));

        boolean aliasSwapped = false;
        if (switchAlias) {
            switchAliasTo(targetIndex);
            aliasSwapped = true;
        }

        long duration = System.currentTimeMillis() - startTime;
        String status = failed == 0 ? "SUCCESS" : "COMPLETED_WITH_ERRORS";
        log.info("Reindex completed in {} ms: total={}, indexed={}, failed={}, targetIndex={}, aliasSwapped={}",
                duration, totalCount, indexed, failed, targetIndex, aliasSwapped);

        return new ReindexResponse(targetIndex, totalCount, indexed, failed, duration, aliasSwapped, status);
    }

    public void switchAliasTo(String targetIndex) throws IOException {
        boolean aliasExists = client.indices().existsAlias(r -> r.name(properties.aliasName())).value();
        List<Action> actions = new ArrayList<>();
        if (aliasExists) {
            actions.add(Action.of(a -> a.remove(r -> r.index("*").alias(properties.aliasName()))));
        }
        actions.add(Action.of(a -> a.add(ad -> ad.index(targetIndex).alias(properties.aliasName()))));
        client.indices().updateAliases(builder -> builder.actions(actions));
        log.info("Atomically switched alias {} to point to index {}", properties.aliasName(), targetIndex);
    }

    private void ensureIndexExists(String indexName) throws IOException {
        boolean exists = client.indices().exists(r -> r.index(indexName)).value();
        if (!exists) {
            try (InputStream mapping = new ClassPathResource("elasticsearch/products-index.json").getInputStream()) {
                client.indices().create(b -> b.index(indexName).withJson(mapping));
            }
            log.info("Created missing target index {} with default mapping", indexName);
        }
    }

    private ProductSearchDocument toSearchDocument(Product product) {
        Instant now = Instant.now();
        return new ProductSearchDocument(
                product.getId().toString(),
                product.getSku(),
                product.getTitle(),
                product.getDescription(),
                product.getCategory(),
                product.getPrice(),
                product.getStockQuantity(),
                product.isActive(),
                product.getCreatedAt() != null ? product.getCreatedAt().toString() : now.toString(),
                product.getUpdatedAt() != null ? product.getUpdatedAt().toString() : now.toString(),
                product.getVersion()
        );
    }
}
