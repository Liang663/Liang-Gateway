package com.liang.gateway.ai.internal.mcp.application;

import com.liang.gateway.ai.internal.mcp.domain.ToolArgDefinitions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpJsonSupport {

    private McpJsonSupport() {}

    static JsonNode readTree(JsonMapper mapper, String json, String field) {
        if (json == null || json.isBlank()) {
            return mapper.missingNode();
        }
        try {
            return mapper.readTree(json);
        } catch (RuntimeException ex) {
            throw new McpBadRequestException(field + " must be JSON");
        }
    }

    static ObjectNode headersNode(JsonMapper mapper, Map<String, String> headers) {
        ObjectNode node = mapper.createObjectNode();
        if (headers == null) {
            return node;
        }
        headers.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null) {
                return;
            }
            try {
                ToolArgDefinitions.requireSafeHeader(name, value);
            } catch (IllegalArgumentException ex) {
                throw new McpBadRequestException(ex.getMessage());
            }
            node.put(name, value);
        });
        return node;
    }

    static String writeHeaders(JsonMapper mapper, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        return mapper.writeValueAsString(headersNode(mapper, headers));
    }

    static Map<String, String> readHeaders(JsonMapper mapper, String json) {
        JsonNode node = readTree(mapper, json, "httpHeaders");
        Map<String, String> headers = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return headers;
        }
        node.properties().forEach(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                headers.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return headers;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readArgs(JsonMapper mapper, String json) {
        JsonNode node = readTree(mapper, json, "args");
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<Map<String, Object>> args = mapper.convertValue(node, ArrayList.class);
        return args == null ? List.of() : List.copyOf(args);
    }

    static JsonNode argsNode(JsonMapper mapper, Object args) {
        if (args == null) {
            return mapper.createArrayNode();
        }
        if (args instanceof JsonNode node) {
            return node;
        }
        return mapper.valueToTree(args);
    }
}
