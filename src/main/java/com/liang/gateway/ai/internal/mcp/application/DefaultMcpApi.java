package com.liang.gateway.ai.internal.mcp.application;

import com.liang.gateway.ai.McpApi;
import com.liang.gateway.ai.McpCallPrepare;
import com.liang.gateway.ai.McpCallResult;
import com.liang.gateway.ai.McpDiscoverResult;
import com.liang.gateway.ai.McpServerNotFoundException;
import com.liang.gateway.ai.McpToolsListResult;
import com.liang.gateway.ai.internal.infrastructure.jpa.AiJpaExecutor;
import com.liang.gateway.ai.internal.mcp.domain.McpCallWrapper;
import com.liang.gateway.ai.internal.mcp.domain.McpFailureTexts;
import com.liang.gateway.ai.internal.mcp.domain.McpProtocol;
import com.liang.gateway.ai.internal.mcp.domain.ToolArgDefinitions;
import com.liang.gateway.ai.internal.mcp.domain.ToolCallAssembler;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpServerEntity;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpServerRepository;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpToolEntity;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpToolRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DefaultMcpApi implements McpApi {

    private final AiJpaExecutor jpaExecutor;
    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final JsonMapper objectMapper;

    public DefaultMcpApi(
            AiJpaExecutor jpaExecutor,
            McpServerRepository serverRepository,
            McpToolRepository toolRepository,
            JsonMapper objectMapper) {
        this.jpaExecutor = jpaExecutor;
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<McpDiscoverResult> discover(String serverPath, String protocolVersion) {
        return Mono.defer(() -> {
            McpProtocol.requireSupported(protocolVersion);
            return jpaExecutor.call(() -> toDiscover(requireEnabledServer(serverPath)));
        });
    }

    @Override
    public Mono<McpToolsListResult> listTools(String serverPath, String protocolVersion) {
        return Mono.defer(() -> {
            McpProtocol.requireSupported(protocolVersion);
            return jpaExecutor.call(() -> {
                McpServerEntity server = requireEnabledServer(serverPath);
                List<McpToolsListResult.Tool> tools = toolRepository
                        .findByServerCodeAndEnabledTrueOrderByNameAsc(server.getCode())
                        .stream()
                        .map(this::toToolSafe)
                        .toList();
                return new McpToolsListResult(tools, "complete", 0L, "private");
            });
        });
    }

    @Override
    public Mono<McpCallPrepare> prepareCall(
            String serverPath, String toolName, Map<String, Object> arguments, String protocolVersion) {
        return Mono.defer(() -> {
            McpProtocol.requireSupported(protocolVersion);
            return jpaExecutor.call(() -> {
                McpServerEntity server = requireEnabledServer(serverPath);
                if (toolName == null || toolName.isBlank()) {
                    return new McpCallPrepare.Completed(McpCallResult.error(McpFailureTexts.toolUnavailable()));
                }
                McpToolEntity tool = toolRepository
                        .findByServerCodeAndName(server.getCode(), toolName)
                        .orElse(null);
                if (tool == null || !tool.isEnabled()) {
                    return new McpCallPrepare.Completed(McpCallResult.error(McpFailureTexts.toolUnavailable()));
                }
                JsonNode args;
                JsonNode headers;
                try {
                    args = parseArgs(tool.getArgs());
                    headers = parseHeaders(tool.getHttpHeaders());
                } catch (RuntimeException ex) {
                    return new McpCallPrepare.Completed(McpCallResult.error(McpFailureTexts.toolUnavailable()));
                }
                JsonNode argumentNode = objectMapper.valueToTree(arguments == null ? Map.of() : arguments);
                ToolCallAssembler.Result assembled;
                try {
                    assembled = ToolCallAssembler.assemble(
                            tool.getHttpUrl(),
                            tool.getHttpMethod(),
                            headers,
                            tool.getTimeoutMs(),
                            args,
                            argumentNode,
                            objectMapper);
                } catch (RuntimeException ex) {
                    return new McpCallPrepare.Completed(McpCallResult.error(McpFailureTexts.toolUnavailable()));
                }
                if (assembled.failed()) {
                    return new McpCallPrepare.Completed(McpCallResult.error(assembled.errorText()));
                }
                return new McpCallPrepare.Ready(assembled.upstream());
            });
        });
    }

    @Override
    public McpCallResult wrapCall(Integer httpStatus, String responseBody, boolean timedOut) {
        return McpCallWrapper.wrap(httpStatus, responseBody, timedOut);
    }

    private McpServerEntity requireEnabledServer(String serverPath) {
        if (serverPath == null || serverPath.isBlank()) {
            throw new McpServerNotFoundException(serverPath);
        }
        McpServerEntity server = serverRepository.findByPath(serverPath).orElse(null);
        if (server == null || !server.isEnabled()) {
            throw new McpServerNotFoundException(serverPath);
        }
        return server;
    }

    private McpDiscoverResult toDiscover(McpServerEntity server) {
        String instructions = server.getDescription();
        if (instructions != null && instructions.isBlank()) {
            instructions = null;
        }
        return new McpDiscoverResult(
                List.of(McpProtocol.VERSION),
                Map.of("tools", Map.of()),
                new McpDiscoverResult.Meta(new McpDiscoverResult.ServerInfo(server.getName(), server.getVersion())),
                instructions,
                "complete",
                0L,
                "private");
    }

    private McpToolsListResult.Tool toToolSafe(McpToolEntity entity) {
        try {
            JsonNode args = parseArgs(entity.getArgs());
            return new McpToolsListResult.Tool(
                    entity.getName(), entity.getDescription(), ToolArgDefinitions.inputSchema(args, objectMapper));
        } catch (RuntimeException ex) {
            return new McpToolsListResult.Tool(
                    entity.getName(),
                    entity.getDescription(),
                    Map.of("type", "object", "properties", Map.of()));
        }
    }

    private JsonNode parseArgs(String json) {
        JsonNode node = parseJson(json);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        return node;
    }

    private JsonNode parseHeaders(String json) {
        JsonNode node = parseJson(json);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.createObjectNode();
        }
        return node;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.missingNode();
        }
        return objectMapper.readTree(json);
    }
}
