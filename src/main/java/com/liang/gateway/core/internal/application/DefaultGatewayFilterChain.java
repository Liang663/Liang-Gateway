package com.liang.gateway.core.internal.application;

import com.liang.gateway.core.GatewayFilter;
import com.liang.gateway.core.GatewayFilterChain;
import com.liang.gateway.core.GatewayExchange;
import java.util.List;
import reactor.core.publisher.Mono;

final class DefaultGatewayFilterChain implements GatewayFilterChain {

    private final List<GatewayFilter> filters;
    private final int index;
    private final GatewayFilterChain terminal;

    private DefaultGatewayFilterChain(List<GatewayFilter> filters, int index, GatewayFilterChain terminal) {
        this.filters = filters;
        this.index = index;
        this.terminal = terminal;
    }

    static DefaultGatewayFilterChain of(List<GatewayFilter> filters, GatewayFilterChain terminal) {
        return new DefaultGatewayFilterChain(List.copyOf(filters), 0, terminal);
    }

    @Override
    public Mono<Void> filter(GatewayExchange exchange) {
        if (this.index < this.filters.size()) {
            GatewayFilter filter = this.filters.get(this.index);
            DefaultGatewayFilterChain next =
                    new DefaultGatewayFilterChain(this.filters, this.index + 1, this.terminal);
            return filter.filter(exchange, next);
        }
        return this.terminal.filter(exchange);
    }
}
