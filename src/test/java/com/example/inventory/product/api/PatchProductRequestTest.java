package com.example.inventory.product.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PatchProductRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesExplicitNullDescription() throws Exception {
        String json = "{\"description\": null}";
        PatchProductRequest request = objectMapper.readValue(json, PatchProductRequest.class);

        assertThat(request.hasDescription()).isTrue();
        assertThat(request.description()).isNull();
        assertThat(request.hasTitle()).isFalse();
    }

    @Test
    void deserializesNonNullDescription() throws Exception {
        String json = "{\"description\": \"A updated description\"}";
        PatchProductRequest request = objectMapper.readValue(json, PatchProductRequest.class);

        assertThat(request.hasDescription()).isTrue();
        assertThat(request.description()).isEqualTo("A updated description");
    }

    @Test
    void tracksOmittedDescription() throws Exception {
        String json = "{\"price\": 49.99, \"title\": \"New Phone\"}";
        PatchProductRequest request = objectMapper.readValue(json, PatchProductRequest.class);

        assertThat(request.hasDescription()).isFalse();
        assertThat(request.description()).isNull();
        assertThat(request.hasTitle()).isTrue();
        assertThat(request.title()).isEqualTo("New Phone");
        assertThat(request.hasPrice()).isTrue();
        assertThat(request.price()).isEqualTo(new BigDecimal("49.99"));
    }
}
