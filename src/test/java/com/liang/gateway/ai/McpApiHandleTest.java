package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.ai.internal.mcp.application.McpServerAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpServerSnapshot;
import com.liang.gateway.ai.internal.mcp.application.McpToolAdminService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class McpApiHandleTest {

    @Autowired
    private McpApi mcpApi;

    @Autowired
    private McpServerAdminService serverAdminService;

    @Autowired
    private McpToolAdminService toolAdminService;

    @Autowired
    private JsonMapper objectMapper;

    @Test
    @DisplayName("非法 JSON 是 HTTP 400 且 -32700")
    void illegalJsonIsParseError() {
        StepVerifier.create(mcpApi.handle("any", transport("server/discover", null), "{".getBytes(StandardCharsets.UTF_8)))
                .assertNext(outcome -> assertError(outcome, 400, -32700))
                .verifyComplete();
    }

    @Test
    @DisplayName("initialize 是 HTTP 404 且 -32601")
    void initializeIsMethodNotFound() {
        StepVerifier.create(mcpApi.handle("any", transport("initialize", null), rpc(4, "initialize", Map.of())))
                .assertNext(outcome -> assertError(outcome, 404, -32601))
                .verifyComplete();
    }

    @Test
    @DisplayName("头版本或 params._meta.protocolVersion 错误时 400 且带 supported")
    void protocolVersionMismatchIncludesSupported() {
        byte[] discover = rpc(7, "server/discover", Map.of());
        StepVerifier.create(mcpApi.handle(
                        "any", new McpApi.Transport("2025-03-26", "server/discover", null), discover))
                .assertNext(outcome -> assertUnsupportedVersion(outcome))
                .verifyComplete();

        byte[] metaWrong = rpc(
                8, "server/discover", Map.of("_meta", Map.of("protocolVersion", "2025-03-26")));
        StepVerifier.create(mcpApi.handle("any", transport("server/discover", null), metaWrong))
                .assertNext(outcome -> assertUnsupportedVersion(outcome))
                .verifyComplete();
    }

    @Test
    @DisplayName("Mcp-Method 与 JSON-RPC method 不一致时 400 -32600")
    void methodHeaderMismatchIsInvalidRequest() {
        StepVerifier.create(mcpApi.handle(
                        "any",
                        new McpApi.Transport(McpApi.PROTOCOL_VERSION, "tools/list", null),
                        rpc(9, "server/discover", Map.of())))
                .assertNext(outcome -> assertError(outcome, 400, -32600))
                .verifyComplete();
    }

    @Test
    @DisplayName("discover 信封含 jsonrpc 2.0 与 result")
    void discoverWrapsJsonRpcResult() {
        String path = uniquePath("disc");
        serverAdminService.create("协议发现", path, "desc", "1.0.0", true).block();
        StepVerifier.create(mcpApi.handle(path, transport("server/discover", null), rpc(1, "server/discover", Map.of())))
                .assertNext(outcome -> {
                    assertThat(outcome).isInstanceOf(McpApi.Outcome.Reply.class);
                    McpApi.Outcome.Reply reply = (McpApi.Outcome.Reply) outcome;
                    assertThat(reply.httpStatus()).isEqualTo(200);
                    assertThat(reply.jsonRpc()).containsEntry("jsonrpc", "2.0");
                    assertThat(reply.jsonRpc()).containsKey("result");
                    assertThat(reply.jsonRpc()).doesNotContainKey("error");
                    assertThat(reply.jsonRpc().get("id")).isEqualTo(1);
                    assertThat(reply.jsonRpc().get("result")).isInstanceOf(McpDiscoverResult.class);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("缺必填参数是 Reply 200 isError missing_argument，不是 NeedsOutbound")
    void missingRequiredArgsIsCompletedError() {
        Fixture fixture = seedCallTool();
        byte[] body = rpc(
                2,
                "tools/call",
                Map.of("name", fixture.toolName(), "arguments", Map.of()));
        StepVerifier.create(mcpApi.handle(fixture.path(), transport("tools/call", fixture.toolName()), body))
                .assertNext(outcome -> {
                    assertThat(outcome).isInstanceOf(McpApi.Outcome.Reply.class);
                    McpApi.Outcome.Reply reply = (McpApi.Outcome.Reply) outcome;
                    assertThat(reply.httpStatus()).isEqualTo(200);
                    assertThat(reply.jsonRpc().get("result")).isInstanceOf(McpCallResult.class);
                    McpCallResult result = (McpCallResult) reply.jsonRpc().get("result");
                    assertThat(result.isError()).isTrue();
                    assertThat(result.text()).contains("missing_argument");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("参数齐全的 tools/call 返回 NeedsOutbound")
    void validToolsCallNeedsOutbound() {
        Fixture fixture = seedCallTool();
        byte[] body = rpc(
                3,
                "tools/call",
                Map.of("name", fixture.toolName(), "arguments", Map.of("id", "e1")));
        StepVerifier.create(mcpApi.handle(fixture.path(), transport("tools/call", fixture.toolName()), body))
                .assertNext(outcome -> {
                    assertThat(outcome).isInstanceOf(McpApi.Outcome.NeedsOutbound.class);
                    McpApi.Outcome.NeedsOutbound outbound = (McpApi.Outcome.NeedsOutbound) outcome;
                    assertThat(outbound.id()).isEqualTo(3);
                    assertThat(outbound.upstream().url()).isEqualTo("https://biz.example.com/items/e1");
                    assertThat(outbound.upstream().httpMethod()).isEqualTo("GET");
                })
                .verifyComplete();
    }

    private Fixture seedCallTool() {
        String path = uniquePath("call");
        McpServerSnapshot server =
                serverAdminService.create("协议调用", path, null, "1.0.0", true).block();
        String toolName = "get_item";
        toolAdminService
                .create(
                        server.code(),
                        toolName,
                        "查询",
                        "https://biz.example.com/items/{id}",
                        "GET",
                        Map.of(),
                        5000,
                        List.of(Map.of(
                                "name", "id", "value_type", "string", "required", true, "position", "path")),
                        true)
                .block();
        return new Fixture(path, toolName);
    }

    private byte[] rpc(int id, String method, Map<String, Object> params) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.put("params", params);
        return objectMapper.writeValueAsBytes(envelope);
    }

    private static McpApi.Transport transport(String method, String name) {
        return new McpApi.Transport(McpApi.PROTOCOL_VERSION, method, name);
    }

    @SuppressWarnings("unchecked")
    private static void assertError(McpApi.Outcome outcome, int httpStatus, int code) {
        assertThat(outcome).isInstanceOf(McpApi.Outcome.Reply.class);
        McpApi.Outcome.Reply reply = (McpApi.Outcome.Reply) outcome;
        assertThat(reply.httpStatus()).isEqualTo(httpStatus);
        Map<String, Object> error = (Map<String, Object>) reply.jsonRpc().get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(code);
        assertThat(reply.jsonRpc()).containsEntry("jsonrpc", "2.0");
    }

    @SuppressWarnings("unchecked")
    private static void assertUnsupportedVersion(McpApi.Outcome outcome) {
        assertError(outcome, 400, -32600);
        McpApi.Outcome.Reply reply = (McpApi.Outcome.Reply) outcome;
        Map<String, Object> error = (Map<String, Object>) reply.jsonRpc().get("error");
        Map<String, Object> data = (Map<String, Object>) error.get("data");
        assertThat(data.get("supported")).asList().containsExactly("2026-07-28");
    }

    private static String uniquePath(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private record Fixture(String path, String toolName) {}
}