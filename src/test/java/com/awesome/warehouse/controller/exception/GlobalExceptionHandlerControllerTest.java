package com.awesome.warehouse.controller.exception;

import com.awesome.warehouse.dto.ErrorDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerControllerTest {

    private final GlobalExceptionHandlerController controller = new GlobalExceptionHandlerController();

    @Test
    void answers503WhenNoDatabaseConnectionIsAvailable() {
        ResponseEntity<ErrorDto> response = controller.handleDatabaseUnavailable(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Database unavailable");
    }
}
