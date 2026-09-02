package com.liang.gateway.core.internal.application;

import com.liang.gateway.core.GatewayExchange;
import com.liang.gateway.core.GatewayFilter;
import com.liang.gateway.core.PipelineApi;
import com.liang.gateway.core.internal.infrastructure.WebClientProxy;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Service
public class DefaultPipelineApi implements PipelineApi {

    private final List<GatewayFilter> filters;
    private final WebClientProxy proxy;

    public DefaultPipelineApi(List<GatewayFilter> filters) {
        this(filters, null);
    }

    @Autowired
    public DefaultPipelineApi(List<GatewayFilter> filters, WebClientProxy proxy) {
        this.filters = List.copyOf(filters);
        this.proxy = proxy;
    }

    @Override
    public Mono<Void> execute(ServerWebExchange exchange) {
        GatewayExchange gatewayExchange = new GatewayExchange(exchange);
        return DefaultGatewayFilterChain.of(this.filters, this::finish).filter(gatewayExchange);
    }

    private Mono<Void> finish(GatewayExchange exchange) {
        if (exchange.nativeExchange().getResponse().isCommitted()) {
            return Mono.empty();
        }
        if (this.proxy == null || exchange.getUpstream().isEmpty()) {
            return Mono.empty();
        }
        return this.proxy.forward(exchange);
    }
}
