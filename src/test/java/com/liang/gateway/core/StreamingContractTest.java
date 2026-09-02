package com.liang.gateway.core;

import com.liang.gateway.core.support.TestPipelineConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestPipelineConfiguration.class, StreamingContractTest.StreamingFilterConfig.class})
class StreamingContractTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("测试 Filter 发出的三帧 SSE 按帧到达")
    void threeSseFramesArriveSeparately() {
        Flux<ServerSentEvent<String>> body = webTestClient.get()
                .uri("/__test__/pipeline")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody();

        long started = System.nanoTime();
        StepVerifier.create(body)
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.data()).isEqualTo("a"))
                .assertNext(event -> {
                    long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    org.assertj.core.api.Assertions.assertThat(event.data()).isEqualTo("b");
                    org.assertj.core.api.Assertions.assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
                })
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.data()).isEqualTo("c"))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @TestConfiguration
    static class StreamingFilterConfig {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        GatewayFilter threeFrameSseFilter() {
            return (exchange, chain) -> {
                var response = exchange.nativeExchange().getResponse();
                response.setStatusCode(HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
                Flux<DataBuffer> frames = Flux.just("data: a\n\n", "data: b\n\n", "data: c\n\n")
                        .delayElements(Duration.ofMillis(50))
                        .map(chunk -> response.bufferFactory().wrap(chunk.getBytes(StandardCharsets.UTF_8)));
                return response.writeAndFlushWith(frames.map(Mono::just));
            };
        }
    }
}
