package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.ai.internal.mcp.application.McpToolAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpToolSnapshot;
import com.liang.gateway.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AdminMcpHttpTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private McpApi mcpApi;

    @Autowired
    private McpToolAdminService toolAdminService;

    @Autowired
    private JsonMapper objectMapper;

    @Test
    @DisplayName("导入后校验 http_url/position，并能 prepareCall；未勾选 path 不导入")
    void importThenPrepareCall() {
        String path = "pets-" + System.nanoTime();
        String serverCode = createServer(path);

        webTestClient
                .post()
                .uri("/admin/mcp/servers")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "坏路径", "path", "a/b", "version", "1.0.0"))
                .exchange()
                .expectStatus()
                .isBadRequest();

        webTestClient
                .post()
                .uri("/admin/mcp/servers/" + serverCode + "/tools:import")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("swagger", minimalSwagger(), "paths", List.of("/pets/{id}", "/pets")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2);

        webTestClient
                .post()
                .uri("/admin/mcp/servers/" + serverCode + "/tools:import")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("swagger", minimalSwagger(), "paths", List.of("/missing")))
                .exchange()
                .expectStatus()
                .isBadRequest();

        List<McpToolSnapshot> tools = toolAdminService.list(serverCode).block();
        assertThat(tools).extracting(McpToolSnapshot::name).containsExactly("createPet", "getPet");
        McpToolSnapshot getPet = named(tools, "getPet");
        assertThat(getPet.httpUrl()).isEqualTo("https://api.example.com/pets/{id}");
        assertThat(getPet.httpMethod()).isEqualTo("GET");
        assertThat(arg(getPet, "id"))
                .containsEntry("position", "path")
                .containsEntry("value_type", "string");
        assertThat(arg(getPet, "verbose"))
                .containsEntry("position", "query")
                .containsEntry("value_type", "boolean");
        McpToolSnapshot createPet = named(tools, "createPet");
        assertThat(createPet.httpUrl()).isEqualTo("https://api.example.com/pets");
        assertThat(createPet.httpMethod()).isEqualTo("POST");
        assertThat(arg(createPet, "name"))
                .containsEntry("position", "body")
                .containsEntry("value_type", "string");
        assertThat(arg(createPet, "tags"))
                .containsEntry("position", "body")
                .containsEntry("value_type", "array");

        McpCallPrepare get = mcpApi.prepareCall(path, "getPet", Map.of("id", "p1", "verbose", true)).block();
        McpUpstream getUpstream = ((McpCallPrepare.Ready) get).upstream();
        assertThat(getUpstream.url()).isEqualTo("https://api.example.com/pets/p1?verbose=true");
        assertThat(getUpstream.httpMethod()).isEqualTo("GET");
        assertThat(getUpstream.body()).isEmpty();

        McpCallPrepare create =
                mcpApi.prepareCall(path, "createPet", Map.of("name", "n", "tags", List.of("a"))).block();
        McpUpstream createUpstream = ((McpCallPrepare.Ready) create).upstream();
        assertThat(createUpstream.url()).isEqualTo("https://api.example.com/pets");
        assertThat(createUpstream.httpMethod()).isEqualTo("POST");
        JsonNode body = objectMapper.readTree(createUpstream.body());
        assertThat(body.path("name").asText()).isEqualTo("n");
        assertThat(body.path("tags").get(0).asText()).isEqualTo("a");

        assertThat(mcpApi.listTools(path).block().tools())
                .extracting(McpToolsListResult.Tool::name)
                .doesNotContain("hidePet");
    }

    @Test
    @DisplayName("管理面可更新删除工具，path 占位不符与 header 注入被拒绝")
    void toolUpdateDeleteAndValidation() {
        String path = "crud-" + System.nanoTime();
        String serverCode = createServer(path);
        byte[] created = webTestClient
                .post()
                .uri("/admin/mcp/servers/" + serverCode + "/tools")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name",
                        "echo",
                        "description",
                        "echo",
                        "httpUrl",
                        "https://biz.example.com/echo/{id}",
                        "httpMethod",
                        "GET",
                        "args",
                        List.of(Map.of(
                                "name", "id", "value_type", "string", "required", true, "position", "path"))))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("echo")
                .returnResult()
                .getResponseBody();
        String toolCode = extract(new String(created == null ? new byte[0] : created, StandardCharsets.UTF_8), "\"code\":\"", "\"");

        webTestClient
                .put()
                .uri("/admin/mcp/servers/" + serverCode + "/tools/" + toolCode)
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("description", "updated"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.description")
                .isEqualTo("updated");

        webTestClient
                .post()
                .uri("/admin/mcp/servers/" + serverCode + "/tools")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name",
                        "bad-path",
                        "description",
                        "bad",
                        "httpUrl",
                        "https://biz.example.com/items",
                        "httpMethod",
                        "GET",
                        "args",
                        List.of(Map.of(
                                "name", "id", "value_type", "string", "required", true, "position", "path"))))
                .exchange()
                .expectStatus()
                .isBadRequest();

        webTestClient
                .post()
                .uri("/admin/mcp/servers/" + serverCode + "/tools")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name",
                        "inject",
                        "description",
                        "inject",
                        "httpUrl",
                        "https://biz.example.com/x",
                        "httpMethod",
                        "GET",
                        "httpHeaders",
                        Map.of("X-A", "ok\r\nX-Injected: 1"),
                        "args",
                        List.of()))
                .exchange()
                .expectStatus()
                .isBadRequest();

        webTestClient
                .delete()
                .uri("/admin/mcp/servers/" + serverCode + "/tools/" + toolCode)
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .exchange()
                .expectStatus()
                .isOk();
        webTestClient
                .get()
                .uri("/admin/mcp/servers/" + serverCode + "/tools/" + toolCode)
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    private String createServer(String path) {
        byte[] serverBody = webTestClient
                .post()
                .uri("/admin/mcp/servers")
                .header("X-Admin-Token", TestTokens.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "宠物", "path", path, "version", "1.0.0", "enabled", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.code")
                .exists()
                .jsonPath("$.path")
                .isEqualTo(path)
                .returnResult()
                .getResponseBody();
        return extract(new String(serverBody == null ? new byte[0] : serverBody, StandardCharsets.UTF_8), "\"code\":\"", "\"");
    }

    private static McpToolSnapshot named(List<McpToolSnapshot> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
    }

    private static Map<String, Object> arg(McpToolSnapshot tool, String name) {
        return tool.args().stream()
                .filter(item -> name.equals(item.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> minimalSwagger() {
        return Map.of(
                "openapi",
                "3.0.0",
                "info",
                Map.of("title", "Pets", "version", "1.0.0"),
                "servers",
                List.of(Map.of("url", "https://api.example.com")),
                "paths",
                Map.of(
                        "/pets/{id}",
                        Map.of(
                                "get",
                                Map.of(
                                        "operationId",
                                        "getPet",
                                        "summary",
                                        "Get a pet",
                                        "parameters",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "id",
                                                        "in",
                                                        "path",
                                                        "required",
                                                        true,
                                                        "description",
                                                        "pet id",
                                                        "schema",
                                                        Map.of("type", "string")),
                                                Map.of(
                                                        "name",
                                                        "verbose",
                                                        "in",
                                                        "query",
                                                        "required",
                                                        false,
                                                        "schema",
                                                        Map.of("type", "boolean"))))),
                        "/pets",
                        Map.of(
                                "post",
                                Map.of(
                                        "operationId",
                                        "createPet",
                                        "summary",
                                        "Create pet",
                                        "requestBody",
                                        Map.of(
                                                "required",
                                                true,
                                                "content",
                                                Map.of(
                                                        "application/json",
                                                        Map.of(
                                                                "schema",
                                                                Map.of(
                                                                        "type",
                                                                        "object",
                                                                        "required",
                                                                        List.of("name"),
                                                                        "properties",
                                                                        Map.of(
                                                                                "name",
                                                                                Map.of(
                                                                                        "type",
                                                                                        "string",
                                                                                        "description",
                                                                                        "pet name"),
                                                                                "tags",
                                                                                Map.of(
                                                                                        "type",
                                                                                        "array",
                                                                                        "items",
                                                                                        Map.of(
                                                                                                "type",
                                                                                                "string"))))))))),
                        "/hidden",
                        Map.of("get", Map.of("operationId", "hidePet", "summary", "hidden"))));
    }

    private static String extract(String json, String prefix, String suffix) {
        int start = json.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        start += prefix.length();
        int end = json.indexOf(suffix, start);
        return json.substring(start, end);
    }
}
