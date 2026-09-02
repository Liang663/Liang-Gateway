package com.liang.gateway.core;

import java.util.Objects;
import java.util.Optional;
import org.springframework.web.server.ServerWebExchange;

public final class GatewayExchange {

    public static final String UPSTREAM_ATTRIBUTE = GatewayExchange.class.getName() + ".upstream";

    private final ServerWebExchange delegate;

    public GatewayExchange(ServerWebExchange delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public ServerWebExchange nativeExchange() {
        return delegate;
    }

    public void setUpstream(Upstream upstream) {
        delegate.getAttributes().put(UPSTREAM_ATTRIBUTE, Objects.requireNonNull(upstream, "upstream"));
    }

    public Optional<Upstream> getUpstream() {
        Object value = delegate.getAttribute(UPSTREAM_ATTRIBUTE);
        if (value instanceof Upstream upstream) {
            return Optional.of(upstream);
        }
        return Optional.empty();
    }
}
