package com.example.inventory.product;

public class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException(String sku) {
        super("A product with SKU '" + sku + "' already exists");
    }
}

