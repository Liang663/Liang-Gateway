package com.liang.gateway.ai.internal.mcp.domain;

import com.liang.gateway.ai.McpApi;
import com.liang.gateway.ai.McpProtocolVersionException;
import com.liang.gateway.ai.McpServerNotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class McpJsonRpc {

    public static final String JSONRPC = "2.0";
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int SERVER_ERROR = -32000;
    public static final int HTTP_BAD_REQUEST = 400;
    public static final int HTTP_NOT_FOUND = 404;
    public static final int HTTP_OK = 200;

    private McpJsonRpc() {}

    public static McpApi.Outcome.Reply parseError() {
        return error(HTTP_BAD_REQUEST, null, PARSE_ERROR, "Parse error", null);
    }

    public static McpApi.Outcome.Reply invalidRequest(Object id) {
        return error(HTTP_BAD_REQUEST, id, INVALID_REQUEST, "Invalid Request", null);
    }

    public static McpApi.Outcome.Reply unsupportedVersion(Object id) {
        return unsupportedVersion(id, "Unsupported MCP protocol version", List.of(McpApi.PROTOCOL_VERSION));
    }

    public static McpApi.Outcome.Reply unsupportedVersion(Object id, String message, List<String> supported) {
        return error(
                HTTP_BAD_REQUEST,
                id,
                INVALID_REQUEST,
                message,
                Map.of("supported", supported == null ? List.of() : List.copyOf(supported)));
    }

    public static McpApi.Outcome.Reply methodNotFound(Object id) {
        return error(HTTP_NOT_FOUND, id, METHOD_NOT_FOUND, "Method not found", null);
    }

    public static McpApi.Outcome.Reply serverNotFound(Object id, String message) {
        return error(HTTP_NOT_FOUND, id, SERVER_ERROR, message, null);
    }

    public static McpApi.Outcome.Reply result(Object id, Object result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC);
        envelope.put("id", id);
        envelope.put("result", result);
        return new McpApi.Outcome.Reply(HTTP_OK, envelope);
    }

    public static McpApi.Outcome.Reply mapApiError(Throwable ex, Object id) {
        if (ex instanceof McpServerNotFoundException serverNotFound) {
            return serverNotFound(id, serverNotFound.getMessage());
        }
        if (ex instanceof McpProtocolVersionException version) {
            return unsupportedVersion(id, version.getMessage(), version.supported());
        }
        if (ex instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException(ex);
    }

    public static Object idValue(JsonNode id) {
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

    public static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        return node.asText();
    }

    public static String metaProtocolVersion(JsonNode params) {
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
    public static Map<String, Object> arguments(JsonNode params, JsonMapper mapper) {
        if (params == null || !params.isObject()) {
            return Map.of();
        }
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull() || !arguments.isObject()) {
            return Map.of();
        }
        Map<String, Object> converted = mapper.convertValue(arguments, LinkedHashMap.class);
        return converted == null ? Map.of() : converted;
    }

    private static McpApi.Outcome.Reply error(
            int httpStatus, Object id, int code, String message, Map<String, Object> data) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        if (data != null) {
            error.put("data", data);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC);
        envelope.put("id", id);
        envelope.put("error", error);
        return new McpApi.Outcome.Reply(httpStatus, envelope);
    }
}