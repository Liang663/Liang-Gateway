package com.liang.gateway.core;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface GatewayFilterChain {

    Mono<Void> filter(GatewayExchange exchange);
}
