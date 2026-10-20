package com.example.inventory.product.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class PatchProductRequest {

    @Size(max = 200)
    @Pattern(regexp = ".*\\S.*", message = "must contain a non-whitespace character")
    private String title;

    @Size(max = 4000)
    private String description;

    @Size(max = 100)
    @Pattern(regexp = ".*\\S.*", message = "must contain a non-whitespace character")
    private String category;

    @DecimalMin("0.00")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal price;

    @Min(0)
    private Integer stockQuantity;

    private Boolean active;

    private Long version;

    private final Set<String> presentFields;

    public PatchProductRequest() {
        this.presentFields = new HashSet<>();
    }

    public PatchProductRequest(String title, String description, String category,
                               BigDecimal price, Integer stockQuantity, Boolean active, Long version) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.active = active;
        this.version = version;
        this.presentFields = new HashSet<>();
        if (title != null) this.presentFields.add("title");
        if (description != null) this.presentFields.add("description");
        if (category != null) this.presentFields.add("category");
        if (price != null) this.presentFields.add("price");
        if (stockQuantity != null) this.presentFields.add("stockQuantity");
        if (active != null) this.presentFields.add("active");
        if (version != null) this.presentFields.add("version");
    }

    public PatchProductRequest(String title, String description, String category,
                               BigDecimal price, Integer stockQuantity, Boolean active, Long version,
                               Set<String> presentFields) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.active = active;
        this.version = version;
        this.presentFields = presentFields != null ? new HashSet<>(presentFields) : new HashSet<>();
    }

    @JsonSetter(value = "title")
    public void setTitle(String title) {
        this.title = title;
        this.presentFields.add("title");
    }

    @JsonSetter(value = "description", nulls = Nulls.SET)
    public void setDescription(String description) {
        this.description = description;
        this.presentFields.add("description");
    }

    @JsonSetter(value = "category")
    public void setCategory(String category) {
        this.category = category;
        this.presentFields.add("category");
    }

    @JsonSetter(value = "price")
    public void setPrice(BigDecimal price) {
        this.price = price;
        this.presentFields.add("price");
    }

    @JsonSetter(value = "stockQuantity")
    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
        this.presentFields.add("stockQuantity");
    }

    @JsonSetter(value = "active")
    public void setActive(Boolean active) {
        this.active = active;
        this.presentFields.add("active");
    }

    @JsonSetter(value = "version")
    public void setVersion(Long version) {
        this.version = version;
        this.presentFields.add("version");
    }

    public String title() { return title; }
    public String description() { return description; }
    public String category() { return category; }
    public BigDecimal price() { return price; }
    public Integer stockQuantity() { return stockQuantity; }
    public Boolean active() { return active; }
    public Long version() { return version; }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public Integer getStockQuantity() { return stockQuantity; }
    public Boolean getActive() { return active; }
    public Long getVersion() { return version; }

    public boolean hasTitle() { return presentFields.contains("title"); }
    public boolean hasDescription() { return presentFields.contains("description"); }
    public boolean hasCategory() { return presentFields.contains("category"); }
    public boolean hasPrice() { return presentFields.contains("price"); }
    public boolean hasStockQuantity() { return presentFields.contains("stockQuantity"); }
    public boolean hasActive() { return presentFields.contains("active"); }
    public boolean hasVersion() { return presentFields.contains("version"); }
    public Set<String> presentFields() { return Collections.unmodifiableSet(presentFields); }
}
