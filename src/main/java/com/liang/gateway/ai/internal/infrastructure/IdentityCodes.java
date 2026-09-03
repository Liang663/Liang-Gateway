package com.liang.gateway.ai.internal.infrastructure;

import java.security.SecureRandom;
import java.util.HexFormat;

public final class IdentityCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private IdentityCodes() {}

    public static String apikeyCode() {
        return "apk_" + hex(16);
    }

    public static String modelCode() {
        return "mdl_" + hex(16);
    }

    public static String callLogCode() {
        return "lcl_" + hex(16);
    }

    private static String hex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
