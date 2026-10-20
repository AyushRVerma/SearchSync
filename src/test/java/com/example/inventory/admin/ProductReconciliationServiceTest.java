package com.example.inventory.admin;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.example.inventory.product.Product;
import com.example.inventory.product.ProductRepository;
import com.example.inventory.search.ElasticsearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductReconciliationServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ElasticsearchIndicesClient indicesClient;

    private ElasticsearchProperties properties;
    private ProductReconciliationService service;

    @BeforeEach
    void setUp() {
        properties = new ElasticsearchProperties("http://localhost:9200", "products-v1", "products", false);
        lenient().when(client.indices()).thenReturn(indicesClient);
        service = new ProductReconciliationService(productRepository, client, properties);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reindexAllProductsSuccess() throws IOException {
        Product p1 = new Product(UUID.randomUUID(), "SKU-1", "Laptop", "Gaming laptop",
                "Electronics", new BigDecimal("1200.00"), 10, true);
        Product p2 = new Product(UUID.randomUUID(), "SKU-2", "Mouse", "Wireless mouse",
                "Electronics", new BigDecimal("40.00"), 50, true);

        when(productRepository.count()).thenReturn(2L);
        when(productRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(p1, p2)));

        when(indicesClient.exists(any(Function.class))).thenReturn(new BooleanResponse(true));
        when(indicesClient.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(true));

        BulkResponseItem item1 = BulkResponseItem.of(b -> b
                .operationType(co.elastic.clients.elasticsearch.core.bulk.OperationType.Index)
                .id(p1.getId().toString())
                .index("products-v2")
                .status(200));
        BulkResponseItem item2 = BulkResponseItem.of(b -> b
                .operationType(co.elastic.clients.elasticsearch.core.bulk.OperationType.Index)
                .id(p2.getId().toString())
                .index("products-v2")
                .status(200));
        BulkResponse bulkResponse = BulkResponse.of(b -> b.took(10L).errors(false).items(List.of(item1, item2)));
        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);

        ReindexRequest request = new ReindexRequest("products-v2", 100, true);
        ReindexResponse response = service.reindex(request);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.targetIndex()).isEqualTo("products-v2");
        assertThat(response.totalProductsInDatabase()).isEqualTo(2);
        assertThat(response.indexedCount()).isEqualTo(2);
        assertThat(response.failedCount()).isEqualTo(0);
        assertThat(response.aliasSwitched()).isTrue();

        ArgumentCaptor<BulkRequest> bulkCaptor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(bulkCaptor.capture());
        BulkRequest capturedBulk = bulkCaptor.getValue();
        assertThat(capturedBulk.operations()).hasSize(2);
        assertThat(capturedBulk.operations().get(0).index().versionType()).isEqualTo(VersionType.ExternalGte);
        assertThat(capturedBulk.operations().get(0).index().version()).isEqualTo(1L); // p1.version + 1
        assertThat(capturedBulk.operations().get(1).index().version()).isEqualTo(1L); // p2.version + 1

        verify(indicesClient).refresh(any(Function.class));
        verify(indicesClient).updateAliases(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reindexRecordsBulkItemErrors() throws IOException {
        Product p1 = new Product(UUID.randomUUID(), "SKU-1", "Laptop", null,
                "Electronics", new BigDecimal("1200.00"), 10, true);

        when(productRepository.count()).thenReturn(1L);
        when(productRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(p1)));
        when(indicesClient.exists(any(Function.class))).thenReturn(new BooleanResponse(true));

        BulkResponseItem errorItem = BulkResponseItem.of(b -> b
                .operationType(co.elastic.clients.elasticsearch.core.bulk.OperationType.Index)
                .id(p1.getId().toString())
                .index("products-v1")
                .status(400)
                .error(e -> e.reason("mapper_parsing_exception")));
        BulkResponse bulkResponse = BulkResponse.of(b -> b.took(5L).errors(true).items(List.of(errorItem)));
        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);

        ReindexResponse response = service.reindex(null);

        assertThat(response.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(response.totalProductsInDatabase()).isEqualTo(1);
        assertThat(response.indexedCount()).isEqualTo(0);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.aliasSwitched()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void switchAliasUpdatesElasticsearchAliases() throws IOException {
        when(indicesClient.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(true));

        service.switchAliasTo("products-v2");

        verify(indicesClient).updateAliases(any(Function.class));
    }
}
