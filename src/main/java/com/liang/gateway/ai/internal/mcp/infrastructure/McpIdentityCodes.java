package com.liang.gateway.ai.internal.mcp.infrastructure;

import java.security.SecureRandom;
import java.util.HexFormat;

public final class McpIdentityCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private McpIdentityCodes() {}

    public static String serverCode() {
        return "mcs_" + hex(16);
    }

    public static String toolCode() {
        return "mct_" + hex(16);
    }

    private static String hex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
