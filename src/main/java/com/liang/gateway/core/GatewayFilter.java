package com.liang.gateway.core;

import reactor.core.publisher.Mono;

/**
 * Reactive filter. Call {@code chain.filter(exchange)} to continue; omit it to short-circuit.
 */
public interface GatewayFilter {

    Mono<Void> filter(GatewayExchange exchange, GatewayFilterChain chain);
}
