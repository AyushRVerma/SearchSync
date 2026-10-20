package com.example.inventory.api;

import com.example.inventory.product.DuplicateSkuException;
import com.example.inventory.product.ProductNotFoundException;
import com.example.inventory.product.VersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ProductNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request.getRequestURI(), Map.of());
    }

    @ExceptionHandler({DuplicateSkuException.class, VersionConflictException.class, org.springframework.dao.OptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> conflict(Exception exception, HttpServletRequest request) {
        String message = (exception instanceof org.springframework.dao.OptimisticLockingFailureException)
                ? "The resource was modified concurrently by another transaction. Please retry with the latest version."
                : exception.getMessage();
        return error(HttpStatus.CONFLICT, message, request.getRequestURI(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(field -> fields.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", request.getRequestURI(), fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraintViolation(ConstraintViolationException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (jakarta.validation.ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            String property = violation.getPropertyPath().toString();
            int lastDot = property.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < property.length() - 1) {
                property = property.substring(lastDot + 1);
            }
            fields.putIfAbsent(property, violation.getMessage());
        }
        return error(HttpStatus.BAD_REQUEST, "Validation constraint violation", request.getRequestURI(), fields);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        String requiredType = exception.getRequiredType() != null ? exception.getRequiredType().getSimpleName() : "unknown";
        String message = String.format("Parameter '%s' with value '%s' could not be converted to type '%s'",
                exception.getName(), exception.getValue(), requiredType);
        Map<String, String> fields = Map.of(exception.getName(), "Invalid " + requiredType + " format");
        return error(HttpStatus.BAD_REQUEST, message, request.getRequestURI(), fields);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> notReadable(org.springframework.http.converter.HttpMessageNotReadableException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Malformed or unreadable JSON request body", request.getRequestURI(), Map.of());
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request.getRequestURI(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internalError(Exception exception, HttpServletRequest request) {
        log.error("Unhandled server exception at {}", request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal server error occurred", request.getRequestURI(), Map.of());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, String path,
                                           Map<String, String> fields) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, fields);
        return ResponseEntity.status(status).body(body);
    }
}

