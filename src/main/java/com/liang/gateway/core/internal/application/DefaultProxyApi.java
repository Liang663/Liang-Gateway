package com.liang.gateway.core.internal.application;

import com.liang.gateway.core.ProxyApi;
import com.liang.gateway.core.ProxyResponse;
import com.liang.gateway.core.Upstream;
import com.liang.gateway.core.internal.infrastructure.WebClientProxy;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Service
public class DefaultProxyApi implements ProxyApi {

    private final WebClientProxy proxy;

    public DefaultProxyApi(WebClientProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public Mono<Void> forward(
            ServerWebExchange exchange, Upstream upstream, Consumer<byte[]> observe, boolean drainAfterCancel) {
        return this.proxy.forward(exchange, upstream, observe, drainAfterCancel);
    }

    @Override
    public Mono<ProxyResponse> exchange(Upstream upstream) {
        return this.proxy.exchange(upstream);
    }
}
