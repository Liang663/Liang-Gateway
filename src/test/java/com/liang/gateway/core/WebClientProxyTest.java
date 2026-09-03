package com.liang.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.core.support.TestPipelineConfiguration;
import com.liang.gateway.support.TestTokens;
import java.io.IOException;
import java.time.Duration;
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
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@Import(TestPipelineConfiguration.class)
class WebClientProxyTest {

    @Autowired
    private WebTestClient webTestClient;

    private MockWebServer mockWebServer;

    @BeforeEach
    void authorize() {
        webTestClient = webTestClient.mutate()
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
    @DisplayName("非流式透传 status 与 body，且不转发入站 Authorization")
    void nonStreamingPassThroughDoesNotForwardInboundAuthorization() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"ok\":true}"));

        webTestClient
                .post()
                .uri("/__test__/pipeline")
                .header("X-Upstream-Url", upstreamUrl())
                .header("X-Upstream-Stream", "false")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"hello\":\"world\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .isEqualTo("{\"ok\":true}");

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo("/v1/upstream");
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(recorded.getHeader(HttpHeaders.CONTENT_TYPE)).contains("application/json");
        assertThat(recorded.getBody().readUtf8()).isEqualTo("{\"hello\":\"world\"}");
    }

    @Test
    @DisplayName("上游响应头透传，hop-by-hop 头不抄")
    void responseHeadersAreForwardedExceptHopByHop() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setHeader("X-Request-Id", "abc-123")
                .setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .setHeader(HttpHeaders.CONNECTION, "keep-alive")
                .setBody("{}"));

        webTestClient
                .post()
                .uri("/__test__/pipeline")
                .header("X-Upstream-Url", upstreamUrl())
                .header("X-Upstream-Stream", "false")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Request-Id", "abc-123")
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader()
                .doesNotExist(HttpHeaders.CONNECTION);
    }

    @Test
    @DisplayName("只附加 Upstream.extraHeaders 中的 Authorization")
    void extraHeadersAreForwardedInsteadOfInboundAuthorization() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(204));

        webTestClient
                .post()
                .uri("/__test__/pipeline")
                .header("X-Upstream-Url", upstreamUrl())
                .header("X-Upstream-Stream", "false")
                .header("X-Upstream-Extra-Authorization", "Bearer upstream-key")
                .exchange()
                .expectStatus()
                .isNoContent();

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer upstream-key");
    }

    @Test
    @DisplayName("SSE 多帧按帧透传")
    void sseFramesAreForwardedSeparately() {
        mockWebServer.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .setChunkedBody("data: a\n\ndata: b\n\ndata: c\n\n", 8)
                .throttleBody(8, 50, TimeUnit.MILLISECONDS));

        Flux<ServerSentEvent<String>> body = webTestClient
                .get()
                .uri("/__test__/pipeline")
                .header("X-Upstream-Url", upstreamUrl())
                .header("X-Upstream-Stream", "true")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody();

        long started = System.nanoTime();
        StepVerifier.create(body)
                .assertNext(event -> assertThat(event.data()).isEqualTo("a"))
                .assertNext(event -> {
                    long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    assertThat(event.data()).isEqualTo("b");
                    assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
                })
                .assertNext(event -> assertThat(event.data()).isEqualTo("c"))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("调用方取消后上游订阅停止，不再写出后续帧")
    void clientCancelStopsReadingUpstream() throws InterruptedException {
        AtomicInteger framesEmitted = new AtomicInteger();
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        CountDownLatch cancelled = new CountDownLatch(1);

        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get("/sse", (req, res) -> res.header(
                                HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .sendString(Flux.concat(
                                        Flux.just("data: 1\n\n"),
                                        Flux.interval(Duration.ofMillis(150))
                                                .map(i -> "data: " + (i + 2) + "\n\n"))
                                .doOnNext(frame -> framesEmitted.incrementAndGet())
                                .doOnCancel(() -> {
                                    upstreamCancelled.set(true);
                                    cancelled.countDown();
                                }))))
                .bindNow();
        try {
            String url = "http://127.0.0.1:" + server.port() + "/sse";
            Flux<ServerSentEvent<String>> body = webTestClient
                    .get()
                    .uri("/__test__/pipeline")
                    .header("X-Upstream-Url", url)
                    .header("X-Upstream-Stream", "true")
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

            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(upstreamCancelled).isTrue();
            int emittedAtCancel = framesEmitted.get();
            assertThat(emittedAtCancel).isEqualTo(1);
            Thread.sleep(400);
            assertThat(framesEmitted.get()).isEqualTo(emittedAtCancel);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    @DisplayName("上游连不上映射 502 bad_gateway")
    void upstreamConnectFailureMappedToBadGateway() {
        webTestClient
                .get()
                .uri("/__test__/pipeline")
                .header("X-Upstream-Url", "http://127.0.0.1:1")
                .header("X-Upstream-Stream", "false")
                .exchange()
                .expectStatus()
                .isEqualTo(502)
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("bad_gateway")
                .jsonPath("$.error.message")
                .isEqualTo("Bad gateway");
    }

    private String upstreamUrl() {
        return mockWebServer.url("/v1/upstream").toString();
    }
}
