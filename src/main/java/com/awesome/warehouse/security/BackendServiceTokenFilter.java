package com.awesome.warehouse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Authenticates the backend on the stock ledger API: {@code Authorization: Bearer <token>} must
 * equal {@code BACKEND_SERVICE_TOKEN}. The tokens are compared as SHA-256 digests with
 * {@link MessageDigest#isEqual} (constant time, as the backend compares service tokens). A match
 * authenticates caller {@value #CALLER}; a missing or wrong token answers 401
 * "Invalid service token"; without a configured token the API does not exist (404 "Not found"),
 * the backend's convention for disabled internal routes.
 */
@Slf4j
public class BackendServiceTokenFilter extends OncePerRequestFilter {

    public static final String CALLER = "BACKEND";
    public static final String AUTHORITY = "SERVICE_BACKEND";
    private static final String BEARER_PREFIX = "Bearer ";

    /** SHA-256 of the expected token; empty when no token is configured. */
    private final byte[] expectedDigest;

    public BackendServiceTokenFilter(String expectedToken) {
        super();
        boolean configured = expectedToken != null && !expectedToken.isBlank();
        this.expectedDigest = configured ? digest(expectedToken) : new byte[0];
        if (!configured) {
            log.warn("BACKEND_SERVICE_TOKEN is not set; the stock ledger API answers 404");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (expectedDigest.length == 0) {
            ErrorResponses.send(response, HttpStatus.NOT_FOUND, "Not found");
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean bearer = header != null && header.startsWith(BEARER_PREFIX);
        byte[] presented = digest(bearer ? header.substring(BEARER_PREFIX.length()) : "");
        // Always compare, so a missing token takes as long as a wrong one.
        if (!MessageDigest.isEqual(expectedDigest, presented) || !bearer) {
            ErrorResponses.send(response, HttpStatus.UNAUTHORIZED, "Invalid service token");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                CALLER, null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        filterChain.doFilter(request, response);
    }

    static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
