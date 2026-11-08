package com.awesome.warehouse.security;

import java.util.Set;

/** Caller identity taken from the backend-issued access token. */
public record CustomPrincipal(String username, Set<String> roles) {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    public CustomPrincipal {
        roles = Set.copyOf(roles);
    }

    public boolean isAdmin() {
        return roles.contains(ROLE_ADMIN);
    }

    @Override
    public String toString() {
        return username;
    }
}
