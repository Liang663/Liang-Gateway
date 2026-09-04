package com.liang.gateway.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ChatUpstream(String url, Map<String, String> extraHeaders, byte[] body, boolean stream, String apikeyCode) {

    public ChatUpstream {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(apikeyCode, "apikeyCode");
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        body = body == null ? new byte[0] : body;
    }

    @Override
    public String toString() {
        Map<String, String> safe = new LinkedHashMap<>();
        extraHeaders.forEach((name, value) -> {
            if ("Authorization".equalsIgnoreCase(name)) {
                safe.put(name, "Bearer ***");
            } else {
                safe.put(name, value);
            }
        });
        return "ChatUpstream[url=" + url + ", extraHeaders=" + safe + ", bodyBytes=" + body.length + ", stream="
                + stream + ", apikeyCode=" + apikeyCode + "]";
    }
}
