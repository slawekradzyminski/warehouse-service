package com.awesome.warehouse.client;

import java.util.List;

/** Body of {@code GET /internal/v1/jwks.json}: the backend's access-token verification keys. */
public record JwkSet(List<Jwk> keys) {

    public JwkSet {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    /** An RSA public key as a JSON Web Key (RFC 7517); n and e are base64url without padding. */
    public record Jwk(String kty, String kid, String alg, String use, String n, String e) {
    }
}
