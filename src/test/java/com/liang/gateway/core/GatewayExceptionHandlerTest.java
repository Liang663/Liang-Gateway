package com.liang.gateway.core;

import com.liang.gateway.core.support.TestPipelineConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestPipelineConfiguration.class, GatewayExceptionHandlerTest.ThrowingFilterConfig.class})
class GatewayExceptionHandlerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("未注册路径是 404 not_found 而不是 500")
    void missingRouteIsNotFound() {
        webTestClient.get()
                .uri("/no-such-route")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("not_found")
                .jsonPath("$.error.message")
                .isEqualTo("Not Found");
    }

    @Test
    @DisplayName("不允许的方法是 405 method_not_allowed 而不是 500")
    void methodNotAllowedIsNotInternalError() {
        webTestClient.post()
                .uri("/health")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isEqualTo(405)
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("method_not_allowed")
                .jsonPath("$.error.message")
                .isEqualTo("Method Not Allowed");
    }

    @Test
    @DisplayName("未捕获异常映射为 500 internal_error 且不泄露堆栈")
    void uncaughtExceptionMappedWithoutStack() {
        webTestClient.get()
                .uri("/__test__/pipeline")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isEqualTo(500)
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("internal_error")
                .jsonPath("$.error.message")
                .isEqualTo("Internal error")
                .consumeWith(result -> {
                    byte[] body = result.getResponseBody();
                    String text = body == null ? "" : new String(body);
                    org.assertj.core.api.Assertions.assertThat(text)
                            .doesNotContain("boom-secret-stack")
                            .doesNotContain("IllegalStateException")
                            .doesNotContain("at com.liang");
                });
    }

    @TestConfiguration
    static class ThrowingFilterConfig {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        GatewayFilter throwingFilter() {
            return (exchange, chain) ->
                    reactor.core.publisher.Mono.error(new IllegalStateException("boom-secret-stack"));
        }
    }
}
