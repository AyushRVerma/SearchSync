package com.example.inventory.search;

import java.util.List;

public record SearchResponse(
        List<ProductSearchDocument> items,
        long total,
        int page,
        int size,
        SearchFacets facets
) {
    public SearchResponse(List<ProductSearchDocument> items, long total, int page, int size) {
        this(items, total, page, size, null);
    }
}
