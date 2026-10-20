package com.example.inventory.product;

public class VersionConflictException extends RuntimeException {
    public VersionConflictException() {
        super("The product was changed by another request; reload it and retry");
    }
}

