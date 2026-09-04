package com.liang.gateway.ai.internal.mcp.application;

import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.infrastructure.jpa.AiJpaExecutor;
import com.liang.gateway.ai.internal.mcp.domain.OpenApiToolImporter;
import com.liang.gateway.ai.internal.mcp.domain.ToolArgDefinitions;
import com.liang.gateway.ai.internal.mcp.infrastructure.McpIdentityCodes;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpServerEntity;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpToolEntity;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpToolRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

@Service
public class McpToolAdminService {

    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    private final AiJpaExecutor jpaExecutor;
    private final McpServerAdminService serverAdminService;
    private final McpToolRepository toolRepository;
    private final AiClock aiClock;
    private final JsonMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public McpToolAdminService(
            AiJpaExecutor jpaExecutor,
            McpServerAdminService serverAdminService,
            McpToolRepository toolRepository,
            AiClock aiClock,
            JsonMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jpaExecutor = jpaExecutor;
        this.serverAdminService = serverAdminService;
        this.toolRepository = toolRepository;
        this.aiClock = aiClock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Mono<McpToolSnapshot> create(
            String serverCode,
            String name,
            String description,
            String httpUrl,
            String httpMethod,
            Map<String, String> httpHeaders,
            Integer timeoutMs,
            Object args,
            boolean enabled) {
        return jpaExecutor.call(() -> {
            McpServerEntity server = serverAdminService.requireServer(serverCode);
            JsonNode argsNode = McpJsonSupport.argsNode(objectMapper, args);
            ValidatedTool validated = validateTool(name, description, httpUrl, httpMethod, timeoutMs, argsNode);
            requireUniqueName(server.getCode(), validated.name(), null);
            McpToolEntity saved = toolRepository.save(McpToolEntity.create(
                    McpIdentityCodes.toolCode(),
                    server.getCode(),
                    validated.name(),
                    validated.description(),
                    validated.httpUrl(),
                    validated.httpMethod(),
                    McpJsonSupport.writeHeaders(objectMapper, httpHeaders),
                    validated.timeoutMs(),
                    validated.argsJson(),
                    enabled,
                    aiClock.nowShanghai()));
            return toSnapshot(saved);
        });
    }

    public Mono<List<McpToolSnapshot>> list(String serverCode) {
        return jpaExecutor.call(() -> {
            McpServerEntity server = serverAdminService.requireServer(serverCode);
            return toolRepository.findByServerCodeOrderByNameAsc(server.getCode()).stream()
                    .map(this::toSnapshot)
                    .toList();
        });
    }

    public Mono<McpToolSnapshot> get(String serverCode, String toolCode) {
        return jpaExecutor.call(() -> toSnapshot(requireTool(serverCode, toolCode)));
    }

    public Mono<McpToolSnapshot> update(
            String serverCode,
            String toolCode,
            String name,
            String description,
            String httpUrl,
            String httpMethod,
            Map<String, String> httpHeaders,
            boolean httpHeadersPresent,
            Integer timeoutMs,
            Object args,
            Boolean enabled) {
        return jpaExecutor.call(() -> {
            McpToolEntity entity = requireTool(serverCode, toolCode);
            String nextName = name == null ? entity.getName() : name;
            String nextDescription = description == null ? entity.getDescription() : description;
            String nextUrl = httpUrl == null ? entity.getHttpUrl() : httpUrl;
            String nextMethod = httpMethod == null ? entity.getHttpMethod() : httpMethod;
            Integer nextTimeout = timeoutMs == null ? entity.getTimeoutMs() : timeoutMs;
            JsonNode nextArgs = args == null
                    ? McpJsonSupport.readTree(objectMapper, entity.getArgs(), "args")
                    : McpJsonSupport.argsNode(objectMapper, args);
            ValidatedTool validated = validateTool(nextName, nextDescription, nextUrl, nextMethod, nextTimeout, nextArgs);
            requireUniqueName(entity.getServerCode(), validated.name(), entity.getCode());
            String nextHeaders = httpHeadersPresent
                    ? McpJsonSupport.writeHeaders(objectMapper, httpHeaders)
                    : entity.getHttpHeaders();
            boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
            entity.update(
                    validated.name(),
                    validated.description(),
                    validated.httpUrl(),
                    validated.httpMethod(),
                    nextHeaders,
                    validated.timeoutMs(),
                    validated.argsJson(),
                    nextEnabled,
                    aiClock.nowShanghai());
            return toSnapshot(toolRepository.save(entity));
        });
    }

    public Mono<Void> delete(String serverCode, String toolCode) {
        return jpaExecutor.run(() -> {
            McpToolEntity entity = requireTool(serverCode, toolCode);
            toolRepository.delete(entity);
        });
    }

    public Mono<List<McpToolSnapshot>> importTools(String serverCode, JsonNode swagger, List<String> paths) {
        return jpaExecutor.call(() -> transactionTemplate.execute(status -> {
            McpServerEntity server = serverAdminService.requireServer(serverCode);
            List<OpenApiToolImporter.Draft> drafts;
            try {
                drafts = OpenApiToolImporter.importSelected(swagger, paths, objectMapper);
            } catch (IllegalArgumentException ex) {
                throw new McpBadRequestException(ex.getMessage());
            }
            Set<String> batchNames = new LinkedHashSet<>();
            List<ValidatedTool> validated = new ArrayList<>();
            for (OpenApiToolImporter.Draft draft : drafts) {
                if (!batchNames.add(draft.name())) {
                    throw new McpBadRequestException("duplicate tool name: " + draft.name());
                }
                requireUniqueName(server.getCode(), draft.name(), null);
                validated.add(validateTool(
                        draft.name(),
                        draft.description(),
                        draft.httpUrl(),
                        draft.httpMethod(),
                        DEFAULT_TIMEOUT_MS,
                        draft.args()));
            }
            LocalDateTime now = aiClock.nowShanghai();
            List<McpToolSnapshot> created = new ArrayList<>();
            for (ValidatedTool tool : validated) {
                McpToolEntity saved = toolRepository.save(McpToolEntity.create(
                        McpIdentityCodes.toolCode(),
                        server.getCode(),
                        tool.name(),
                        tool.description(),
                        tool.httpUrl(),
                        tool.httpMethod(),
                        null,
                        tool.timeoutMs(),
                        tool.argsJson(),
                        true,
                        now));
                created.add(toSnapshot(saved));
            }
            return List.copyOf(created);
        }));
    }

    private McpToolEntity requireTool(String serverCode, String toolCode) {
        serverAdminService.requireServer(serverCode);
        McpToolEntity entity = toolRepository
                .findByCode(toolCode)
                .orElseThrow(() -> new McpNotFoundException("MCP tool not found"));
        if (!entity.getServerCode().equals(serverCode)) {
            throw new McpNotFoundException("MCP tool not found");
        }
        return entity;
    }

    private void requireUniqueName(String serverCode, String name, String currentCode) {
        toolRepository.findByServerCodeAndName(serverCode, name).ifPresent(existing -> {
            if (currentCode == null || !existing.getCode().equals(currentCode)) {
                throw new McpBadRequestException("tool name already exists");
            }
        });
    }

    private ValidatedTool validateTool(
            String name, String description, String httpUrl, String httpMethod, Integer timeoutMs, JsonNode args) {
        String nextName = McpServerAdminService.requireText(name, "name", 128);
        String nextDescription = McpServerAdminService.requireText(description, "description", 512);
        String nextUrl = McpServerAdminService.requireText(httpUrl, "httpUrl", 512);
        String nextMethod;
        try {
            nextMethod = ToolArgDefinitions.uppercaseMethod(httpMethod);
            ToolArgDefinitions.validate(args, nextUrl);
        } catch (IllegalArgumentException ex) {
            throw new McpBadRequestException(ex.getMessage());
        }
        int nextTimeout = timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
        if (nextTimeout <= 0) {
            throw new McpBadRequestException("timeoutMs must be positive");
        }
        return new ValidatedTool(
                nextName, nextDescription, nextUrl, nextMethod, nextTimeout, ToolArgDefinitions.canonicalize(args, objectMapper));
    }

    private McpToolSnapshot toSnapshot(McpToolEntity entity) {
        return new McpToolSnapshot(
                entity.getCode(),
                entity.getServerCode(),
                entity.getName(),
                entity.getDescription(),
                entity.getHttpUrl(),
                entity.getHttpMethod(),
                McpJsonSupport.readHeaders(objectMapper, entity.getHttpHeaders()),
                entity.getTimeoutMs(),
                McpJsonSupport.readArgs(objectMapper, entity.getArgs()),
                entity.isEnabled(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    private record ValidatedTool(
            String name, String description, String httpUrl, String httpMethod, int timeoutMs, String argsJson) {}
}
