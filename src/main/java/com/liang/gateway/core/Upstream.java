package com.liang.gateway.core;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record Upstream(
        URI url,
        Map<String, String> extraHeaders,
        boolean stream,
        String method,
        byte[] body,
        Duration timeout) {

    public Upstream {
        Objects.requireNonNull(url, "url");
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        method = method == null || method.isBlank() ? null : method.trim();
    }

    public Upstream(URI url, Map<String, String> extraHeaders, boolean stream) {
        this(url, extraHeaders, stream, null, null, null);
    }
}
