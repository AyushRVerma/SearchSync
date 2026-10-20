package com.example.inventory.admin;

public record ReindexResponse(
        String targetIndex,
        long totalProductsInDatabase,
        long indexedCount,
        long failedCount,
        long durationMillis,
        boolean aliasSwitched,
        String status
) {
}
