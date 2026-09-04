package com.liang.gateway.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.ai.internal.application.LlmApikeyAdminService;
import com.liang.gateway.ai.internal.application.LlmModelAdminService;
import com.liang.gateway.ai.internal.infrastructure.jpa.LlmCallLogEntity;
import com.liang.gateway.ai.internal.infrastructure.jpa.LlmCallLogRepository;
import com.liang.gateway.orchestration.ChatTestSupport.Catalog;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
class ChatStreamingHttpTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private TokenAdminService tokenAdminService;

    @Autowired
    private LlmApikeyAdminService apikeyAdminService;

    @Autowired
    private LlmModelAdminService modelAdminService;

    @Autowired
    private LlmCallLogRepository callLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper objectMapper;

    private MockWebServer mockWebServer;

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
    @DisplayName("流式出站 include_usage=true，逐帧到达并记账")
    void streamForcesIncludeUsageAndRecordsUsage() throws InterruptedException {
        Catalog catalog = seed(mockWebServer.url("/").toString());
        String sse =
                """
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":1000000,"completion_tokens":0}}

                data: [DONE]

                """;
        mockWebServer.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .setChunkedBody(sse, 24)
                .throttleBody(24, 30, TimeUnit.MILLISECONDS));

        String inbound =
                """
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"stream":true,"stream_options":{"include_usage":false}}
                """
                        .formatted(catalog.modelName());

        Flux<ServerSentEvent<String>> body = webTestClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(inbound)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody();

        StepVerifier.create(body)
                .assertNext(event -> assertThat(event.data()).contains("hi"))
                .assertNext(event -> assertThat(event.data()).contains("prompt_tokens"))
                .assertNext(event -> assertThat(event.data()).contains("[DONE]"))
                .verifyComplete();

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        JsonNode outbound = objectMapper.readTree(recorded.getBody().readUtf8());
        assertThat(outbound.path("stream").asBoolean()).isTrue();
        assertThat(outbound.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + catalog.secret());

        assertThat(waitForUsage(catalog.tokenCode())).isEqualTo(1L);
        assertThat(usageAmount(catalog.tokenCode())).isEqualTo(200L);
        List<LlmCallLogEntity> logs = callLogRepository.findByLlmApikeyCodeOrderByCreateTimeDesc(catalog.apikeyCode());
        assertThat(logs).isNotEmpty();
        assertThat(logs.getFirst().isSuccess()).isTrue();
        assertThat(logs.getFirst().getFirstTokenMs()).isNotNull();
    }

    @Test
    @DisplayName("流式客户端断开后仍 drain 并记账，金额不是伪造的 0")
    void clientDisconnectStillBillsUsage() throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.post("/chat/completions", (req, res) -> res.header(
                                HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .sendString(Flux.concat(
                                        Flux.just(
                                                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"),
                                        Mono.just(
                                                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1000000,\"completion_tokens\":0}}\n\n")
                                                .delayElement(Duration.ofMillis(250)),
                                        Mono.just("data: [DONE]\n\n"))
                                .doOnComplete(finished::countDown))))
                .bindNow();
        try {
            String baseUrl = "http://127.0.0.1:" + server.port() + "/";
            Catalog catalog = seed(baseUrl);
            String inbound =
                    """
                    {"model":"%s","messages":[{"role":"user","content":"hi"}],"stream":true}
                    """
                            .formatted(catalog.modelName());

            Flux<ServerSentEvent<String>> body = webTestClient
                    .post()
                    .uri("/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(inbound)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .getResponseBody();

            StepVerifier.create(body)
                    .assertNext(event -> assertThat(event.data()).contains("hi"))
                    .thenCancel()
                    .verify(Duration.ofSeconds(2));

            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(waitForUsage(catalog.tokenCode())).isEqualTo(1L);
            Long amount = usageAmount(catalog.tokenCode());
            assertThat(amount).isEqualTo(200L);
            assertThat(amount).isNotZero();
        } finally {
            server.disposeNow();
        }
    }

    private Catalog seed(String baseUrl) {
        return ChatTestSupport.seed(
                userAdminService,
                tokenAdminService,
                apikeyAdminService,
                modelAdminService,
                baseUrl,
                List.of(),
                true,
                ChatTestSupport.generousLimits(),
                60);
    }

    private Long waitForUsage(String tokenCode) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Long count = 0L;
        while (System.nanoTime() < deadline) {
            count = usageCount(tokenCode);
            if (count != null && count > 0) {
                return count;
            }
            Thread.sleep(50);
        }
        return count;
    }

    private Long usageCount(String tokenCode) {
        return jdbcTemplate.queryForObject(
                "select count(*) from usage_record where token_code = ?", Long.class, tokenCode);
    }

    private Long usageAmount(String tokenCode) {
        return jdbcTemplate.queryForObject(
                "select amount_fen from usage_record where token_code = ?", Long.class, tokenCode);
    }
}
