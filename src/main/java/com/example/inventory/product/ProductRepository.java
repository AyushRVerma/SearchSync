package com.example.inventory.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsBySkuIgnoreCase(String sku);
}

