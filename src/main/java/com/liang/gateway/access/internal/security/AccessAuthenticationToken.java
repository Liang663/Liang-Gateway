package com.liang.gateway.access.internal.security;

import com.liang.gateway.access.AccessPrincipal;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

final class AccessAuthenticationToken extends AbstractAuthenticationToken {

    private final String credentials;
    private final AccessPrincipal principal;

    static AccessAuthenticationToken unauthenticated(String rawToken) {
        return new AccessAuthenticationToken(rawToken, null, false);
    }

    static AccessAuthenticationToken authenticated(AccessPrincipal principal) {
        return new AccessAuthenticationToken("", principal, true);
    }

    private AccessAuthenticationToken(String credentials, AccessPrincipal principal, boolean authenticated) {
        super(List.of());
        this.credentials = credentials;
        this.principal = principal;
        setAuthenticated(authenticated);
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public AccessPrincipal getPrincipal() {
        return principal;
    }
}
