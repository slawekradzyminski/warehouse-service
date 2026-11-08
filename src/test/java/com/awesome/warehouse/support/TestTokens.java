package com.awesome.warehouse.support;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/** Issues tokens exactly like the backend's {@code JwtTokenProvider.createToken}. */
public final class TestTokens {

    public static final String SECRET = "local-development-jwt-key-change-me";

    private TestTokens() {
    }

    public static String token(String username, String... roles) {
        return token(SECRET, username, 3_600_000L, roles);
    }

    public static String token(String secret, String username, long validityMillis, String... roles) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        Claims claims = Jwts.claims().subject(username).add("auth", authorities).build();
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validityMillis))
                .signWith(Keys.hmacShaKeyFor(expand(secret)))
                .compact();
    }

    /** Backend short-key expansion: repeat the key until it has at least 32 bytes. */
    public static byte[] expand(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            bytes = secret.repeat(Math.ceilDiv(32, bytes.length)).getBytes(StandardCharsets.UTF_8);
        }
        return bytes;
    }
}
