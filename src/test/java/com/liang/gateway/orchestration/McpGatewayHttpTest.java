package com.liang.gateway.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.access.AccessApi;
import com.liang.gateway.access.UsageMeta;
import com.liang.gateway.access.internal.application.TokenAdminService;
import com.liang.gateway.access.internal.application.UsageLimitInput;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.ai.McpApi;
import com.liang.gateway.ai.internal.application.LlmApikeyAdminService;
import com.liang.gateway.ai.internal.application.LlmModelAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpServerAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpServerSnapshot;
import com.liang.gateway.ai.internal.mcp.application.McpToolAdminService;
import com.liang.gateway.orchestration.ChatTestSupport.Catalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
class McpGatewayHttpTest {

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
    private McpServerAdminService serverAdminService;

    @Autowired
    private McpToolAdminService toolAdminService;

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
    @DisplayName("金额窗已满的令牌 discover 仍 200")
    void discoverSucceedsWhenQuotaIsFull() {
        Catalog catalog = exhaustedToken();
        String path = uniquePath("disc");
        serverAdminService.create("discover-server", path, "desc", "1.0.0", true).block();

        webTestClient
                .post()
                .uri("/mcp/" + path)
                .headers(headers -> mcpHeaders(headers, catalog.accessToken(), "server/discover", null))
                .bodyValue(rpc(1, "server/discover", Map.of()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.jsonrpc")
                .isEqualTo("2.0")
                .jsonPath("$.id")
                .isEqualTo(1)
                .jsonPath("$.result.capabilities.tools")
                .exists()
                .jsonPath("$.error")
                .doesNotExist();
    }

    @Test
    @DisplayName("call 缺参 HTTP 200，content 含 missing_argument")
    void missingArgumentIsJsonRpcResult() {
        Catalog catalog = generousToken();
        Fixture fixture = seedCallTool(true);

        JsonNode body = postRpc(
                catalog.accessToken(),
                fixture.path(),
                "tools/call",
                fixture.toolName(),
                rpc(2, "tools/call", Map.of("name", fixture.toolName(), "arguments", Map.of())));

        assertThat(body.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(body.path("result").path("isError").asBoolean()).isTrue();
        assertThat(body.path("result").path("content").get(0).path("text").asText()).contains("missing_argument");
    }

    @Test
    @DisplayName("上游 404 时 Agent 仍收到 HTTP 200，isError 含状态和 body")
    void upstreamNotFoundIsWrapped() throws InterruptedException {
        Catalog catalog = generousToken();
        Fixture fixture = seedCallTool(true);
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"error\":\"nope\"}"));

        JsonNode body = postRpc(
                catalog.accessToken(),
                fixture.path(),
                "tools/call",
                fixture.toolName(),
                rpc(
                        3,
                        "tools/call",
                        Map.of(
                                "name",
                                fixture.toolName(),
                                "arguments",
                                Map.of("id", "e1"))));

        assertThat(body.path("result").path("isError").asBoolean()).isTrue();
        String text = body.path("result").path("content").get(0).path("text").asText();
        assertThat(text).contains("upstream_error:");
        assertThat(text).contains("HTTP 404");
        assertThat(text).contains("nope");

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isNotEqualTo("Bearer " + catalog.accessToken());
    }

    @Test
    @DisplayName("initialize 是 HTTP 404 + -32601")
    void initializeIsMethodNotFound() {
        Catalog catalog = generousToken();
        String path = uniquePath("init");
        serverAdminService.create("init-server", path, null, "1.0.0", true).block();

        webTestClient
                .post()
                .uri("/mcp/" + path)
                .headers(headers -> mcpHeaders(headers, catalog.accessToken(), "initialize", null))
                .bodyValue(rpc(4, "initialize", Map.of()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo(-32601)
                .jsonPath("$.error.type")
                .doesNotExist();
    }

    @Test
    @DisplayName("头版本正确但 params._meta.protocolVersion 错误时 400 且带 supported")
    void paramsMetaProtocolVersionIsChecked() {
        Catalog catalog = generousToken();
        String path = uniquePath("ver");
        serverAdminService.create("ver-server", path, null, "1.0.0", true).block();

        webTestClient
                .post()
                .uri("/mcp/" + path)
                .headers(headers -> mcpHeaders(headers, catalog.accessToken(), "server/discover", null))
                .bodyValue(rpc(
                        7,
                        "server/discover",
                        Map.of("_meta", Map.of("protocolVersion", "2025-03-26"))))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.data.supported[0]")
                .isEqualTo("2026-07-28");
    }

    @Test
    @DisplayName("非法 Origin 403；Accept 不合 406；数组请求 400 -32600")
    void transportChecks() {
        Catalog catalog = generousToken();
        String path = uniquePath("tr");
        serverAdminService.create("transport", path, null, "1.0.0", true).block();

        webTestClient
                .post()
                .uri("/mcp/" + path)
                .headers(headers -> mcpHeaders(headers, catalog.accessToken(), "server/discover", null))
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .bodyValue(rpc(5, "server/discover", Map.of()))
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .post()
                .uri("/mcp/" + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + catalog.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("MCP-Protocol-Version", McpApi.PROTOCOL_VERSION)
                .header("Mcp-Method", "server/discover")
                .bodyValue(rpc(6, "server/discover", Map.of()))
                .exchange()
                .expectStatus()
                .isEqualTo(406);

        webTestClient
                .post()
                .uri("/mcp/" + path)
                .headers(headers -> mcpHeaders(headers, catalog.accessToken(), "server/discover", null))
                .bodyValue("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"}]")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo(-32600);
    }

    private JsonNode postRpc(String accessToken, String path, String method, String name, String payload) {
        byte[] response = webTestClient
                .post()
                .uri("/mcp/" + path)
                .headers(headers -> mcpHeaders(headers, accessToken, method, name))
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        return objectMapper.readTree(response);
    }

    private static void mcpHeaders(HttpHeaders headers, String accessToken, String method, String name) {
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        headers.set("MCP-Protocol-Version", McpApi.PROTOCOL_VERSION);
        headers.set("Mcp-Method", method);
        if (name != null) {
            headers.set("Mcp-Name", name);
        }
    }

    private String rpc(int id, String method, Map<String, Object> params) {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.put("params", params);
        return new String(objectMapper.writeValueAsBytes(envelope), StandardCharsets.UTF_8);
    }

    private Catalog generousToken() {
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

    private Catalog exhaustedToken() {
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
        return catalog;
    }

    private Fixture seedCallTool(boolean enabled) {
        String path = uniquePath("call");
        McpServerSnapshot server =
                serverAdminService.create("call-server", path, null, "1.0.0", true).block();
        String toolName = "get_item";
        toolAdminService
                .create(
                        server.code(),
                        toolName,
                        "查询",
                        mockWebServer.url("/").toString() + "items/{id}",
                        "GET",
                        Map.of(),
                        5000,
                        List.of(Map.of(
                                "name", "id", "value_type", "string", "required", true, "position", "path")),
                        enabled)
                .block();
        return new Fixture(path, toolName);
    }

    private static String uniquePath(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private record Fixture(String path, String toolName) {}
}
