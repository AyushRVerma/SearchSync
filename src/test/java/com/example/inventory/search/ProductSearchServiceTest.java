package com.example.inventory.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTest {

    @Mock
    private ElasticsearchClient client;

    private ProductSearchService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        service = new ProductSearchService(
                client,
                new ElasticsearchProperties("http://localhost:9200", "products-v1", "products", false)
        );

        HitsMetadata<ProductSearchDocument> hitsMetadata = HitsMetadata.of(h -> h
                .hits(List.of())
                .total(t -> t.value(0L).relation(TotalHitsRelation.Eq)));
        co.elastic.clients.elasticsearch.core.SearchResponse<ProductSearchDocument> mockResponse =
                co.elastic.clients.elasticsearch.core.SearchResponse.of(r -> r
                        .took(1L)
                        .timedOut(false)
                        .shards(s -> s.total(1).successful(1).skipped(0).failed(0))
                        .hits(hitsMetadata));

        org.mockito.Mockito.lenient().when(client.search(any(Function.class), eq(ProductSearchDocument.class))).thenReturn(mockResponse);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchAvailableTrueFiltersByActiveAndPositiveStock() throws IOException {
        service.search(null, null, null, null, true, 0, 20, SearchSort.RELEVANCE);

        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).search(captor.capture(), eq(ProductSearchDocument.class));

        SearchRequest.Builder builder = new SearchRequest.Builder();
        captor.getValue().apply(builder);
        SearchRequest request = builder.build();

        List<Query> filters = request.query().bool().filter();
        assertThat(filters).hasSize(2);

        // Filter 1: active == true
        assertThat(filters.get(0).isTerm()).isTrue();
        assertThat(filters.get(0).term().field()).isEqualTo("active");
        assertThat(filters.get(0).term().value().booleanValue()).isTrue();

        // Filter 2: stockQuantity > 0
        assertThat(filters.get(1).isRange()).isTrue();
        assertThat(filters.get(1).range().number().field()).isEqualTo("stockQuantity");
        assertThat(filters.get(1).range().number().gt()).isEqualTo(0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchAvailableFalseFiltersByInactiveOrOutOfStock() throws IOException {
        service.search(null, null, null, null, false, 0, 20, SearchSort.RELEVANCE);

        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).search(captor.capture(), eq(ProductSearchDocument.class));

        SearchRequest.Builder builder = new SearchRequest.Builder();
        captor.getValue().apply(builder);
        SearchRequest request = builder.build();

        List<Query> filters = request.query().bool().filter();
        assertThat(filters).hasSize(1);

        // Filter: bool { should: [active == false, stockQuantity <= 0], minimum_should_match: 1 }
        assertThat(filters.get(0).isBool()).isTrue();
        BoolQuery boolFilter = filters.get(0).bool();
        assertThat(boolFilter.minimumShouldMatch()).isEqualTo("1");

        List<Query> shouldClauses = boolFilter.should();
        assertThat(shouldClauses).hasSize(2);

        // Should clause 1: active == false
        assertThat(shouldClauses.get(0).isTerm()).isTrue();
        assertThat(shouldClauses.get(0).term().field()).isEqualTo("active");
        assertThat(shouldClauses.get(0).term().value().booleanValue()).isFalse();

        // Should clause 2: stockQuantity <= 0
        assertThat(shouldClauses.get(1).isRange()).isTrue();
        assertThat(shouldClauses.get(1).range().number().field()).isEqualTo("stockQuantity");
        assertThat(shouldClauses.get(1).range().number().lte()).isEqualTo(0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithCategoryAndPriceRangeFilters() throws IOException {
        service.search("phone", "Electronics", new BigDecimal("100.00"), new BigDecimal("500.00"),
                null, 1, 10, SearchSort.PRICE_ASC);

        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).search(captor.capture(), eq(ProductSearchDocument.class));

        SearchRequest.Builder builder = new SearchRequest.Builder();
        captor.getValue().apply(builder);
        SearchRequest request = builder.build();

        assertThat(request.from()).isEqualTo(10);
        assertThat(request.size()).isEqualTo(10);

        List<Query> filters = request.query().bool().filter();
        assertThat(filters).hasSize(2);

        // Category filter (normalized lowercase)
        assertThat(filters.get(0).isTerm()).isTrue();
        assertThat(filters.get(0).term().field()).isEqualTo("category");
        assertThat(filters.get(0).term().value().stringValue()).isEqualTo("electronics");

        // Price range filter
        assertThat(filters.get(1).isRange()).isTrue();
        assertThat(filters.get(1).range().number().field()).isEqualTo("price");
        assertThat(filters.get(1).range().number().gte()).isEqualTo(100.0);
        assertThat(filters.get(1).range().number().lte()).isEqualTo(500.0);

        // Text query: should clauses with autocomplete and multi-match
        List<Query> should = request.query().bool().should();
        assertThat(should).hasSize(2);
        assertThat(should.get(0).isMultiMatch()).isTrue();
        assertThat(should.get(1).isMatch()).isTrue();
    }

    @Test
    void rejectsPaginationExceedingMaxResultWindow() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.search(null, null, null, null, null, 500, 21, SearchSort.RELEVANCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pagination offset and size exceed maximum allowed window");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchIncludesAggregationsWhenRequested() throws IOException {
        service.search(null, null, null, null, null, 0, 10, SearchSort.RELEVANCE, true);

        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).search(captor.capture(), eq(ProductSearchDocument.class));

        SearchRequest.Builder builder = new SearchRequest.Builder();
        captor.getValue().apply(builder);
        SearchRequest request = builder.build();

        assertThat(request.aggregations()).containsKey("categories");
        assertThat(request.aggregations()).containsKey("price_stats");
        assertThat(request.aggregations()).containsKey("available_count");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchExcludesAggregationsWhenFacetsDisabled() throws IOException {
        service.search(null, null, null, null, null, 0, 10, SearchSort.RELEVANCE, false);

        ArgumentCaptor<Function> captor = ArgumentCaptor.forClass(Function.class);
        verify(client).search(captor.capture(), eq(ProductSearchDocument.class));

        SearchRequest.Builder builder = new SearchRequest.Builder();
        captor.getValue().apply(builder);
        SearchRequest request = builder.build();

        assertThat(request.aggregations()).isEmpty();
    }

    @Test
    void recordsSearchDurationMetric() throws IOException {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ProductSearchService timedService = new ProductSearchService(
                client,
                new ElasticsearchProperties("http://localhost:9200", "products-v1", "products", false),
                registry
        );

        timedService.search(null, null, null, null, null, 0, 10, SearchSort.RELEVANCE);

        assertThat(registry.get("products.search.duration").timer().count()).isEqualTo(1);
    }
}
