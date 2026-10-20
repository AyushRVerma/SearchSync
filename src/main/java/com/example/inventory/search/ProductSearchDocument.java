package com.example.inventory.search;

import java.math.BigDecimal;

public record ProductSearchDocument(
        String id,
        String sku,
        String title,
        String description,
        String category,
        BigDecimal price,
        int stockQuantity,
        boolean active,
        String createdAt,
        String updatedAt,
        long version
) {
}

