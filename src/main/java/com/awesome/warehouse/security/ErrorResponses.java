package com.awesome.warehouse.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/** Writes the backend-compatible {@code {"message": ...}} error body outside Spring MVC. */
final class ErrorResponses {

    private ErrorResponses() {
    }

    static void send(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(status.value());
        response.getWriter().write("{\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}
