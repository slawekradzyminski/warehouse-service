package com.awesome.warehouse.controller.exception;

import com.awesome.warehouse.dto.ErrorDto;
import com.awesome.warehouse.dto.stock.StockErrorDto;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/** API error bodies, identical to the backend's: {@code ErrorDto} or field-to-message maps. */
@RestControllerAdvice
public class GlobalExceptionHandlerController {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorDto> handleCustomException(CustomException ex) {
        return ResponseEntity
                .status(ex.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorDto(ex.getMessage()));
    }

    /** Stock API errors that name the product: {@code {"message":...,"productId":1}}. */
    @ExceptionHandler(StockException.class)
    public ResponseEntity<StockErrorDto> handleStockException(StockException ex) {
        return ResponseEntity
                .status(ex.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new StockErrorDto(ex.getMessage(), ex.getProductId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String key = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            errors.put(key, error.getDefaultMessage());
        });
        return ResponseEntity
                .badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(errors);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        Map<String, String> errors = new HashMap<>();
        errors.put("error", ex.getMessage());
        return ResponseEntity
                .badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(errors);
    }

    /**
     * The database cannot be reached (no pooled connection within the 5 s connection timeout, or a
     * refused connection): 503, so callers see a retryable outage instead of a generic 500.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            org.hibernate.exception.JDBCConnectionException.class})
    public ResponseEntity<ErrorDto> handleDatabaseUnavailable(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorDto(DatabaseUnavailable.MESSAGE));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorDto> handleAccessDeniedException(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorDto("Access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorDto> handleAuthenticationException(AuthenticationException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorDto("Unauthorized"));
    }
}
