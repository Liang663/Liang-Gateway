package com.liang.gateway.core;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Runs the gateway filter chain. If the response is still uncommitted and an
 * {@link Upstream} is present on the exchange, core forwards the request.
 */
public interface PipelineApi {

    Mono<Void> execute(ServerWebExchange exchange);
}
