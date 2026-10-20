package com.example.inventory.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 4000)
    private String description;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Product() {
    }

    public Product(UUID id, String sku, String title, String description, String category,
                   BigDecimal price, int stockQuantity, boolean active) {
        this.id = id;
        this.sku = sku;
        this.title = title;
        this.description = description;
        this.category = category;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.active = active;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void patch(String title, boolean updateDescription, String description,
                      String category, BigDecimal price, Integer stockQuantity, Boolean active) {
        if (title != null) this.title = title;
        if (updateDescription) this.description = description;
        if (category != null) this.category = category;
        if (price != null) this.price = price;
        if (stockQuantity != null) this.stockQuantity = stockQuantity;
        if (active != null) this.active = active;
    }

    public void patch(String title, String description, String category, BigDecimal price,
                      Integer stockQuantity, Boolean active) {
        patch(title, description != null, description, category, price, stockQuantity, active);
    }

    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public int getStockQuantity() { return stockQuantity; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}

