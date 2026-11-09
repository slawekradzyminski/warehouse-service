package com.awesome.warehouse.security;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.JwkSet;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.InvalidKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the RS256 verification key of a backend access token by its {@code kid} from the
 * backend's JWKS ({@code /internal/v1/jwks.json}). Keys are cached; an unknown {@code kid}
 * (the backend rotated its key, or restarted with an ephemeral one) triggers a refetch, at
 * most once per {@code security.jwt.jwks-refresh-interval}, so forged kids cannot flood the
 * backend. Only RS256 is accepted, which also rules out HMAC key-confusion tokens.
 */
@Slf4j
@Component
public class BackendKeyLocator extends LocatorAdapter<Key> {

    static final String ALGORITHM = "RS256";

    private final BackendClient backendClient;
    private final Duration refreshInterval;
    private final Clock clock;
    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();
    private volatile Instant lastRefresh = Instant.EPOCH;

    @Autowired
    public BackendKeyLocator(BackendClient backendClient,
                             @Value("${security.jwt.jwks-refresh-interval:10s}") Duration refreshInterval) {
        this(backendClient, refreshInterval, Clock.systemUTC());
    }

    BackendKeyLocator(BackendClient backendClient, Duration refreshInterval, Clock clock) {
        super();
        this.backendClient = backendClient;
        this.refreshInterval = refreshInterval;
        this.clock = clock;
    }

    @Override
    protected Key locate(JwsHeader header) {
        if (!ALGORITHM.equals(header.getAlgorithm())) {
            throw new InvalidKeyException("Only RS256 access tokens are accepted");
        }
        String kid = header.getKeyId();
        if (kid == null || kid.isBlank()) {
            throw new InvalidKeyException("Access token has no key id");
        }
        PublicKey key = keys.get(kid);
        if (key == null && refreshAllowed()) {
            refresh();
            key = keys.get(kid);
        }
        if (key == null) {
            throw new InvalidKeyException("Unknown key id");
        }
        return key;
    }

    private synchronized boolean refreshAllowed() {
        Instant now = clock.instant();
        if (now.isBefore(lastRefresh.plus(refreshInterval))) {
            return false;
        }
        lastRefresh = now;
        return true;
    }

    /** Replaces the cache with the backend's current keys; a backend outage propagates as 503. */
    private void refresh() {
        JwkSet set = backendClient.jwks();
        Map<String, PublicKey> fresh = new ConcurrentHashMap<>();
        for (JwkSet.Jwk jwk : set.keys()) {
            if ("RSA".equals(jwk.kty()) && jwk.kid() != null && (jwk.alg() == null || ALGORITHM.equals(jwk.alg()))) {
                fresh.put(jwk.kid(), rsaKey(jwk));
            }
        }
        keys.clear();
        keys.putAll(fresh);
        log.info("Loaded {} access-token verification key(s) from the backend", fresh.size());
    }

    static PublicKey rsaKey(JwkSet.Jwk jwk) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new BigInteger(1, Base64.getUrlDecoder().decode(jwk.n())),
                    new BigInteger(1, Base64.getUrlDecoder().decode(jwk.e()))));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new InvalidKeyException("Backend published an invalid RSA key " + jwk.kid(), e);
        }
    }
}
