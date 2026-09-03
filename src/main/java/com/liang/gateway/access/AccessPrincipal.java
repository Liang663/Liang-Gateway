package com.liang.gateway.access;

import java.security.Principal;
import java.util.Objects;

public final class AccessPrincipal implements Principal {

    private final String userCode;
    private final String tokenCode;
    private final String apikeyCode;

    public AccessPrincipal(String userCode, String tokenCode, String apikeyCode) {
        this.userCode = Objects.requireNonNull(userCode, "userCode");
        this.tokenCode = Objects.requireNonNull(tokenCode, "tokenCode");
        this.apikeyCode = Objects.requireNonNull(apikeyCode, "apikeyCode");
    }

    public String userCode() {
        return userCode;
    }

    public String tokenCode() {
        return tokenCode;
    }

    public String apikeyCode() {
        return apikeyCode;
    }

    @Override
    public String getName() {
        return tokenCode;
    }
}
