package com.liang.gateway.core;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

public record Upstream(URI url, Map<String, String> extraHeaders, boolean stream) {

    public Upstream {
        Objects.requireNonNull(url, "url");
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }
}
