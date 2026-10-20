package com.example.inventory.product.api;

import com.example.inventory.product.Product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String sku,
        String title,
        String description,
        String category,
        BigDecimal price,
        int stockQuantity,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(), product.getSku(), product.getTitle(), product.getDescription(),
                product.getCategory(), product.getPrice(), product.getStockQuantity(),
                product.isActive(), product.getCreatedAt(), product.getUpdatedAt(), product.getVersion());
    }
}

