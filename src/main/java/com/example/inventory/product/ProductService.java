package com.example.inventory.product;

import com.example.inventory.product.api.CreateProductRequest;
import com.example.inventory.product.api.PatchProductRequest;
import com.example.inventory.product.api.ProductResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public ProductResponse create(CreateProductRequest request) {
        String sku = request.sku().trim().toUpperCase(Locale.ROOT);
        if (repository.existsBySkuIgnoreCase(sku)) {
            throw new DuplicateSkuException(sku);
        }
        Product product = new Product(
                UUID.randomUUID(), sku, request.title().trim(), normalizeNullable(request.description()),
                request.category().trim(), request.price(), request.stockQuantity(), request.active());
        try {
            return ProductResponse.from(repository.saveAndFlush(product));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateSkuException(sku);
        }
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        return ProductResponse.from(find(id));
    }

    public ProductResponse patch(UUID id, PatchProductRequest request) {
        Product product = find(id);
        if (request.version() != null && request.version() != product.getVersion()) {
            throw new VersionConflictException();
        }
        product.patch(
                trimNullable(request.title()),
                request.hasDescription(),
                normalizeNullable(request.description()),
                trimNullable(request.category()),
                request.price(),
                request.stockQuantity(),
                request.active());
        return ProductResponse.from(repository.saveAndFlush(product));
    }

    public void delete(UUID id) {
        Product product = find(id);
        repository.delete(product);
        repository.flush();
    }

    private Product find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    private static String trimNullable(String value) {
        return value == null ? null : value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null ? null : value.trim();
    }
}

