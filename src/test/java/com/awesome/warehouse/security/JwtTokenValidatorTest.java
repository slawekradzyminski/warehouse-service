package com.awesome.warehouse.security;

import com.awesome.warehouse.client.JwkSet;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.support.FakeBackend;
import com.awesome.warehouse.support.TestTokens;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenValidatorTest {

    private final JwtTokenValidator validator = TestTokens.validator();

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
        assertInvalid(TestTokens.token(TestTokens.KEYS.getPrivate(), TestTokens.KID, "alice", -1_000L, "ROLE_ADMIN"));
    }

    @Test
    void tokenSignedWithAnotherKeyUnderTheSameKidIsRejected() {
        KeyPair other = TestTokens.generate();

        assertInvalid(TestTokens.token(other.getPrivate(), TestTokens.KID, "alice", 60_000L, "ROLE_ADMIN"));
    }

    @Test
    void tokenWithAnUnknownOrMissingKidIsRejected() {
        assertInvalid(TestTokens.token(TestTokens.KEYS.getPrivate(), "unknown", "alice", 60_000L, "ROLE_ADMIN"));
        assertInvalid(Jwts.builder().subject("alice").claim("auth", List.of(Map.of("authority", "ROLE_ADMIN")))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(TestTokens.KEYS.getPrivate(), Jwts.SIG.RS256).compact());
    }

    @Test
    void hmacTokenSignedWithThePublicKeyIsRejected() {
        byte[] publicKey = TestTokens.KEYS.getPublic().getEncoded();
        String confused = Jwts.builder().header().keyId(TestTokens.KID).and()
                .subject("mallory").claim("auth", List.of(Map.of("authority", "ROLE_ADMIN")))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(publicKey), Jwts.SIG.HS256).compact();

        assertInvalid(confused);
    }

    @Test
    void aRotatedBackendKeyIsFetchedOnFirstUse() {
        KeyPair rotated = TestTokens.generate();
        FakeBackend backend = new FakeBackend() {
            private int calls;

            @Override
            public JwkSet jwks() {
                calls++;
                return calls == 1 ? TestTokens.jwks()
                        : new JwkSet(List.of(TestTokens.jwk("rotated", (RSAPublicKey) rotated.getPublic())));
            }
        };
        JwtTokenValidator rotating = new JwtTokenValidator(new BackendKeyLocator(backend, java.time.Duration.ZERO));

        assertThat(rotating.authenticate(TestTokens.token("alice", "ROLE_CLIENT")).getName()).isEqualTo("alice");
        assertThat(rotating.authenticate(TestTokens.token(rotated.getPrivate(), "rotated", "bob", 60_000L, "ROLE_CLIENT"))
                .getName()).isEqualTo("bob");
    }

    @Test
    void unknownKidsDoNotRefetchMoreOftenThanTheRefreshInterval() {
        int[] calls = {0};
        FakeBackend backend = new FakeBackend() {
            @Override
            public JwkSet jwks() {
                calls[0]++;
                return TestTokens.jwks();
            }
        };
        JwtTokenValidator throttled = new JwtTokenValidator(new BackendKeyLocator(backend, java.time.Duration.ofHours(1)));

        throttled.authenticate(TestTokens.token("alice", "ROLE_CLIENT"));
        for (int i = 0; i < 5; i++) {
            String forged = TestTokens.token(TestTokens.KEYS.getPrivate(), "kid-" + i, "alice", 60_000L, "ROLE_CLIENT");
            assertThatThrownBy(() -> throttled.authenticate(forged)).isInstanceOf(CustomException.class);
        }

        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void concurrentFirstRequestsWaitForTheOneKeyFetchInsteadOfBeingRejected() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FakeBackend backend = new FakeBackend() {
            @Override
            public JwkSet jwks() {
                calls.incrementAndGet();
                return super.jwks();
            }
        };
        backend.setLatency(java.time.Duration.ofMillis(100));
        JwtTokenValidator cold = new JwtTokenValidator(new BackendKeyLocator(backend, java.time.Duration.ofHours(1)));
        String token = TestTokens.token("alice", "ROLE_CLIENT");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<String>> names = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                names.add(pool.submit(() -> {
                    start.await();
                    return cold.authenticate(token).getName();
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<String> name : names) {
                assertThat(name.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("alice");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void anUnreachableBackendIsReportedAsUnavailable() {
        FakeBackend backend = new FakeBackend();
        backend.setAvailable(false);
        JwtTokenValidator offline = new JwtTokenValidator(new BackendKeyLocator(backend, java.time.Duration.ZERO));

        assertThatThrownBy(() -> offline.authenticate(TestTokens.token("alice", "ROLE_CLIENT")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
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
    void bearerTokenIsExtractedFromTheHeader() {
        assertThat(JwtTokenValidator.extractToken("Bearer abc.def")).isEqualTo("abc.def");
        assertThat(JwtTokenValidator.extractToken("Basic abc")).isNull();
        assertThat(JwtTokenValidator.extractToken(null)).isNull();
    }

    private static String signed(io.jsonwebtoken.JwtBuilder builder) {
        return builder.header().keyId(TestTokens.KID).and()
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(TestTokens.KEYS.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> validator.authenticate(token))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessage()).isEqualTo("Invalid or expired token");
                });
    }
}
