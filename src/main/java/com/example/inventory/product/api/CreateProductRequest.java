package com.example.inventory.product.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String description,
        @NotBlank @Size(max = 100) String category,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal price,
        @NotNull @Min(0) Integer stockQuantity,
        @NotNull Boolean active
) {
}

