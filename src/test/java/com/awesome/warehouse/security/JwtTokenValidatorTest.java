package com.awesome.warehouse.security;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.support.TestTokens;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenValidatorTest {

    private final JwtTokenValidator validator = new JwtTokenValidator(TestTokens.SECRET, false);

    @Test
    void backendTokenBecomesAnAuthenticationWithItsRoles() {
        Authentication auth = validator.authenticate(TestTokens.token("alice", "ROLE_ADMIN", "ROLE_CLIENT"));

        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(auth.getPrincipal()).isEqualTo(new CustomPrincipal("alice", java.util.Set.of("ROLE_ADMIN", "ROLE_CLIENT")));
        assertThat(((CustomPrincipal) auth.getPrincipal()).isAdmin()).isTrue();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN", "ROLE_CLIENT");
    }

    @Test
    void clientIsNotAdmin() {
        CustomPrincipal principal = (CustomPrincipal) validator.authenticate(TestTokens.token("bob", "ROLE_CLIENT"))
                .getPrincipal();

        assertThat(principal.isAdmin()).isFalse();
        assertThat(principal).hasToString("bob");
    }

    @Test
    void expiredTokenIsRejected() {
        assertInvalid(TestTokens.token(TestTokens.SECRET, "alice", -1_000L, "ROLE_ADMIN"));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        assertInvalid(TestTokens.token("another-secret", "alice", 60_000L, "ROLE_ADMIN"));
    }

    @Test
    void tamperedAndMalformedTokensAreRejected() {
        String token = TestTokens.token("alice", "ROLE_CLIENT");
        String forged = TestTokens.token("mallory", "ROLE_ADMIN");
        String spliced = token.substring(0, token.indexOf('.')) + forged.substring(forged.indexOf('.'),
                forged.lastIndexOf('.')) + token.substring(token.lastIndexOf('.'));

        assertInvalid(spliced);
        assertInvalid("not-a-jwt");
        assertInvalid("");
    }

    @Test
    void unsignedTokenIsRejected() {
        String unsigned = Jwts.builder().subject("alice").claim("auth", List.of(Map.of("authority", "ROLE_ADMIN")))
                .compact();

        assertInvalid(unsigned);
    }

    @Test
    void tokenWithoutAuthClaimIsRejected() {
        assertInvalid(signed(Jwts.builder().subject("alice")));
    }

    @Test
    void tokenWithEmptyOrUnknownRolesIsRejected() {
        assertInvalid(signed(Jwts.builder().subject("alice").claim("auth", List.of())));
        assertInvalid(signed(Jwts.builder().subject("alice").claim("auth", List.of(Map.of("authority", "ADMIN")))));
        assertInvalid(signed(Jwts.builder().subject("alice").claim("auth", "ROLE_ADMIN")));
    }

    @Test
    void tokenWithoutSubjectIsRejected() {
        assertInvalid(signed(Jwts.builder().claim("auth", List.of(Map.of("authority", "ROLE_ADMIN")))));
        assertInvalid(signed(Jwts.builder().subject(" ").claim("auth", List.of(Map.of("authority", "ROLE_ADMIN")))));
    }

    @Test
    void plainStringRolesAreAcceptedAndNonRolesIgnored() {
        assertThat(JwtTokenValidator.roles(List.of("ROLE_WAREHOUSE", Map.of("authority", "ROLE_CLIENT"), "admin",
                Map.of("other", "ROLE_ADMIN"), 7))).containsExactly("ROLE_WAREHOUSE", "ROLE_CLIENT");
        assertThat(JwtTokenValidator.roles(null)).isEmpty();
    }

    @Test
    void shortKeysAreExpandedLikeTheBackend() {
        assertThat(JwtTokenValidator.keyBytes("abc", false)).isEqualTo("abcabcabcabcabcabcabcabcabcabcabc"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(JwtTokenValidator.keyBytes("x".repeat(32), false)).hasSize(32);
        assertThat(new JwtTokenValidator("abc", false).authenticate(TestTokens.token("abc", "u", 60_000L, "ROLE_CLIENT"))
                .getName()).isEqualTo("u");
    }

    @Test
    void secureKeyModeRejectsShortOrDevelopmentKeys() {
        assertThatThrownBy(() -> JwtTokenValidator.keyBytes("short", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtTokenValidator.keyBytes("local-development-jwt-key-change-me", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtTokenValidator.keyBytes(" ", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtTokenValidator.keyBytes(null, false)).isInstanceOf(IllegalStateException.class);
        assertThat(JwtTokenValidator.keyBytes("k".repeat(40), true)).hasSize(40);
        assertThat(JwtTokenValidator.keyBytes("k".repeat(32), true)).hasSize(32);
        assertThatThrownBy(() -> JwtTokenValidator.keyBytes("k".repeat(31), true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bearerTokenIsExtractedFromTheHeader() {
        assertThat(JwtTokenValidator.extractToken("Bearer abc.def")).isEqualTo("abc.def");
        assertThat(JwtTokenValidator.extractToken("Basic abc")).isNull();
        assertThat(JwtTokenValidator.extractToken(null)).isNull();
    }

    private static String signed(io.jsonwebtoken.JwtBuilder builder) {
        return builder.expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(TestTokens.expand(TestTokens.SECRET))).compact();
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> validator.authenticate(token))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessage()).isEqualTo("Invalid or expired token");
                });
    }
}
