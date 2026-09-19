package com.liang.gateway.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.ai.McpApi;
import com.liang.gateway.support.TestTokens;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the console's actual HTTP contracts against MySQL/Redis and a local-only upstream. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.mcp.allowed-origins[0]=http://console.test")
@AutoConfigureWebTestClient(timeout = "30s")
class ConsoleWorkflowHttpTest {
    private static final String CONSOLE_ORIGIN = "http://console.test";
    @Autowired WebTestClient client;
    @Autowired JsonMapper mapper;
    private MockWebServer upstream;

    @BeforeEach
    void startLocalUpstream() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
    }

    @AfterEach
    void stopLocalUpstream() throws IOException {
        upstream.shutdown();
    }

    @Test
    @DisplayName("后台HTTP配置模型与令牌后，SSE调用可在日志、账单、统计与实时额度中核对")
    void configureChatAndReadItsAccountingThroughAdminHttp() throws InterruptedException {
        String suffix = UUID.randomUUID().toString();
        String model = "console-" + suffix;
        String upstreamSecret = "sk-console-" + suffix;
        JsonNode key = adminPost("/admin/llm/apikeys", Map.of(
                "name", model, "provider", "mock", "baseUrl", upstream.url("/").toString(),
                "secret", upstreamSecret, "enabled", true));
        assertThat(key.has("secret")).isFalse();
        String keyCode = key.path("code").asText();
        adminPost("/admin/llm/models", Map.of(
                "name", model, "provider", "mock", "apikeyCode", keyCode,
                "inputPriceFenPerMillion", 200, "outputPriceFenPerMillion", 400, "enabled", true));
        TokenFixture token = createToken(model);

        upstream.enqueue(new MockResponse().setHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .setChunkedBody("""
                        data: {"choices":[{"delta":{"content":"console-ok"}}]}

                        data: {"choices":[],"usage":{"prompt_tokens":1000000,"completion_tokens":0,"total_tokens":1000000}}

                        data: [DONE]

                        """, 17));
        var events = client.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("model", model, "stream", true,
                        "stream_options", Map.of("include_usage", true),
                        "messages", List.of(Map.of("role", "user", "content", "test console workflow"))))
                .exchange().expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody();
        StepVerifier.create(events)
                .assertNext(event -> assertThat(event.data()).contains("console-ok"))
                .assertNext(event -> assertThat(event.data()).contains("prompt_tokens"))
                .assertNext(event -> assertThat(event.data()).isEqualTo("[DONE]"))
                .expectComplete().verify(Duration.ofSeconds(10));

        var request = upstream.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/chat/completions");
        assertThat(request.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + upstreamSecret);
        assertThat(request.getHeader("X-Admin-Token")).isNull();

        String recordsPath = "/admin/usage/records?tokenCode=" + token.code() + "&model=" + model;
        String logsPath = "/admin/llm/call-logs?apikeyCode=" + keyCode + "&model=" + model;
        // Streaming accounting completes asynchronously after the last client-visible frame.
        waitForOneRecord(recordsPath);
        waitForOneRecord(logsPath);
        JsonNode records = adminGet(recordsPath);
        assertThat(records.path("total").asLong()).isEqualTo(1);
        assertThat(records.path("items").get(0).path("amountFen").asLong()).isEqualTo(200);
        assertThat(records.path("items").get(0).path("totalTokens").asLong()).isEqualTo(1_000_000);
        JsonNode log = adminGet(logsPath).path("items").get(0);
        assertThat(log.path("success").asBoolean()).isTrue();
        assertThat(log.path("firstTokenMs").isNumber()).isTrue();
        JsonNode stats = adminGet("/admin/usage/stats?range=24h&tokenCode=" + token.code());
        assertThat(stats.path("totalRecords").asLong()).isEqualTo(1);
        assertThat(stats.path("amountFen").asLong()).isEqualTo(200);
        boolean modelCountFound = false;
        for (JsonNode count : adminGet("/admin/llm/stats?range=24h").path("models")) {
            if (model.equals(count.path("model").asText())) {
                assertThat(count.path("count").asLong()).isEqualTo(1);
                modelCountFound = true;
            }
        }
        assertThat(modelCountFound).isTrue();
        JsonNode quota = adminGet("/admin/users/" + token.userCode() + "/tokens/" + token.code() + "/quota");
        assertThat(quota.path("fiveHour").path("used").asLong()).isEqualTo(200);
        assertThat(quota.path("fiveHour").path("limit").asLong()).isEqualTo(10_000);
        assertThat(quota.path("week").path("used").asLong()).isEqualTo(200);
    }

    @Test
    @DisplayName("后台HTTP导入OpenAPI后可使用显式允许Origin发现并调用MCP工具")
    void importOpenApiAndCallTheDiscoveredTool() throws InterruptedException {
        TokenFixture token = createToken("console-unused-" + UUID.randomUUID());
        String path = "console-" + UUID.randomUUID();
        JsonNode server = adminPost("/admin/mcp/servers", Map.of(
                "name", path, "path", path, "description", "local workflow test", "version", "1.0.0", "enabled", true));
        String serverCode = server.path("code").asText();
        var document = Map.of(
                "openapi", "3.0.3", "info", Map.of("title", "Local mock API", "version", "1.0"),
                "servers", List.of(Map.of("url", upstream.url("/").toString())),
                "paths", Map.of("/items/{id}", Map.of("get", Map.of(
                        "operationId", "getItem", "summary", "Read an item", "parameters", List.of(
                                Map.of("name", "id", "in", "path", "required", true,
                                        "schema", Map.of("type", "string"))),
                        "responses", Map.of("200", Map.of("description", "ok"))))));
        JsonNode imported = adminPost("/admin/mcp/servers/" + serverCode + "/tools:import",
                Map.of("swagger", document, "paths", List.of("/items/{id}")));
        assertThat(imported.size()).isEqualTo(1);
        String tool = imported.get(0).path("name").asText();
        assertThat(tool).isEqualTo("getItem");

        JsonNode discovery = rpc(path, token.accessToken(), 1, "server/discover", Map.of());
        assertThat(discovery.path("result").path("capabilities").has("tools")).isTrue();
        JsonNode tools = rpc(path, token.accessToken(), 2, "tools/list", Map.of()).path("result").path("tools");
        assertThat(tools.size()).isEqualTo(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo(tool);
        assertThat(tools.get(0).path("inputSchema").path("required").get(0).asText()).isEqualTo("id");

        upstream.enqueue(new MockResponse().setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"id\":\"42\",\"name\":\"console-item\"}"));
        JsonNode result = rpc(path, token.accessToken(), 3, "tools/call",
                Map.of("name", tool, "arguments", Map.of("id", "42"))).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText()).contains("console-item");
        var request = upstream.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/items/42");
        assertThat(request.getHeader(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(request.getHeader("X-Admin-Token")).isNull();
    }

    private TokenFixture createToken(String model) {
        JsonNode user = adminPost("/admin/users", Map.of(
                "name", "console-user-" + UUID.randomUUID(), "authority", "DATA", "enabled", true));
        String userCode = user.path("code").asText();
        JsonNode token = adminPost("/admin/users/" + userCode + "/tokens", Map.of(
                "qpmLimit", 60, "enabled", true, "models", List.of(model),
                "limits", List.of(Map.of("limitType", 1, "usage", 10_000), Map.of("limitType", 2, "usage", 10_000))));
        return new TokenFixture(userCode, token.path("code").asText(), token.path("accessToken").asText());
    }

    private JsonNode adminPost(String path, Object body) {
        return json(client.post().uri(path).header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    private JsonNode adminGet(String path) {
        return json(client.get().uri(path).header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    private JsonNode rpc(String path, String token, int id, String method, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>(arguments);
        params.put("_meta", Map.of("protocolVersion", McpApi.PROTOCOL_VERSION));
        return json(client.post().uri("/mcp/" + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, CONSOLE_ORIGIN)
                .header("MCP-Protocol-Version", McpApi.PROTOCOL_VERSION)
                .header("Mcp-Method", method)
                .headers(headers -> { if (arguments.containsKey("name")) headers.set("Mcp-Name", arguments.get("name").toString()); })
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    private JsonNode json(byte[] body) {
        assertThat(body).isNotNull();
        return mapper.readTree(body);
    }

    private void waitForOneRecord(String path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (adminGet(path).path("total").asLong() == 1) return;
            Thread.sleep(50);
        }
        assertThat(adminGet(path).path("total").asLong()).isEqualTo(1);
    }

    private record TokenFixture(String userCode, String code, String accessToken) {}
}
