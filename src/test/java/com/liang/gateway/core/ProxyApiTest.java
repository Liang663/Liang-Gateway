package com.liang.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.core.support.TestProxyConfiguration;
import com.liang.gateway.support.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@Import(TestProxyConfiguration.class)
class ProxyApiTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ProxyApi proxyApi;

    private MockWebServer mockWebServer;

    @BeforeEach
    void authorize() {
        webTestClient = webTestClient
                .mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.BEARER)
                .build();
    }

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("Upstream.body 覆盖入站 body，method 用指定 POST")
    void overrideBodyIsSentInsteadOfInbound() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient
                .post()
                .uri("/__test__/proxy/forward")
                .header("X-Upstream-Url", upstreamUrl())
                .header("X-Upstream-Stream", "false")
                .header("X-Upstream-Method", "POST")
                .header("X-Upstream-Override-Body", "{\"from\":\"override\"}")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"from\":\"inbound\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("ok");

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getBody().readUtf8()).isEqualTo("{\"from\":\"override\"}");
    }

    @Test
    @DisplayName("exchange 捕获状态和正文，且不写入站响应")
    void exchangeCapturesStatusAndBody() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"error\":\"nope\"}"));

        Upstream upstream = new Upstream(
                URI.create(upstreamUrl()),
                Map.of(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
                false,
                "POST",
                "{\"q\":1}".getBytes(StandardCharsets.UTF_8),
                null);

        StepVerifier.create(proxyApi.exchange(upstream))
                .assertNext(response -> {
                    assertThat(response.status().value()).isEqualTo(404);
                    assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"nope\"}");
                    assertThat(response.contentType()).isEqualTo(MediaType.APPLICATION_JSON);
                })
                .verifyComplete();

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getBody().readUtf8()).isEqualTo("{\"q\":1}");
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    @DisplayName("drain 模式下客户端取消后仍读完上游")
    void drainAfterCancelKeepsReadingUpstream() throws InterruptedException {
        AtomicInteger framesEmitted = new AtomicInteger();
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        CountDownLatch finished = new CountDownLatch(1);

        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get("/sse", (req, res) -> res.header(
                                HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .sendString(Flux.concat(
                                        Flux.just("data: 1\n\n"),
                                        Mono.just("data: 2\n\n").delayElement(Duration.ofMillis(200)),
                                        Mono.just("data: 3\n\n").delayElement(Duration.ofMillis(200)))
                                .doOnNext(frame -> framesEmitted.incrementAndGet())
                                .doOnCancel(() -> upstreamCancelled.set(true))
                                .doOnComplete(finished::countDown))))
                .bindNow();
        try {
            String url = "http://127.0.0.1:" + server.port() + "/sse";
            Flux<ServerSentEvent<String>> body = webTestClient
                    .get()
                    .uri("/__test__/proxy/forward")
                    .header("X-Upstream-Url", url)
                    .header("X-Upstream-Stream", "true")
                    .header("X-Drain-After-Cancel", "true")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .getResponseBody();

            StepVerifier.create(body)
                    .assertNext(event -> assertThat(event.data()).isEqualTo("1"))
                    .thenCancel()
                    .verify(Duration.ofSeconds(2));

            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(upstreamCancelled).isFalse();
            assertThat(framesEmitted.get()).isEqualTo(3);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    @DisplayName("exchange 入站 Authorization 不会出现在出站")
    void exchangeDoesNotSendInboundAuthorization() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(204));
        Upstream upstream = new Upstream(
                URI.create(upstreamUrl()),
                Map.of(HttpHeaders.AUTHORIZATION, "Bearer model-key"),
                false,
                "POST",
                new byte[0],
                null);
        StepVerifier.create(proxyApi.exchange(upstream)).expectNextCount(1).verifyComplete();
        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer model-key");
    }

    @Test
    @DisplayName("exchange 超时映射 ProxyFailureException 504")
    void exchangeTimeoutIsGatewayTimeout() {
        mockWebServer.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("late"));
        Upstream upstream = new Upstream(
                URI.create(mockWebServer.url("/slow").toString()),
                Map.of(),
                false,
                "GET",
                null,
                Duration.ofMillis(200));
        StepVerifier.create(proxyApi.exchange(upstream))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProxyFailureException.class);
                    assertThat(((ProxyFailureException) error).status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                })
                .verify(Duration.ofSeconds(5));
    }

    private String upstreamUrl() {
        return mockWebServer.url("/v1/upstream").toString();
    }
}
