package com.awesome.warehouse.security;

import com.awesome.warehouse.controller.exception.CustomException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates the backend's HMAC access tokens. The signing key is derived from
 * {@code security.jwt.token.secret-key} exactly like the backend's
 * {@code JwtTokenProvider.init()}; roles come from the {@code auth} claim,
 * which the backend writes as {@code [{"authority": "ROLE_..."}]}. There is no
 * user lookup: the token is the source of truth.
 */
@Component
public class JwtTokenValidator {

    static final String INVALID_TOKEN = "Invalid or expired token";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final int MINIMUM_SECURE_KEY_BYTES = 32;
    private static final Set<String> INSECURE_DEVELOPMENT_KEYS = Set.of(
            "secret-key",
            "test-key",
            "local-development-jwt-key-change-me"
    );

    private final JwtParser parser;

    public JwtTokenValidator(
            @Value("${security.jwt.token.secret-key}") String secretKey,
            @Value("${security.jwt.token.require-secure-key:false}") boolean requireSecureKey) {
        this.parser = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(keyBytes(secretKey, requireSecureKey))).build();
    }

    static byte[] keyBytes(String secretKey, boolean requireSecureKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("JWT signing key must be configured");
        }
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (requireSecureKey
                && (keyBytes.length < MINIMUM_SECURE_KEY_BYTES || INSECURE_DEVELOPMENT_KEYS.contains(secretKey))) {
            throw new IllegalStateException("JWT signing key must contain at least 32 random bytes");
        }
        if (keyBytes.length < MINIMUM_SECURE_KEY_BYTES) {
            int repetitions = Math.ceilDiv(MINIMUM_SECURE_KEY_BYTES, keyBytes.length);
            keyBytes = secretKey.repeat(repetitions).getBytes(StandardCharsets.UTF_8);
        }
        return keyBytes;
    }

    /** Returns the raw token from an {@code Authorization: Bearer ...} header, or null. */
    public static String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorizationHeader.substring(BEARER_PREFIX.length());
    }

    /**
     * Verifies signature and expiry and builds the authentication.
     *
     * @throws CustomException 401 when the token is invalid, expired, or has no subject or roles
     */
    public Authentication authenticate(String token) {
        Claims claims;
        try {
            claims = parser.parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw invalid(e);
        }
        String username = claims.getSubject();
        if (username == null || username.isBlank()) {
            throw invalid(null);
        }
        Set<String> roles = roles(claims.get("auth"));
        if (roles.isEmpty()) {
            throw invalid(null);
        }
        CustomPrincipal principal = new CustomPrincipal(username, roles);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, roles.stream().map(SimpleGrantedAuthority::new).toList());
    }

    static Set<String> roles(Object authClaim) {
        Set<String> roles = new LinkedHashSet<>();
        if (authClaim instanceof Collection<?> entries) {
            for (Object entry : entries) {
                Object role = entry instanceof Map<?, ?> map ? map.get("authority") : entry;
                if (role instanceof String name && name.startsWith(ROLE_PREFIX)) {
                    roles.add(name);
                }
            }
        }
        return roles;
    }

    private static CustomException invalid(Throwable cause) {
        return new CustomException(INVALID_TOKEN, HttpStatus.UNAUTHORIZED, cause);
    }
}
