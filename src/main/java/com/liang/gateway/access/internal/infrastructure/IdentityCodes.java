package com.liang.gateway.access.internal.infrastructure;

import java.security.SecureRandom;
import java.util.HexFormat;

public final class IdentityCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private IdentityCodes() {}

    public static String userCode() {
        return "usr_" + hex(16);
    }

    public static String tokenCode() {
        return "tok_" + hex(16);
    }

    public static String accessToken() {
        return hex(32);
    }

    public static String usageCode() {
        return "usg_" + hex(16);
    }

    private static String hex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
