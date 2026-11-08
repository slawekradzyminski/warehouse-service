package com.awesome.warehouse.client;

import com.awesome.warehouse.controller.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translates backend internal-API failures into errors for the warehouse
 * caller: a 4xx keeps its status and the backend's {@code message}; a 5xx or a
 * rejected service token means the backend cannot serve warehouse requests and
 * becomes 503 "Backend unavailable".
 */
@Slf4j
public final class BackendErrorMapper {

    public static final String BACKEND_UNAVAILABLE = "Backend unavailable";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BackendErrorMapper() {
    }

    public static CustomException fromResponse(HttpStatusCode status, byte[] body) {
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            // The caller's JWT is not forwarded, so a 401 means our service token was rejected.
            log.error("Backend rejected the warehouse service token; check WAREHOUSE_SERVICE_TOKEN");
            return unavailable(null);
        }
        if (!status.is4xxClientError()) {
            log.error("Backend internal API failed with status {}", status.value());
            return unavailable(null);
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        HttpStatus httpStatus = resolved == null ? HttpStatus.BAD_REQUEST : resolved;
        return new CustomException(message(body, httpStatus), httpStatus);
    }

    public static CustomException unavailable(Throwable cause) {
        if (cause != null) {
            log.error("Backend internal API is unreachable: {}", cause.getMessage());
        }
        return new CustomException(BACKEND_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, cause);
    }

    static String message(byte[] body, HttpStatus status) {
        if (body == null || body.length == 0) {
            return status.getReasonPhrase();
        }
        try {
            JsonNode message = JSON.readTree(body).get("message");
            if (message != null && message.isString() && !message.asString().isBlank()) {
                return message.asString();
            }
        } catch (JacksonException e) {
            log.debug("Backend error body is not JSON", e);
        }
        return status.getReasonPhrase();
    }
}
