package com.liang.gateway.ai.internal.mcp.application;

import com.liang.gateway.ai.McpApi;
import com.liang.gateway.ai.McpApi.Outcome;
import com.liang.gateway.ai.McpApi.Transport;
import com.liang.gateway.ai.McpCallPrepare;
import com.liang.gateway.ai.McpCallResult;
import com.liang.gateway.ai.McpDiscoverResult;
import com.liang.gateway.ai.McpServerNotFoundException;
import com.liang.gateway.ai.McpToolsListResult;
import com.liang.gateway.ai.internal.infrastructure.jpa.AiJpaExecutor;
import com.liang.gateway.ai.internal.mcp.domain.McpCallWrapper;
import com.liang.gateway.ai.internal.mcp.domain.McpFailureTexts;
import com.liang.gateway.ai.internal.mcp.domain.McpJsonRpc;
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

    @Override
    public Mono<Outcome> handle(String serverPath, Transport transport, byte[] jsonRpcBody) {
        return Mono.defer(() -> parseAndDispatch(serverPath, transport, jsonRpcBody));
    }

    @Override
    public Mono<Outcome.Reply> completeCall(Object id, Integer httpStatus, String body, boolean timedOut) {
        return Mono.just(McpJsonRpc.result(id, wrapCall(httpStatus, body, timedOut)));
    }

    private Mono<Outcome> parseAndDispatch(String serverPath, Transport transport, byte[] jsonRpcBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonRpcBody == null ? new byte[0] : jsonRpcBody);
        } catch (RuntimeException ex) {
            return Mono.just(McpJsonRpc.parseError());
        }
        if (root == null || root.isArray() || !root.isObject()) {
            return Mono.just(McpJsonRpc.invalidRequest(null));
        }
        Object id = McpJsonRpc.idValue(root.get("id"));
        if (!McpJsonRpc.JSONRPC.equals(McpJsonRpc.text(root.get("jsonrpc")))) {
            return Mono.just(McpJsonRpc.invalidRequest(id));
        }
        String method = McpJsonRpc.text(root.get("method"));
        if (method == null || method.isBlank()) {
            return Mono.just(McpJsonRpc.invalidRequest(id));
        }
        String headerVersion = transport == null ? null : transport.protocolVersion();
        if (!PROTOCOL_VERSION.equals(headerVersion)) {
            return Mono.just(McpJsonRpc.unsupportedVersion(id));
        }
        String methodHeader = transport == null ? null : transport.methodHeader();
        if (!method.equals(methodHeader)) {
            return Mono.just(McpJsonRpc.invalidRequest(id));
        }
        JsonNode params = root.get("params");
        if ("tools/call".equals(method)) {
            String paramsName = params == null ? null : McpJsonRpc.text(params.get("name"));
            String nameHeader = transport == null ? null : transport.nameHeader();
            if (paramsName == null || !paramsName.equals(nameHeader)) {
                return Mono.just(McpJsonRpc.invalidRequest(id));
            }
        }
        String metaVersion = McpJsonRpc.metaProtocolVersion(params);
        return dispatchProtocol(serverPath, method, params, metaVersion, id);
    }

    private Mono<Outcome> dispatchProtocol(
            String serverPath, String method, JsonNode params, String protocolVersion, Object id) {
        return switch (method) {
            case "server/discover" -> discover(serverPath, protocolVersion)
                    .map(result -> (Outcome) McpJsonRpc.result(id, result))
                    .onErrorResume(ex -> Mono.just(McpJsonRpc.mapApiError(ex, id)));
            case "tools/list" -> listTools(serverPath, protocolVersion)
                    .map(result -> (Outcome) McpJsonRpc.result(id, result))
                    .onErrorResume(ex -> Mono.just(McpJsonRpc.mapApiError(ex, id)));
            case "tools/call" -> prepareCall(
                            serverPath,
                            params == null ? null : McpJsonRpc.text(params.get("name")),
                            McpJsonRpc.arguments(params, objectMapper),
                            protocolVersion)
                    .map(prepare -> toCallOutcome(id, prepare))
                    .onErrorResume(ex -> Mono.just(McpJsonRpc.mapApiError(ex, id)));
            default -> Mono.just(McpJsonRpc.methodNotFound(id));
        };
    }

    private static Outcome toCallOutcome(Object id, McpCallPrepare prepare) {
        if (prepare instanceof McpCallPrepare.Completed completed) {
            return McpJsonRpc.result(id, completed.result());
        }
        return new Outcome.NeedsOutbound(id, ((McpCallPrepare.Ready) prepare).upstream());
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
