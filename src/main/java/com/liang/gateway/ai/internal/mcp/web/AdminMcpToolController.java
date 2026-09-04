package com.liang.gateway.ai.internal.mcp.web;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.liang.gateway.ai.internal.mcp.application.McpToolAdminService;
import com.liang.gateway.ai.internal.mcp.application.McpToolSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/mcp/servers/{serverCode}/tools")
public class AdminMcpToolController {

    private final McpToolAdminService toolAdminService;

    public AdminMcpToolController(McpToolAdminService toolAdminService) {
        this.toolAdminService = toolAdminService;
    }

    @PostMapping
    public Mono<McpToolSnapshot> create(
            @PathVariable String serverCode, @Valid @RequestBody CreateToolRequest request) {
        return toolAdminService.create(
                serverCode,
                request.name(),
                request.description(),
                request.httpUrl(),
                request.httpMethod(),
                request.httpHeaders(),
                request.timeoutMs(),
                request.args(),
                request.enabledOrDefault());
    }

    @GetMapping
    public Mono<List<McpToolSnapshot>> list(@PathVariable String serverCode) {
        return toolAdminService.list(serverCode);
    }

    @GetMapping("/{toolCode}")
    public Mono<McpToolSnapshot> get(@PathVariable String serverCode, @PathVariable String toolCode) {
        return toolAdminService.get(serverCode, toolCode);
    }

    @PutMapping("/{toolCode}")
    public Mono<McpToolSnapshot> update(
            @PathVariable String serverCode,
            @PathVariable String toolCode,
            @RequestBody UpdateToolRequest request) {
        return toolAdminService.update(
                serverCode,
                toolCode,
                request.getName(),
                request.getDescription(),
                request.getHttpUrl(),
                request.getHttpMethod(),
                request.getHttpHeaders(),
                request.httpHeadersPresent(),
                request.getTimeoutMs(),
                request.getArgs(),
                request.getEnabled());
    }

    @DeleteMapping("/{toolCode}")
    public Mono<Void> delete(@PathVariable String serverCode, @PathVariable String toolCode) {
        return toolAdminService.delete(serverCode, toolCode);
    }

    public record CreateToolRequest(
            @NotBlank String name,
            @NotBlank String description,
            @NotBlank String httpUrl,
            @NotBlank String httpMethod,
            Map<String, String> httpHeaders,
            Integer timeoutMs,
            Object args,
            Boolean enabled) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    public static final class UpdateToolRequest {

        private String name;
        private String description;
        private String httpUrl;
        private String httpMethod;
        private Map<String, String> httpHeaders;
        private boolean httpHeadersPresent;
        private Integer timeoutMs;
        private Object args;
        private Boolean enabled;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getHttpUrl() {
            return httpUrl;
        }

        public void setHttpUrl(String httpUrl) {
            this.httpUrl = httpUrl;
        }

        public String getHttpMethod() {
            return httpMethod;
        }

        public void setHttpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
        }

        public Map<String, String> getHttpHeaders() {
            return httpHeaders;
        }

        @JsonSetter("httpHeaders")
        public void setHttpHeaders(Map<String, String> httpHeaders) {
            this.httpHeaders = httpHeaders;
            this.httpHeadersPresent = true;
        }

        boolean httpHeadersPresent() {
            return httpHeadersPresent;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Object getArgs() {
            return args;
        }

        public void setArgs(Object args) {
            this.args = args;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
