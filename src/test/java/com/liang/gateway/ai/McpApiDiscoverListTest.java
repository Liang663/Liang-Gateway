package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.mcp.application.McpServerAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpServerSnapshot;
import com.liang.gateway.ai.internal.mcp.application.McpToolAdminService;
import com.liang.gateway.ai.internal.mcp.infrastructure.McpIdentityCodes;
import com.liang.gateway.ai.internal.mcp.infrastructure.persistence.McpToolEntity;
import com.liang.gateway.ai.internal.mcp.infrastructure.persistence.McpToolRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class McpApiDiscoverListTest {

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

    @Test
    @DisplayName("discover 返回 2026 能力与 serverInfo，空 description 不带 instructions")
    void discoverEnabledServer() {
        String path = uniquePath("emp");
        serverAdminService
                .create("员工服务", path, "查询员工", "1.0.0", true)
                .block();
        StepVerifier.create(mcpApi.discover(path))
                .assertNext(result -> {
                    assertThat(result.supportedVersions()).containsExactly("2026-07-28");
                    assertThat(result.capabilities()).containsEntry("tools", Map.of());
                    assertThat(result.capabilities()).doesNotContainKeys("prompts", "resources");
                    assertThat(result.meta().serverInfo().name()).isEqualTo("员工服务");
                    assertThat(result.meta().serverInfo().version()).isEqualTo("1.0.0");
                    assertThat(result.instructions()).isEqualTo("查询员工");
                    assertThat(result.resultType()).isEqualTo("complete");
                    assertThat(result.ttlMs()).isZero();
                    assertThat(result.cacheScope()).isEqualTo("private");
                })
                .verifyComplete();

        String silent = uniquePath("silent");
        serverAdminService.create("静默服务", silent, null, "1.0.0", true).block();
        StepVerifier.create(mcpApi.discover(silent))
                .assertNext(result -> assertThat(result.instructions()).isNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("path 不存在或未启用时失败类型明确")
    void missingOrDisabledServerFails() {
        String disabledPath = uniquePath("off");
        serverAdminService.create("关闭", disabledPath, null, "1.0.0", false).block();
        StepVerifier.create(mcpApi.discover("no-such-path"))
                .expectError(McpServerNotFoundException.class)
                .verify();
        StepVerifier.create(mcpApi.discover(disabledPath))
                .expectError(McpServerNotFoundException.class)
                .verify();
        StepVerifier.create(mcpApi.listTools(disabledPath))
                .expectError(McpServerNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("list 只含启用工具、按 name 排序，inputSchema 来自 args")
    void listEnabledToolsSortedFromArgs() {
        String path = uniquePath("list");
        McpServerSnapshot server =
                serverAdminService.create("目录", path, null, "1.0.0", true).block();
        toolAdminService
                .create(
                        server.code(),
                        "zeta",
                        "Z",
                        "https://biz.example.com/z",
                        "GET",
                        Map.of(),
                        30000,
                        List.of(),
                        true)
                .block();
        toolAdminService
                .create(
                        server.code(),
                        "alpha",
                        "员工",
                        "https://biz.example.com/employees/{id}",
                        "GET",
                        Map.of(),
                        30000,
                        List.of(Map.of(
                                "name",
                                "id",
                                "value_type",
                                "string",
                                "required",
                                true,
                                "description",
                                "员工 ID",
                                "position",
                                "path")),
                        true)
                .block();
        toolAdminService
                .create(
                        server.code(),
                        "mu",
                        "禁用",
                        "https://biz.example.com/mu",
                        "GET",
                        Map.of(),
                        30000,
                        List.of(),
                        false)
                .block();

        StepVerifier.create(mcpApi.listTools(path))
                .assertNext(result -> {
                    assertThat(result.resultType()).isEqualTo("complete");
                    assertThat(result.ttlMs()).isZero();
                    assertThat(result.cacheScope()).isEqualTo("private");
                    assertThat(result.tools()).extracting(McpToolsListResult.Tool::name).containsExactly("alpha", "zeta");
                    McpToolsListResult.Tool alpha = result.tools().getFirst();
                    assertThat(alpha.inputSchema().get("type")).isEqualTo("object");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> properties = (Map<String, Object>) alpha.inputSchema().get("properties");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> id = (Map<String, Object>) properties.get("id");
                    assertThat(id.get("type")).isEqualTo("string");
                    assertThat(id.get("description")).isEqualTo("员工 ID");
                    assertThat(alpha.inputSchema().get("required")).asList().containsExactly("id");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("params._meta.protocolVersion 不是 2026-07-28 时带 supported")
    void unsupportedProtocolVersion() {
        String path = uniquePath("ver");
        serverAdminService.create("版本", path, null, "1.0.0", true).block();
        StepVerifier.create(mcpApi.discover(path, "2025-03-26"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(McpProtocolVersionException.class);
                    assertThat(((McpProtocolVersionException) error).supported()).containsExactly("2026-07-28");
                })
                .verify();
        StepVerifier.create(mcpApi.listTools(path, "1.0"))
                .expectError(McpProtocolVersionException.class)
                .verify();
    }

    @Test
    @DisplayName("list 遇到坏 args 仍返回其他工具，不整表失败")
    void listSkipsCorruptArgs() {
        String path = uniquePath("badargs");
        McpServerSnapshot server =
                serverAdminService.create("坏参", path, null, "1.0.0", true).block();
        toolAdminService
                .create(
                        server.code(),
                        "alpha",
                        "ok",
                        "https://biz.example.com/a",
                        "GET",
                        Map.of(),
                        30000,
                        List.of(),
                        true)
                .block();
        toolRepository.save(McpToolEntity.create(
                McpIdentityCodes.toolCode(),
                server.code(),
                "broken",
                "坏",
                "https://biz.example.com/b",
                "GET",
                null,
                30000,
                "{}",
                true,
                aiClock.nowShanghai()))
                .block();
        StepVerifier.create(mcpApi.listTools(path))
                .assertNext(result -> {
                    assertThat(result.tools()).extracting(McpToolsListResult.Tool::name).containsExactly("alpha", "broken");
                    McpToolsListResult.Tool broken = result.tools().stream()
                            .filter(tool -> "broken".equals(tool.name()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(broken.inputSchema().get("type")).isEqualTo("object");
                })
                .verifyComplete();
    }

    private static String uniquePath(String prefix) {
        return prefix + "-" + System.nanoTime();
    }
}
