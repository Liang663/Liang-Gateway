package com.liang.gateway.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record McpUpstream(String url, String httpMethod, Map<String, String> headers, byte[] body, int timeoutMs) {

    public McpUpstream {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(httpMethod, "httpMethod");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    @Override
    public String toString() {
        Map<String, String> safe = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if ("Authorization".equalsIgnoreCase(name)) {
                safe.put(name, "Bearer ***");
            } else {
                safe.put(name, value);
            }
        });
        return "McpUpstream[url=" + url + ", httpMethod=" + httpMethod + ", headers=" + safe + ", bodyBytes="
                + body.length + ", timeoutMs=" + timeoutMs + "]";
    }
}
