package com.liang.gateway.ai.internal.mcp.domain;

final class HeaderFieldSafety {

    private HeaderFieldSafety() {}

    static boolean isUnsafe(String name, String value) {
        return containsForbidden(name) || containsForbidden(value);
    }

    static boolean containsForbidden(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }
}
