package com.liang.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liang.gateway.ai.internal.mcp.application.McpBadRequestException;
import com.liang.gateway.ai.internal.mcp.application.McpServerAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpServerSnapshot;
import com.liang.gateway.ai.internal.mcp.application.McpToolAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpToolSnapshot;
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
class McpOpenApiImportTest {

    @Autowired
    private McpApi mcpApi;

    @Autowired
    private McpServerAdminService serverAdminService;

    @Autowired
    private McpToolAdminService toolAdminService;

    @Autowired
    private JsonMapper objectMapper;

    @Test
    @DisplayName("解析 $ref、{pet-id} 占位和 servers.url 变量，导入后可 prepareCall")
    void refHyphenAndServerVariables() {
        McpServerSnapshot server = newServer("ref");
        List<McpToolSnapshot> tools = toolAdminService
                .importTools(server.code(), read(refSwagger()), List.of("/pets/{pet-id}", "/pets"))
                .block();
        assertThat(tools).extracting(McpToolSnapshot::name).containsExactlyInAnyOrder("createPet", "getPetById");
        McpToolSnapshot get = named(tools, "getPetById");
        assertThat(get.httpUrl()).isEqualTo("https://api.example.com/pets/{pet-id}");
        assertThat(arg(get, "pet-id")).containsEntry("position", "path").containsEntry("value_type", "string");
        McpToolSnapshot create = named(tools, "createPet");
        assertThat(create.httpUrl()).isEqualTo("https://api.example.com/pets");
        assertThat(arg(create, "name")).containsEntry("position", "body").containsEntry("value_type", "string");

        StepVerifier.create(mcpApi.prepareCall(server.path(), "getPetById", Map.of("pet-id", "p-9")))
                .assertNext(prepare -> {
                    McpUpstream upstream = ((McpCallPrepare.Ready) prepare).upstream();
                    assertThat(upstream.url()).isEqualTo("https://api.example.com/pets/p-9");
                    assertThat(upstream.httpMethod()).isEqualTo("GET");
                })
                .verifyComplete();
        StepVerifier.create(mcpApi.prepareCall(server.path(), "createPet", Map.of("name", "n")))
                .assertNext(prepare -> {
                    McpUpstream upstream = ((McpCallPrepare.Ready) prepare).upstream();
                    assertThat(upstream.url()).isEqualTo("https://api.example.com/pets");
                    assertThat(new String(upstream.body())).contains("\"name\":\"n\"");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Swagger2 使用 in:body 与参数 type，formData 与未解析 $ref 返回 400")
    void swagger2AndRejectedDocs() {
        McpServerSnapshot server = newServer("sw2");
        List<McpToolSnapshot> tools = toolAdminService
                .importTools(server.code(), read(swagger2()), List.of("/items/{id}"))
                .block();
        McpToolSnapshot create = named(tools, "createItem");
        assertThat(create.httpUrl()).isEqualTo("https://api.example.com/v1/items/{id}");
        assertThat(arg(create, "id")).containsEntry("position", "path").containsEntry("value_type", "integer");
        assertThat(arg(create, "q")).containsEntry("position", "query").containsEntry("value_type", "boolean");
        assertThat(arg(create, "title")).containsEntry("position", "body").containsEntry("value_type", "string");
        assertThat(arg(create, "count")).containsEntry("position", "body").containsEntry("value_type", "integer");

        StepVerifier.create(mcpApi.prepareCall(
                        server.path(), "createItem", Map.of("id", 7, "q", true, "title", "hi", "count", 2)))
                .assertNext(prepare -> {
                    McpUpstream upstream = ((McpCallPrepare.Ready) prepare).upstream();
                    assertThat(upstream.url()).isEqualTo("https://api.example.com/v1/items/7?q=true");
                    assertThat(upstream.httpMethod()).isEqualTo("POST");
                    JsonNode body = objectMapper.readTree(upstream.body());
                    assertThat(body.path("title").asText()).isEqualTo("hi");
                    assertThat(body.path("count").asInt()).isEqualTo(2);
                })
                .verifyComplete();

        McpServerSnapshot form = newServer("form");
        StepVerifier.create(toolAdminService.importTools(form.code(), read(formDataSwagger()), List.of("/upload")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(McpBadRequestException.class);
                    assertThat(error.getMessage()).contains("formData");
                })
                .verify();

        McpServerSnapshot missing = newServer("miss");
        StepVerifier.create(toolAdminService.importTools(missing.code(), read(unresolvedRef()), List.of("/pets/{id}")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(McpBadRequestException.class);
                    assertThat(error.getMessage()).contains("#/components/parameters/Missing");
                })
                .verify();
    }

    @Test
    @DisplayName("超长 name 在写入前失败，已有同名时整批不写入新工具")
    void validateBeforeInsert() {
        McpServerSnapshot server = newServer("len");
        StepVerifier.create(toolAdminService.importTools(server.code(), read(longNameSwagger()), List.of("/x")))
                .expectError(McpBadRequestException.class)
                .verify();
        assertThat(toolAdminService.list(server.code()).block()).isEmpty();

        toolAdminService
                .create(
                        server.code(),
                        "getPetById",
                        "existing",
                        "https://api.example.com/pets/{pet-id}",
                        "GET",
                        Map.of(),
                        30000,
                        List.of(Map.of(
                                "name",
                                "pet-id",
                                "value_type",
                                "string",
                                "required",
                                true,
                                "position",
                                "path")),
                        true)
                .block();
        StepVerifier.create(toolAdminService.importTools(server.code(), read(refSwagger()), List.of("/pets/{pet-id}", "/pets")))
                .expectError(McpBadRequestException.class)
                .verify();
        assertThat(toolAdminService.list(server.code()).block())
                .extracting(McpToolSnapshot::name)
                .containsExactly("getPetById");
    }

    private McpServerSnapshot newServer(String prefix) {
        return serverAdminService
                .create("导入", prefix + "-" + System.nanoTime(), null, "1.0.0", true)
                .block();
    }

    private JsonNode read(String json) {
        return objectMapper.readTree(json);
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

    private static String refSwagger() {
        return """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "Pets", "version": "1.0.0"},
                  "servers": [{
                    "url": "https://{env}.example.com",
                    "variables": {"env": {"default": "api"}}
                  }],
                  "paths": {
                    "/pets/{pet-id}": {
                      "get": {
                        "operationId": "getPetById",
                        "parameters": [{ "$ref": "#/components/parameters/PetId" }]
                      }
                    },
                    "/pets": {
                      "post": {
                        "operationId": "createPet",
                        "requestBody": { "$ref": "#/components/requestBodies/PetBody" }
                      }
                    }
                  },
                  "components": {
                    "parameters": {
                      "PetId": {
                        "name": "pet-id",
                        "in": "path",
                        "required": true,
                        "schema": { "$ref": "#/components/schemas/PetId" }
                      }
                    },
                    "schemas": {
                      "PetId": { "type": "string" },
                      "Pet": {
                        "type": "object",
                        "required": ["name"],
                        "properties": { "name": { "type": "string" } }
                      }
                    },
                    "requestBodies": {
                      "PetBody": {
                        "content": {
                          "application/json": {
                            "schema": { "$ref": "#/components/schemas/Pet" }
                          }
                        }
                      }
                    }
                  }
                }
                """;
    }

    private static String swagger2() {
        return """
                {
                  "swagger": "2.0",
                  "host": "api.example.com",
                  "basePath": "/v1",
                  "schemes": ["https"],
                  "paths": {
                    "/items/{id}": {
                      "post": {
                        "operationId": "createItem",
                        "parameters": [
                          {"name": "id", "in": "path", "required": true, "type": "integer"},
                          {"name": "q", "in": "query", "type": "boolean"},
                          {
                            "name": "body",
                            "in": "body",
                            "schema": {
                              "type": "object",
                              "required": ["title"],
                              "properties": {
                                "title": {"type": "string"},
                                "count": {"type": "integer"}
                              }
                            }
                          }
                        ]
                      }
                    }
                  }
                }
                """;
    }

    private static String formDataSwagger() {
        return """
                {
                  "swagger": "2.0",
                  "host": "api.example.com",
                  "paths": {
                    "/upload": {
                      "post": {
                        "operationId": "upload",
                        "parameters": [
                          {"name": "file", "in": "formData", "type": "file", "required": true}
                        ]
                      }
                    }
                  }
                }
                """;
    }

    private static String unresolvedRef() {
        return """
                {
                  "openapi": "3.0.0",
                  "servers": [{"url": "https://api.example.com"}],
                  "paths": {
                    "/pets/{id}": {
                      "get": {
                        "operationId": "getPet",
                        "parameters": [{ "$ref": "#/components/parameters/Missing" }]
                      }
                    }
                  }
                }
                """;
    }

    private static String longNameSwagger() {
        String name = "n".repeat(129);
        return """
                {
                  "openapi": "3.0.0",
                  "servers": [{"url": "https://api.example.com"}],
                  "paths": {
                    "/x": {
                      "get": {
                        "operationId": "%s",
                        "summary": "too long"
                      }
                    }
                  }
                }
                """
                .formatted(name);
    }
}
