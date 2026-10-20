package com.example.inventory.api;

import com.example.inventory.product.DuplicateSkuException;
import com.example.inventory.product.ProductNotFoundException;
import com.example.inventory.product.VersionConflictException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest("GET", "/api/v1/test");
    }

    @Test
    void handlesOptimisticLockingFailureExceptionAsConflict() {
        OptimisticLockingFailureException exception =
                new OptimisticLockingFailureException("Row was updated or deleted by another transaction");

        ResponseEntity<ApiError> response = handler.conflict(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().message()).contains("modified concurrently");
    }

    @Test
    void handlesMethodArgumentTypeMismatchException() {
        MethodArgumentTypeMismatchException exception =
                new MethodArgumentTypeMismatchException("not-a-uuid", UUID.class, "id", null, null);

        ResponseEntity<ApiError> response = handler.typeMismatch(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).contains("Parameter 'id' with value 'not-a-uuid' could not be converted to type 'UUID'");
        assertThat(response.getBody().fieldErrors()).containsEntry("id", "Invalid UUID format");
    }

    @Test
    void handlesHttpMessageNotReadableException() {
        HttpMessageNotReadableException exception =
                new HttpMessageNotReadableException("JSON parse error", new MockHttpInputMessage("invalid json".getBytes()));

        ResponseEntity<ApiError> response = handler.notReadable(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("Malformed or unreadable JSON request body");
    }

    @Test
    void handlesConstraintViolationExceptionWithPopulatedFields() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("search.size");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be less than or equal to 100");

        ConstraintViolationException exception = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ApiError> response = handler.constraintViolation(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldErrors()).containsEntry("size", "must be less than or equal to 100");
    }

    @Test
    void handlesProductNotFoundException() {
        UUID id = UUID.randomUUID();
        ProductNotFoundException exception = new ProductNotFoundException(id);

        ResponseEntity<ApiError> response = handler.notFound(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains(id.toString());
    }

    @Test
    void handlesUnexpectedExceptionAsInternalServerError() {
        RuntimeException exception = new RuntimeException("Unexpected runtime glitch");

        ResponseEntity<ApiError> response = handler.internalError(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("An unexpected internal server error occurred");
    }
}
