package com.awesome.warehouse.support;

import com.awesome.warehouse.client.JwkSet;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/** Issues RS256 tokens exactly like the backend's {@code JwtTokenProvider.createToken}. */
public final class TestTokens {

    public static final String KID = "test-key";
    public static final KeyPair KEYS = generate();

    private TestTokens() {
    }

    public static String token(String username, String... roles) {
        return token(KEYS.getPrivate(), KID, username, 3_600_000L, roles);
    }

    public static String token(PrivateKey key, String kid, String username, long validityMillis, String... roles) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        Claims claims = Jwts.claims().subject(username).add("auth", authorities).build();
        Date now = new Date();
        return Jwts.builder()
                .header().keyId(kid).and()
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validityMillis))
                .signWith(key, Jwts.SIG.RS256)
                .compact();
    }

    /** A validator that resolves keys from a backend publishing {@link #jwks()}. */
    public static com.awesome.warehouse.security.JwtTokenValidator validator() {
        return new com.awesome.warehouse.security.JwtTokenValidator(
                new com.awesome.warehouse.security.BackendKeyLocator(new FakeBackend(), java.time.Duration.ZERO));
    }

    /** The JWKS the backend would publish for {@link #KEYS}. */
    public static JwkSet jwks() {
        return new JwkSet(List.of(jwk(KID, (RSAPublicKey) KEYS.getPublic())));
    }

    public static JwkSet.Jwk jwk(String kid, RSAPublicKey key) {
        return new JwkSet.Jwk("RSA", kid, "RS256", "sig", base64Url(key.getModulus()), base64Url(key.getPublicExponent()));
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
