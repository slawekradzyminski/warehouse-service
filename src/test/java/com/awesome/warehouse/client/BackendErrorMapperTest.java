package com.awesome.warehouse.client;

import com.awesome.warehouse.controller.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BackendErrorMapperTest {

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 409, 422})
    void clientErrorsKeepStatusAndMessage(int status) {
        CustomException e = BackendErrorMapper.fromResponse(HttpStatusCode.valueOf(status), json("{\"message\":\"Nope\"}"));

        assertThat(e.getHttpStatus().value()).isEqualTo(status);
        assertThat(e.getMessage()).isEqualTo("Nope");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500, 502, 503, 504})
    void serverErrorsAndTokenRejectionBecome503(int status) {
        CustomException e = BackendErrorMapper.fromResponse(HttpStatusCode.valueOf(status), json("{\"message\":\"x\"}"));

        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getMessage()).isEqualTo("Backend unavailable");
    }

    @Test
    void unusableBodiesFallBackToTheReasonPhrase() {
        assertThat(BackendErrorMapper.message(null, HttpStatus.CONFLICT)).isEqualTo("Conflict");
        assertThat(BackendErrorMapper.message(new byte[0], HttpStatus.CONFLICT)).isEqualTo("Conflict");
        assertThat(BackendErrorMapper.message(json("<html>"), HttpStatus.CONFLICT)).isEqualTo("Conflict");
        assertThat(BackendErrorMapper.message(json("{\"email\":\"invalid\"}"), HttpStatus.BAD_REQUEST))
                .isEqualTo("Bad Request");
        assertThat(BackendErrorMapper.message(json("{\"message\":\" \"}"), HttpStatus.CONFLICT)).isEqualTo("Conflict");
        assertThat(BackendErrorMapper.message(json("{\"message\":7}"), HttpStatus.CONFLICT)).isEqualTo("Conflict");
    }

    @Test
    void nonStandardClientStatusIsKeptAsBadRequest() {
        CustomException e = BackendErrorMapper.fromResponse(HttpStatusCode.valueOf(499), json("{\"message\":\"odd\"}"));

        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getMessage()).isEqualTo("odd");
    }

    @Test
    void unavailableKeepsTheCause() {
        IllegalStateException cause = new IllegalStateException("down");

        assertThat(BackendErrorMapper.unavailable(cause).getCause()).isSameAs(cause);
        assertThat(BackendErrorMapper.unavailable(null).getCause()).isNull();
    }
}
