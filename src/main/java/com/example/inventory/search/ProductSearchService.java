package com.example.inventory.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ProductSearchService {

    public static final int MAX_RESULT_WINDOW = 10_000;

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final io.micrometer.core.instrument.Timer searchTimer;

    @Autowired
    public ProductSearchService(ElasticsearchClient client,
                                ElasticsearchProperties properties,
                                io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.client = client;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.searchTimer = io.micrometer.core.instrument.Timer.builder("products.search.duration")
                .description("Elasticsearch search query execution duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public ProductSearchService(ElasticsearchClient client, ElasticsearchProperties properties) {
        this(client, properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    public com.example.inventory.search.SearchResponse search(
            String text, String category, BigDecimal minPrice, BigDecimal maxPrice,
            Boolean available, int page, int size, SearchSort sort) throws IOException {
        return search(text, category, minPrice, maxPrice, available, page, size, sort, true);
    }

    public com.example.inventory.search.SearchResponse search(
            String text, String category, BigDecimal minPrice, BigDecimal maxPrice,
            Boolean available, int page, int size, SearchSort sort, boolean includeFacets) throws IOException {

        if ((long) (page + 1) * size > MAX_RESULT_WINDOW) {
            throw new IllegalArgumentException(
                    "Pagination offset and size exceed maximum allowed window (" + MAX_RESULT_WINDOW + "). " +
                    "Requested from=" + ((long) page * size) + ", size=" + size);
        }

        List<Query> filters = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            String normalizedCategory = category.trim().toLowerCase(Locale.ROOT);
            filters.add(Query.of(query -> query.term(term -> term.field("category").value(normalizedCategory))));
        }
        if (minPrice != null || maxPrice != null) {
            filters.add(Query.of(query -> query.range(range -> range.number(number -> {
                number.field("price");
                if (minPrice != null) number.gte(minPrice.doubleValue());
                if (maxPrice != null) number.lte(maxPrice.doubleValue());
                return number;
            }))));
        }
        if (available != null) {
            if (available) {
                filters.add(Query.of(query -> query.term(term -> term.field("active").value(true))));
                filters.add(Query.of(query -> query.range(range -> range.number(number ->
                        number.field("stockQuantity").gt(0.0)))));
            } else {
                filters.add(Query.of(query -> query.bool(bool -> bool
                        .should(
                                Query.of(q -> q.term(t -> t.field("active").value(false))),
                                Query.of(q -> q.range(r -> r.number(n -> n.field("stockQuantity").lte(0.0))))
                        )
                        .minimumShouldMatch("1"))));
            }
        }

        Query query;
        if (text == null || text.isBlank()) {
            query = Query.of(q -> q.bool(bool -> bool
                    .must(matchAll())
                    .filter(filters)));
        } else {
            String normalized = text.trim();
            List<Query> should = List.of(
                    Query.of(q -> q.multiMatch(m -> m
                            .query(normalized)
                            .fields("title^4", "description")
                            .fuzziness(normalized.length() >= 4 ? "AUTO" : "0"))),
                    Query.of(q -> q.match(m -> m
                            .field("title.autocomplete")
                            .query(normalized)
                            .boost(2.0f)))
            );
            query = Query.of(q -> q.bool(bool -> bool
                    .should(should)
                    .minimumShouldMatch("1")
                    .filter(filters)));
        }

        java.util.Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggs = new java.util.HashMap<>();
        if (includeFacets) {
            aggs.put("categories", co.elastic.clients.elasticsearch._types.aggregations.Aggregation.of(a ->
                    a.terms(t -> t.field("category").size(50))));
            aggs.put("price_stats", co.elastic.clients.elasticsearch._types.aggregations.Aggregation.of(a ->
                    a.stats(s -> s.field("price"))));
            aggs.put("available_count", co.elastic.clients.elasticsearch._types.aggregations.Aggregation.of(a ->
                    a.filter(f -> f.bool(b -> b.filter(
                            Query.of(q -> q.term(t -> t.field("active").value(true))),
                            Query.of(q -> q.range(r -> r.number(n -> n.field("stockQuantity").gt(0.0))))
                    )))));
        }

        List<SortOptions> sorts = sortOptions(sort);
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(meterRegistry);
        SearchResponse<ProductSearchDocument> response;
        try {
            response = client.search(request -> {
                request.index(properties.aliasName())
                        .from(page * size)
                        .size(size)
                        .query(query)
                        .trackTotalHits(hit -> hit.enabled(true));
                if (!sorts.isEmpty()) request.sort(sorts);
                if (includeFacets) request.aggregations(aggs);
                return request;
            }, ProductSearchDocument.class);
        } finally {
            sample.stop(searchTimer);
        }

        List<ProductSearchDocument> items = response.hits().hits().stream()
                .map(Hit::source)
                .toList();
        long total = response.hits().total() == null ? items.size() : response.hits().total().value();

        SearchFacets facets = null;
        if (includeFacets && response.aggregations() != null && !response.aggregations().isEmpty()) {
            java.util.Map<String, Long> categories = new java.util.LinkedHashMap<>();
            co.elastic.clients.elasticsearch._types.aggregations.Aggregate catAgg = response.aggregations().get("categories");
            if (catAgg != null && catAgg.isSterms()) {
                for (var bucket : catAgg.sterms().buckets().array()) {
                    categories.put(bucket.key().stringValue(), bucket.docCount());
                }
            }

            SearchFacets.PriceStats priceStats = null;
            co.elastic.clients.elasticsearch._types.aggregations.Aggregate priceAgg = response.aggregations().get("price_stats");
            if (priceAgg != null && priceAgg.isStats()) {
                var stats = priceAgg.stats();
                priceStats = new SearchFacets.PriceStats(
                        stats.count() > 0 ? stats.min() : null,
                        stats.count() > 0 ? stats.max() : null,
                        stats.count() > 0 ? stats.avg() : null,
                        stats.count()
                );
            }

            long availableCount = 0L;
            co.elastic.clients.elasticsearch._types.aggregations.Aggregate availAgg = response.aggregations().get("available_count");
            if (availAgg != null && availAgg.isFilter()) {
                availableCount = availAgg.filter().docCount();
            }

            facets = new SearchFacets(categories, priceStats, availableCount);
        }

        return new com.example.inventory.search.SearchResponse(items, total, page, size, facets);
    }

    private static Query matchAll() {
        return Query.of(query -> query.matchAll(matchAll -> matchAll));
    }

    private static List<SortOptions> sortOptions(SearchSort sort) {
        return switch (sort) {
            case RELEVANCE -> List.of();
            case PRICE_ASC -> List.of(fieldSort("price", SortOrder.Asc));
            case PRICE_DESC -> List.of(fieldSort("price", SortOrder.Desc));
            case NEWEST -> List.of(fieldSort("createdAt", SortOrder.Desc));
        };
    }

    private static SortOptions fieldSort(String field, SortOrder order) {
        return SortOptions.of(sort -> sort.field(value -> value.field(field).order(order)));
    }
}

