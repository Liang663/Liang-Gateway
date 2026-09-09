package com.liang.gateway.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.AccessApi;
import com.liang.gateway.access.UsageMeta;
import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.UsageLimitInput;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.ai.internal.application.LlmApikeyAdminService;
import com.liang.gateway.ai.internal.application.LlmModelAdminService;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmCallLogEntity;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmCallLogRepository;
import com.liang.gateway.orchestration.ChatTestSupport.Catalog;
import java.io.IOException;
import java.util.List;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
class ChatCompletionsHttpTest {

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
    private AccessApi accessApi;

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
    @DisplayName("非流式有 usage 才记账，入站 Authorization 不到模型上游")
    void nonStreamSuccessRecordsUsageAndHidesInboundAuthorization() throws InterruptedException {
        Catalog catalog = seed();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(
                        """
                        {"id":"c1","choices":[{"message":{"content":"hi"}}],"usage":{"prompt_tokens":1000000,"completion_tokens":0}}
                        """));

        String inbound =
                """
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"foo":"bar","stream":false}
                """
                        .formatted(catalog.modelName());

        webTestClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(inbound)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content")
                .isEqualTo("hi")
                .jsonPath("$.usage.prompt_tokens")
                .isEqualTo(1_000_000);

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo("/chat/completions");
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + catalog.secret());
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).doesNotContain(catalog.accessToken());
        JsonNode outbound = objectMapper.readTree(recorded.getBody().readUtf8());
        assertThat(outbound.path("foo").asText()).isEqualTo("bar");
        assertThat(outbound.path("model").asText()).isEqualTo(catalog.modelName());

        assertThat(usageCount(catalog.tokenCode())).isEqualTo(1L);
        assertThat(usageAmount(catalog.tokenCode())).isEqualTo(200L);
        List<LlmCallLogEntity> logs =
                callLogRepository.findByLlmApikeyCodeOrderByCreateTimeDesc(catalog.apikeyCode()).collectList().block();
        assertThat(logs).isNotEmpty();
        assertThat(logs.getFirst().isSuccess()).isTrue();
    }

    @Test
    @DisplayName("2xx 无 usage 返回 502 且不记账")
    void nonStreamMissingUsageIsBadGateway() {
        Catalog catalog = seed();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"id\":\"c1\",\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));

        webTestClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(catalog.modelName(), false))
                .exchange()
                .expectStatus()
                .isEqualTo(502)
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("bad_gateway");

        assertThat(usageCount(catalog.tokenCode())).isZero();
        List<LlmCallLogEntity> logs =
                callLogRepository.findByLlmApikeyCodeOrderByCreateTimeDesc(catalog.apikeyCode()).collectList().block();
        assertThat(logs).anyMatch(log -> !log.isSuccess() && "missing usage".equals(log.getMessage()));
    }

    @Test
    @DisplayName("上游 4xx 透传且不记账")
    void nonStreamUpstreamClientErrorIsPassedThrough() {
        Catalog catalog = seed();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"error\":{\"message\":\"bad request\"}}"));

        webTestClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(catalog.modelName(), false))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.message")
                .isEqualTo("bad request");

        assertThat(usageCount(catalog.tokenCode())).isZero();
    }

    @Test
    @DisplayName("未授权模型 403")
    void unauthorizedModelIsForbidden() {
        Catalog catalog = ChatTestSupport.seed(
                userAdminService,
                tokenAdminService,
                apikeyAdminService,
                modelAdminService,
                mockWebServer.url("/").toString(),
                List.of(),
                false,
                ChatTestSupport.generousLimits(),
                60);

        webTestClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(catalog.modelName(), false))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("forbidden");
    }

    @Test
    @DisplayName("令牌允许但目录没有或禁用的模型是 404")
    void allowedNameMissingFromCatalogIsNotFound() {
        String missing = "missing-" + System.nanoTime();
        Catalog catalog = ChatTestSupport.seed(
                userAdminService,
                tokenAdminService,
                apikeyAdminService,
                modelAdminService,
                mockWebServer.url("/").toString(),
                List.of(missing),
                false,
                ChatTestSupport.generousLimits(),
                60);

        webTestClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(missing, false))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("not_found");
    }

    @Test
    @DisplayName("超额 429")
    void exceededQuotaIsTooManyRequests() {
        Catalog catalog = ChatTestSupport.seed(
                userAdminService,
                tokenAdminService,
                apikeyAdminService,
                modelAdminService,
                mockWebServer.url("/").toString(),
                List.of(),
                true,
                List.of(new UsageLimitInput(1, 100L), new UsageLimitInput(2, 10_000L)),
                60);
        accessApi.recordUsage(catalog.tokenCode(), 1, 0, 101L, catalog.modelName(), UsageMeta.empty())
                .block();

        webTestClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(catalog.modelName(), false))
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("quota_exceeded");
    }

    @Test
    @DisplayName("缺 model 或非法 JSON 是 400")
    void invalidJsonOrMissingModelIsBadRequest() {
        Catalog catalog = seed();
        webTestClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not-json")
                .exchange()
                .expectStatus()
                .isBadRequest();
        webTestClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"messages\":[]}")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName("GET /v1/models 是授权名与目录启用名求交，不含单价 secret，不打 QPM")
    void modelsAreIntersectionWithoutSecretsAndSkipQuota() {
        Catalog catalog = ChatTestSupport.seed(
                userAdminService,
                tokenAdminService,
                apikeyAdminService,
                modelAdminService,
                mockWebServer.url("/").toString(),
                List.of("not-in-catalog-" + System.nanoTime()),
                true,
                ChatTestSupport.generousLimits(),
                1);
        modelAdminService
                .create(
                        "other-" + System.nanoTime(),
                        "deepseek",
                        catalog.apikeyCode(),
                        200L,
                        400L,
                        true)
                .block();

        webTestClient
                .get()
                .uri("/v1/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.object")
                .isEqualTo("list")
                .jsonPath("$.data.length()")
                .isEqualTo(1)
                .jsonPath("$.data[0].id")
                .isEqualTo(catalog.modelName())
                .jsonPath("$.data[0].object")
                .isEqualTo("model")
                .jsonPath("$.data[0].price")
                .doesNotExist()
                .jsonPath("$.data[0].secret")
                .doesNotExist()
                .jsonPath("$.data[0].provider")
                .doesNotExist()
                .jsonPath("$.data[0].inputPriceFenPerMillion")
                .doesNotExist();

        webTestClient
                .get()
                .uri("/v1/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .exchange()
                .expectStatus()
                .isOk();
    }

    private Catalog seed() {
        return ChatTestSupport.seed(
                userAdminService,
                tokenAdminService,
                apikeyAdminService,
                modelAdminService,
                mockWebServer.url("/").toString(),
                List.of(),
                true,
                ChatTestSupport.generousLimits(),
                60);
    }

    private static String body(String model, boolean stream) {
        return """
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"stream":%s}
                """
                .formatted(model, stream);
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
