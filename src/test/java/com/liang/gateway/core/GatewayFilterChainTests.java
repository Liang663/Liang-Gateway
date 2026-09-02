package com.liang.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.core.internal.application.DefaultPipelineApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayFilterChainTests {

    @Test
    @DisplayName("过滤器按声明顺序执行")
    void filtersRunInDeclaredOrder() {
        List<String> observed = new ArrayList<>();
        GatewayFilter first = (exchange, chain) -> {
            observed.add("first");
            return chain.filter(exchange);
        };
        GatewayFilter second = (exchange, chain) -> {
            observed.add("second");
            return chain.filter(exchange);
        };
        GatewayFilter third = (exchange, chain) -> {
            observed.add("third");
            return chain.filter(exchange);
        };

        DefaultPipelineApi pipeline = new DefaultPipelineApi(List.of(first, second, third));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build());

        StepVerifier.create(pipeline.execute(exchange)).verifyComplete();
        assertThat(observed).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("短路后不再进入下一环")
    void shortCircuitSkipsRemainingFilters() {
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        GatewayFilter first = (exchange, chain) -> Mono.empty();
        GatewayFilter second = (exchange, chain) -> {
            secondCalled.set(true);
            return chain.filter(exchange);
        };

        DefaultPipelineApi pipeline = new DefaultPipelineApi(List.of(first, second));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build());

        StepVerifier.create(pipeline.execute(exchange)).verifyComplete();
        assertThat(secondCalled).isFalse();
    }
}
