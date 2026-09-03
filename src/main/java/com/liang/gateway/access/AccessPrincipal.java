package com.liang.gateway.access;

import java.security.Principal;
import java.util.Objects;

public final class AccessPrincipal implements Principal {

    private final String userCode;
    private final String tokenCode;

    public AccessPrincipal(String userCode, String tokenCode) {
        this.userCode = Objects.requireNonNull(userCode, "userCode");
        this.tokenCode = Objects.requireNonNull(tokenCode, "tokenCode");
    }

    public String userCode() {
        return userCode;
    }

    public String tokenCode() {
        return tokenCode;
    }

    @Override
    public String getName() {
        return tokenCode;
    }
}
