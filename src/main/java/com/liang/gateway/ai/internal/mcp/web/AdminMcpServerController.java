package com.liang.gateway.ai.internal.mcp.web;

import com.liang.gateway.ai.internal.mcp.application.McpServerAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpServerSnapshot;
import com.liang.gateway.ai.internal.mcp.application.McpToolAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpToolSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/admin/mcp/servers")
public class AdminMcpServerController {

    private final McpServerAdminService serverAdminService;
    private final McpToolAdminService toolAdminService;

    public AdminMcpServerController(McpServerAdminService serverAdminService, McpToolAdminService toolAdminService) {
        this.serverAdminService = serverAdminService;
        this.toolAdminService = toolAdminService;
    }

    @PostMapping
    public Mono<McpServerSnapshot> create(@Valid @RequestBody CreateServerRequest request) {
        return serverAdminService.create(
                request.name(),
                request.path(),
                request.description(),
                request.version(),
                request.enabledOrDefault());
    }

    @GetMapping
    public Mono<List<McpServerSnapshot>> list() {
        return serverAdminService.list();
    }

    @GetMapping("/{code}")
    public Mono<McpServerSnapshot> get(@PathVariable String code) {
        return serverAdminService.get(code);
    }

    @PutMapping("/{code}")
    public Mono<McpServerSnapshot> update(@PathVariable String code, @Valid @RequestBody UpdateServerRequest request) {
        return serverAdminService.update(
                code, request.name(), request.path(), request.description(), request.version(), request.enabled());
    }

    @DeleteMapping("/{code}")
    public Mono<Void> delete(@PathVariable String code) {
        return serverAdminService.delete(code);
    }

    @PostMapping("/{code}/tools:import")
    public Mono<List<McpToolSnapshot>> importTools(
            @PathVariable String code, @Valid @RequestBody ImportToolsRequest request) {
        return toolAdminService.importTools(code, request.swagger(), request.paths());
    }

    public record CreateServerRequest(
            @NotBlank String name, @NotBlank String path, String description, @NotBlank String version, Boolean enabled) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    public record UpdateServerRequest(String name, String path, String description, String version, Boolean enabled) {}

    public record ImportToolsRequest(JsonNode swagger, List<String> paths) {}
}
