package com.liang.gateway.core;

import java.util.function.Consumer;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public interface ProxyApi {

    /**
     * Writes the upstream response to the inbound exchange. {@code observe} receives
     * each complete frame (SSE events when streaming). When {@code drainAfterCancel}
     * is true, the upstream is read to completion after the client cancels.
     */
    Mono<Void> forward(
            ServerWebExchange exchange, Upstream upstream, Consumer<byte[]> observe, boolean drainAfterCancel);

    /**
     * Returns upstream status and body without writing the inbound response.
     */
    Mono<ProxyResponse> exchange(Upstream upstream);
}
