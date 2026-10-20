package com.example.inventory.admin;

import jakarta.validation.constraints.Min;

public record ReindexRequest(
        String targetIndex,
        @Min(1) Integer batchSize,
        Boolean switchAlias
) {
}
