package com.example.inventory.search;

import com.example.inventory.api.GlobalExceptionHandler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private ProductSearchService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void acceptsValidPaginationWithinLimit() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new SearchResponse(List.of(), 0L, 0, 20));

        mockMvc.perform(get("/api/v1/search")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void rejectsPaginationExceedingMaxResultWindow() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                        .param("page", "500")
                        .param("size", "21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Pagination exceeds maximum result window")));
    }

    @Test
    void rejectsInvalidPriceRange() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                        .param("minPrice", "100.00")
                        .param("maxPrice", "50.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("minPrice must be less than or equal to maxPrice"));
    }

    @Test
    void returnsFacetsWhenRequested() throws Exception {
        SearchFacets facets = new SearchFacets(
                java.util.Map.of("Electronics", 5L),
                new SearchFacets.PriceStats(10.0, 100.0, 55.0, 5L),
                4L
        );
        when(service.search(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new SearchResponse(List.of(), 5L, 0, 20, facets));

        mockMvc.perform(get("/api/v1/search")
                        .param("includeFacets", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facets.categories.Electronics").value(5))
                .andExpect(jsonPath("$.facets.priceStats.min").value(10.0))
                .andExpect(jsonPath("$.facets.priceStats.max").value(100.0))
                .andExpect(jsonPath("$.facets.availableCount").value(4));
    }
}
