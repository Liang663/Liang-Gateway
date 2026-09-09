package com.liang.gateway.ai.internal.mcp.application;

import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.mcp.domain.OpenApiToolImporter;
import com.liang.gateway.ai.internal.mcp.domain.ToolArgDefinitions;
import com.liang.gateway.ai.internal.mcp.infrastructure.McpIdentityCodes;
import com.liang.gateway.ai.internal.mcp.infrastructure.persistence.McpToolEntity;
import com.liang.gateway.ai.internal.mcp.infrastructure.persistence.McpToolRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class McpToolAdminService {

    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    private final McpServerAdminService serverAdminService;
    private final McpToolRepository toolRepository;
    private final AiClock aiClock;
    private final JsonMapper objectMapper;
    private final TransactionalOperator transactionalOperator;

    public McpToolAdminService(
            McpServerAdminService serverAdminService,
            McpToolRepository toolRepository,
            AiClock aiClock,
            JsonMapper objectMapper,
            TransactionalOperator transactionalOperator) {
        this.serverAdminService = serverAdminService;
        this.toolRepository = toolRepository;
        this.aiClock = aiClock;
        this.objectMapper = objectMapper;
        this.transactionalOperator = transactionalOperator;
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
        return serverAdminService.requireServer(serverCode).flatMap(server -> {
            JsonNode argsNode = McpJsonSupport.argsNode(objectMapper, args);
            ValidatedTool validated = validateTool(name, description, httpUrl, httpMethod, timeoutMs, argsNode);
            return requireUniqueName(server.getCode(), validated.name(), null)
                    .then(toolRepository.save(McpToolEntity.create(
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
                            aiClock.nowShanghai())))
                    .map(this::toSnapshot);
        });
    }

    public Mono<List<McpToolSnapshot>> list(String serverCode) {
        return serverAdminService
                .requireServer(serverCode)
                .thenMany(toolRepository.findByServerCodeOrderByNameAsc(serverCode).map(this::toSnapshot))
                .collectList();
    }

    public Mono<McpToolSnapshot> get(String serverCode, String toolCode) {
        return requireTool(serverCode, toolCode).map(this::toSnapshot);
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
        return requireTool(serverCode, toolCode).flatMap(entity -> {
            String nextName = name == null ? entity.getName() : name;
            String nextDescription = description == null ? entity.getDescription() : description;
            String nextUrl = httpUrl == null ? entity.getHttpUrl() : httpUrl;
            String nextMethod = httpMethod == null ? entity.getHttpMethod() : httpMethod;
            Integer nextTimeout = timeoutMs == null ? entity.getTimeoutMs() : timeoutMs;
            JsonNode nextArgs = args == null
                    ? McpJsonSupport.readTree(objectMapper, entity.getArgs(), "args")
                    : McpJsonSupport.argsNode(objectMapper, args);
            ValidatedTool validated = validateTool(nextName, nextDescription, nextUrl, nextMethod, nextTimeout, nextArgs);
            String nextHeaders = httpHeadersPresent
                    ? McpJsonSupport.writeHeaders(objectMapper, httpHeaders)
                    : entity.getHttpHeaders();
            boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
            return requireUniqueName(entity.getServerCode(), validated.name(), entity.getCode())
                    .then(Mono.fromCallable(() -> {
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
                        return entity;
                    }))
                    .flatMap(toolRepository::save)
                    .map(this::toSnapshot);
        });
    }

    public Mono<Void> delete(String serverCode, String toolCode) {
        return requireTool(serverCode, toolCode).flatMap(toolRepository::delete);
    }

    public Mono<List<McpToolSnapshot>> importTools(String serverCode, JsonNode swagger, List<String> paths) {
        return serverAdminService.requireServer(serverCode).flatMap(server -> {
            List<OpenApiToolImporter.Draft> drafts;
            try {
                drafts = OpenApiToolImporter.importSelected(swagger, paths, objectMapper);
            } catch (IllegalArgumentException ex) {
                return Mono.error(new McpBadRequestException(ex.getMessage()));
            }
            Set<String> batchNames = new LinkedHashSet<>();
            List<ValidatedTool> validated = new ArrayList<>();
            for (OpenApiToolImporter.Draft draft : drafts) {
                if (!batchNames.add(draft.name())) {
                    return Mono.error(new McpBadRequestException("duplicate tool name: " + draft.name()));
                }
                try {
                    validated.add(validateTool(
                            draft.name(),
                            draft.description(),
                            draft.httpUrl(),
                            draft.httpMethod(),
                            DEFAULT_TIMEOUT_MS,
                            draft.args()));
                } catch (RuntimeException ex) {
                    return Mono.error(ex);
                }
            }
            LocalDateTime now = aiClock.nowShanghai();
            Mono<List<McpToolSnapshot>> write = Flux.fromIterable(validated)
                    .concatMap(tool -> requireUniqueName(server.getCode(), tool.name(), null)
                            .then(toolRepository.save(McpToolEntity.create(
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
                                    now)))
                            .map(this::toSnapshot))
                    .collectList();
            return transactionalOperator.transactional(write);
        });
    }

    private Mono<McpToolEntity> requireTool(String serverCode, String toolCode) {
        return serverAdminService.requireServer(serverCode).then(toolRepository.findByCode(toolCode)).flatMap(entity -> {
            if (!entity.getServerCode().equals(serverCode)) {
                return Mono.error(new McpNotFoundException("MCP tool not found"));
            }
            return Mono.just(entity);
        }).switchIfEmpty(Mono.error(new McpNotFoundException("MCP tool not found")));
    }

    private Mono<Void> requireUniqueName(String serverCode, String name, String currentCode) {
        return toolRepository.findByServerCodeAndName(serverCode, name).flatMap(existing -> {
            if (currentCode == null || !existing.getCode().equals(currentCode)) {
                return Mono.error(new McpBadRequestException("tool name already exists"));
            }
            return Mono.empty();
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
