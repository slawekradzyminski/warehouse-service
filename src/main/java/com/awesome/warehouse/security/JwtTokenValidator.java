package com.awesome.warehouse.security;

import com.awesome.warehouse.controller.exception.CustomException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates the backend's RS256 access tokens with the public keys the backend publishes
 * (see {@link BackendKeyLocator}); warehouse-service holds no signing secret. Roles come from
 * the {@code auth} claim, which the backend writes as {@code [{"authority": "ROLE_..."}]}.
 * There is no user lookup: the token is the source of truth.
 */
@Component
public class JwtTokenValidator {

    static final String INVALID_TOKEN = "Invalid or expired token";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtParser parser;

    public JwtTokenValidator(BackendKeyLocator keyLocator) {
        this.parser = Jwts.parser().keyLocator(keyLocator).build();
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
