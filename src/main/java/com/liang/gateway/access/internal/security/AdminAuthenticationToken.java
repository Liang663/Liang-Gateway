package com.liang.gateway.access.internal.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

final class AdminAuthenticationToken extends AbstractAuthenticationToken {

    private final String credentials;

    static AdminAuthenticationToken unauthenticated(String rawToken) {
        return new AdminAuthenticationToken(rawToken, false);
    }

    static AdminAuthenticationToken authenticated() {
        return new AdminAuthenticationToken("", true);
    }

    private AdminAuthenticationToken(String credentials, boolean authenticated) {
        super(List.of());
        this.credentials = credentials;
        setAuthenticated(authenticated);
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public Object getPrincipal() {
        return "admin";
    }
}
