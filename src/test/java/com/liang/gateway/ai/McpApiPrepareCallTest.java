package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.mcp.application.McpServerAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpServerSnapshot;
import com.liang.gateway.ai.internal.mcp.application.McpToolAdminService;
import com.liang.gateway.ai.internal.mcp.infrastructure.McpIdentityCodes;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpToolEntity;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpToolRepository;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class McpApiPrepareCallTest {

    @Autowired
    private McpApi mcpApi;

    @Autowired
    private McpServerAdminService serverAdminService;

    @Autowired
    private McpToolAdminService toolAdminService;

    @Autowired
    private McpToolRepository toolRepository;

    @Autowired
    private AiClock aiClock;

    @Autowired
    private JsonMapper objectMapper;

    @Test
    @DisplayName("prepareCall 按 path/query/header/body 落点组出站，且不含入站 Authorization")
    void fourPositionsWithoutInboundAuthorization() {
        Fixture fixture = seedMappedTool(true);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("id", "e1");
        arguments.put("q", "cn");
        arguments.put("X-Trace", "override");
        arguments.put("city", "上海");

        StepVerifier.create(mcpApi.prepareCall(fixture.path(), "upsert_employee", arguments))
                .assertNext(prepare -> {
                    assertThat(prepare).isInstanceOf(McpCallPrepare.Ready.class);
                    McpUpstream upstream = ((McpCallPrepare.Ready) prepare).upstream();
                    assertThat(upstream.url()).isEqualTo("https://biz.example.com/employees/e1?q=cn");
                    assertThat(upstream.httpMethod()).isEqualTo("POST");
                    assertThat(upstream.headers())
                            .containsEntry("X-Static", "s")
                            .containsEntry("X-Trace", "override")
                            .doesNotContainKey("Authorization");
                    assertThat(upstream.headers().keySet().stream().noneMatch("Authorization"::equalsIgnoreCase))
                            .isTrue();
                    assertThat(upstream.timeoutMs()).isEqualTo(5000);
                    JsonNode body = objectMapper.readTree(upstream.body());
                    assertThat(body.path("city").asText()).isEqualTo("上海");
                    assertThat(new String(upstream.body(), StandardCharsets.UTF_8)).doesNotContain("Authorization");
                    assertThat(upstream.toString()).doesNotContain("Bearer sk-inbound");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("工具不存在或未启用返回 tool_unavailable 且不出站")
    void toolUnavailableIsCompletedError() {
        Fixture fixture = seedMappedTool(false);
        StepVerifier.create(mcpApi.prepareCall(fixture.path(), "missing", Map.of()))
                .assertNext(prepare -> assertErrorPrefix(prepare, "tool_unavailable:"))
                .verifyComplete();
        StepVerifier.create(mcpApi.prepareCall(fixture.path(), "upsert_employee", Map.of("id", "e1")))
                .assertNext(prepare -> assertErrorPrefix(prepare, "tool_unavailable:"))
                .verifyComplete();
    }

    @Test
    @DisplayName("缺必填、类型不符、多余键、path 占位不符分别使用固定前缀")
    void argumentFailuresUseStablePrefixes() {
        Fixture fixture = seedMappedTool(true);
        StepVerifier.create(mcpApi.prepareCall(fixture.path(), "upsert_employee", Map.of("city", "上海")))
                .assertNext(prepare -> assertErrorPrefix(prepare, "missing_argument:"))
                .verifyComplete();
        StepVerifier.create(mcpApi.prepareCall(
                        fixture.path(), "upsert_employee", Map.of("id", 1, "city", "上海")))
                .assertNext(prepare -> assertErrorPrefix(prepare, "argument_type:"))
                .verifyComplete();
        StepVerifier.create(mcpApi.prepareCall(
                        fixture.path(),
                        "upsert_employee",
                        Map.of("id", "e1", "city", "上海", "extra", "x")))
                .assertNext(prepare -> assertErrorPrefix(prepare, "unknown_argument:"))
                .verifyComplete();

        String mismatchPath = uniquePath("mm");
        McpServerSnapshot server =
                serverAdminService.create("占位", mismatchPath, null, "1.0.0", true).block();
        toolRepository.save(McpToolEntity.create(
                McpIdentityCodes.toolCode(),
                server.code(),
                "mismatch",
                "对不上",
                "https://biz.example.com/items/{id}",
                "GET",
                null,
                30000,
                "[{\"name\":\"userId\",\"value_type\":\"string\",\"required\":true,\"position\":\"path\"}]",
                true,
                aiClock.nowShanghai()));
        StepVerifier.create(mcpApi.prepareCall(mismatchPath, "mismatch", Map.of("userId", "u1")))
                .assertNext(prepare -> assertErrorPrefix(prepare, "path_mismatch:"))
                .verifyComplete();
    }

    @Test
    @DisplayName("header 含 CR/LF 时 argument_type，不出站")
    void headerCrLfIsArgumentType() {
        Fixture fixture = seedMappedTool(true);
        StepVerifier.create(mcpApi.prepareCall(
                        fixture.path(),
                        "upsert_employee",
                        Map.of("id", "e1", "city", "上海", "X-Trace", "ok\r\nX-Injected: 1")))
                .assertNext(prepare -> assertErrorPrefix(prepare, "argument_type:"))
                .verifyComplete();
    }

    @Test
    @DisplayName("无 body 参数时出站 body 为空")
    void noBodyArgsYieldEmptyBody() {
        String path = uniquePath("get");
        McpServerSnapshot server =
                serverAdminService.create("查询", path, null, "1.0.0", true).block();
        toolAdminService
                .create(
                        server.code(),
                        "get_item",
                        "查询",
                        "https://biz.example.com/items/{id}",
                        "get",
                        Map.of(),
                        null,
                        List.of(Map.of(
                                "name", "id", "value_type", "string", "required", true, "position", "path")),
                        true)
                .block();
        StepVerifier.create(mcpApi.prepareCall(path, "get_item", Map.of("id", "9")))
                .assertNext(prepare -> {
                    McpUpstream upstream = ((McpCallPrepare.Ready) prepare).upstream();
                    assertThat(upstream.httpMethod()).isEqualTo("GET");
                    assertThat(upstream.url()).isEqualTo("https://biz.example.com/items/9");
                    assertThat(upstream.body()).isEmpty();
                })
                .verifyComplete();
    }

    private Fixture seedMappedTool(boolean enabled) {
        String path = uniquePath("call");
        McpServerSnapshot server =
                serverAdminService.create("调用", path, null, "1.0.0", true).block();
        List<Map<String, Object>> args = List.of(
                Map.of("name", "id", "value_type", "string", "required", true, "position", "path"),
                Map.of("name", "q", "value_type", "string", "required", false, "position", "query"),
                Map.of("name", "X-Trace", "value_type", "string", "required", false, "position", "header"),
                Map.of(
                        "name",
                        "city",
                        "value_type",
                        "string",
                        "required",
                        true,
                        "description",
                        "城市",
                        "position",
                        "body"));
        toolAdminService
                .create(
                        server.code(),
                        "upsert_employee",
                        "更新员工",
                        "https://biz.example.com/employees/{id}",
                        "POST",
                        Map.of("X-Static", "s", "X-Trace", "default"),
                        5000,
                        args,
                        enabled)
                .block();
        return new Fixture(path, server.code());
    }

    private static void assertErrorPrefix(McpCallPrepare prepare, String prefix) {
        assertThat(prepare).isInstanceOf(McpCallPrepare.Completed.class);
        McpCallResult result = ((McpCallPrepare.Completed) prepare).result();
        assertThat(result.isError()).isTrue();
        assertThat(result.text()).startsWith(prefix);
        assertThat(prepare.needsOutbound()).isFalse();
    }

    private static String uniquePath(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private record Fixture(String path, String serverCode) {}
}
