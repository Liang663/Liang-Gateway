package com.liang.gateway.orchestration.internal.application;

import com.liang.gateway.ai.McpApi;
import com.liang.gateway.ai.McpCallPrepare;
import com.liang.gateway.ai.McpCallResult;
import com.liang.gateway.ai.McpProtocolVersionException;
import com.liang.gateway.ai.McpServerNotFoundException;
import com.liang.gateway.ai.McpUpstream;
import com.liang.gateway.core.ProxyApi;
import com.liang.gateway.core.ProxyFailureException;
import com.liang.gateway.core.Upstream;
import com.liang.gateway.orchestration.internal.infrastructure.McpGatewayProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@EnableConfigurationProperties(McpGatewayProperties.class)
public class McpGatewayService {

    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String HEADER_METHOD = "Mcp-Method";
    private static final String HEADER_NAME = "Mcp-Name";

    private final McpApi mcpApi;
    private final ProxyApi proxyApi;
    private final McpGatewayProperties properties;
    private final JsonMapper objectMapper;

    public McpGatewayService(
            McpApi mcpApi, ProxyApi proxyApi, McpGatewayProperties properties, JsonMapper objectMapper) {
        this.mcpApi = mcpApi;
        this.proxyApi = proxyApi;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Mono<ResponseEntity<byte[]>> handle(String path, HttpHeaders headers, byte[] rawBody) {
        ResponseEntity<byte[]> originDenied = rejectOrigin(headers);
        if (originDenied != null) {
            return Mono.just(originDenied);
        }
        if (!acceptsJsonAndSse(headers)) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build());
        }
        if (!isJsonContentType(headers.getContentType())) {
            return Mono.just(jsonRpc(HttpStatus.BAD_REQUEST, null, -32600, "Invalid Request", null));
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody == null ? new byte[0] : rawBody);
        } catch (RuntimeException ex) {
            return Mono.just(jsonRpc(HttpStatus.BAD_REQUEST, null, -32700, "Parse error", null));
        }
        if (root == null || root.isArray() || !root.isObject()) {
            return Mono.just(jsonRpc(HttpStatus.BAD_REQUEST, null, -32600, "Invalid Request", null));
        }
        JsonNode id = root.get("id");
        if (!"2.0".equals(text(root.get("jsonrpc")))) {
            return Mono.just(jsonRpc(HttpStatus.BAD_REQUEST, id, -32600, "Invalid Request", null));
        }
        String method = text(root.get("method"));
        if (method == null || method.isBlank()) {
            return Mono.just(jsonRpc(HttpStatus.BAD_REQUEST, id, -32600, "Invalid Request", null));
        }
        String headerVersion = headers.getFirst(HEADER_PROTOCOL_VERSION);
        if (!McpApi.PROTOCOL_VERSION.equals(headerVersion)) {
            return Mono.just(jsonRpc(
                    HttpStatus.BAD_REQUEST,
                    id,
                    -32600,
                    "Unsupported MCP protocol version",
                    Map.of("supported", List.of(McpApi.PROTOCOL_VERSION))));
        }
        if (!method.equals(headers.getFirst(HEADER_METHOD))) {
            return Mono.just(jsonRpc(HttpStatus.BAD_REQUEST, id, -32600, "Invalid Request", null));
        }
        JsonNode params = root.get("params");
        if ("tools/call".equals(method)) {
            String paramsName = params == null ? null : text(params.get("name"));
            if (paramsName == null || !paramsName.equals(headers.getFirst(HEADER_NAME))) {
                return Mono.just(jsonRpc(HttpStatus.BAD_REQUEST, id, -32600, "Invalid Request", null));
            }
        }
        String metaVersion = metaProtocolVersion(params);
        return dispatch(path, method, params, metaVersion, id);
    }

    private Mono<ResponseEntity<byte[]>> dispatch(
            String path, String method, JsonNode params, String protocolVersion, JsonNode id) {
        return switch (method) {
            case "server/discover" -> mcpApi.discover(path, protocolVersion)
                    .map(result -> result(id, result))
                    .onErrorResume(ex -> mapApiError(ex, id));
            case "tools/list" -> mcpApi.listTools(path, protocolVersion)
                    .map(result -> result(id, result))
                    .onErrorResume(ex -> mapApiError(ex, id));
            case "tools/call" -> call(path, params, protocolVersion, id);
            default -> Mono.just(jsonRpc(HttpStatus.NOT_FOUND, id, -32601, "Method not found", null));
        };
    }

    private Mono<ResponseEntity<byte[]>> call(String path, JsonNode params, String protocolVersion, JsonNode id) {
        String name = params == null ? null : text(params.get("name"));
        Map<String, Object> arguments = arguments(params);
        return mcpApi.prepareCall(path, name, arguments, protocolVersion)
                .flatMap(prepare -> {
                    if (prepare instanceof McpCallPrepare.Completed completed) {
                        return Mono.just(result(id, completed.result()));
                    }
                    McpUpstream upstream = ((McpCallPrepare.Ready) prepare).upstream();
                    return proxyApi.exchange(toUpstream(upstream))
                            .map(response -> mcpApi.wrapCall(
                                    response.status() == null ? null : response.status().value(),
                                    new String(response.body(), StandardCharsets.UTF_8),
                                    false))
                            .onErrorResume(ex -> Mono.just(wrapFailure(ex)))
                            .map(callResult -> result(id, callResult));
                })
                .onErrorResume(ex -> mapApiError(ex, id));
    }

    private McpCallResult wrapFailure(Throwable ex) {
        ProxyFailureException failure = findProxyFailure(ex);
        if (failure != null && failure.status() == HttpStatus.GATEWAY_TIMEOUT) {
            return mcpApi.wrapCall(null, null, true);
        }
        if (failure != null) {
            return mcpApi.wrapCall(failure.status().value(), failure.getMessage(), false);
        }
        return mcpApi.wrapCall(null, null, false);
    }

    private Mono<ResponseEntity<byte[]>> mapApiError(Throwable ex, JsonNode id) {
        if (ex instanceof McpServerNotFoundException serverNotFound) {
            return Mono.just(jsonRpc(HttpStatus.NOT_FOUND, id, -32000, serverNotFound.getMessage(), null));
        }
        if (ex instanceof McpProtocolVersionException version) {
            return Mono.just(jsonRpc(
                    HttpStatus.BAD_REQUEST,
                    id,
                    -32600,
                    version.getMessage(),
                    Map.of("supported", version.supported())));
        }
        return Mono.error(ex);
    }

    private ResponseEntity<byte[]> rejectOrigin(HttpHeaders headers) {
        String origin = headers.getOrigin();
        if (origin == null || origin.isBlank()) {
            return null;
        }
        List<String> allowed = properties.getAllowedOrigins();
        if (allowed != null && allowed.contains(origin)) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    private static boolean acceptsJsonAndSse(HttpHeaders headers) {
        List<MediaType> accept = headers.getAccept();
        boolean json = false;
        boolean sse = false;
        for (MediaType mediaType : accept) {
            if (MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) {
                json = true;
            }
            if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(mediaType)) {
                sse = true;
            }
        }
        if (json && sse) {
            return true;
        }
        String raw = headers.getFirst(HttpHeaders.ACCEPT);
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("application/json") && lower.contains("text/event-stream");
    }

    private static boolean isJsonContentType(MediaType contentType) {
        return contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
    }

    private static String metaProtocolVersion(JsonNode params) {
        if (params == null || !params.isObject()) {
            return null;
        }
        JsonNode meta = params.get("_meta");
        if (meta == null || !meta.isObject()) {
            return null;
        }
        return text(meta.get("protocolVersion"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> arguments(JsonNode params) {
        if (params == null || !params.isObject()) {
            return Map.of();
        }
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull() || !arguments.isObject()) {
            return Map.of();
        }
        Map<String, Object> converted = objectMapper.convertValue(arguments, LinkedHashMap.class);
        return converted == null ? Map.of() : converted;
    }

    private static Upstream toUpstream(McpUpstream mcp) {
        Duration timeout = mcp.timeoutMs() > 0 ? Duration.ofMillis(mcp.timeoutMs()) : null;
        return new Upstream(
                URI.create(mcp.url()), mcp.headers(), false, mcp.httpMethod(), mcp.body(), timeout);
    }

    private ResponseEntity<byte[]> result(JsonNode id, Object result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", idValue(id));
        envelope.put("result", result);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(write(envelope));
    }

    private ResponseEntity<byte[]> jsonRpc(
            HttpStatus status, JsonNode id, int code, String message, Map<String, Object> data) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        if (data != null) {
            error.put("data", data);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", idValue(id));
        envelope.put("error", error);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(write(envelope));
    }

    private byte[] write(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsBytes(envelope);
        } catch (RuntimeException ex) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private static Object idValue(JsonNode id) {
        if (id == null || id.isNull() || id.isMissingNode()) {
            return null;
        }
        if (id.isNumber()) {
            return id.numberValue();
        }
        if (id.isTextual()) {
            return id.asText();
        }
        return id.toString();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        return node.asText();
    }

    private static ProxyFailureException findProxyFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ProxyFailureException failure) {
                return failure;
            }
            current = current.getCause();
        }
        return null;
    }
}
