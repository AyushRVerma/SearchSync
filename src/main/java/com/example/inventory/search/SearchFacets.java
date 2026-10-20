package com.example.inventory.search;

import java.util.Map;

public record SearchFacets(
        Map<String, Long> categories,
        PriceStats priceStats,
        long availableCount
) {
    public record PriceStats(
            Double min,
            Double max,
            Double avg,
            long count
    ) {
    }
}
