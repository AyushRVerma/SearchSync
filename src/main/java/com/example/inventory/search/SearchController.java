package com.example.inventory.search;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;

@Validated
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final ProductSearchService service;

    public SearchController(ProductSearchService service) {
        this.service = service;
    }

    @GetMapping
    public SearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DecimalMin("0.00") BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0.00") BigDecimal maxPrice,
            @RequestParam(required = false) Boolean available,
            @RequestParam(defaultValue = "0") @Min(0) @Max(9999) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "RELEVANCE") SearchSort sort,
            @RequestParam(defaultValue = "true") boolean includeFacets) throws IOException {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("minPrice must be less than or equal to maxPrice");
        }
        if ((long) (page + 1) * size > ProductSearchService.MAX_RESULT_WINDOW) {
            throw new IllegalArgumentException(
                    "Pagination exceeds maximum result window of " + ProductSearchService.MAX_RESULT_WINDOW +
                    ": page " + page + " with size " + size + " exceeds the limit (" + ((long) page * size + size) + " > " + ProductSearchService.MAX_RESULT_WINDOW + ")");
        }
        return service.search(q, category, minPrice, maxPrice, available, page, size, sort, includeFacets);
    }
}

