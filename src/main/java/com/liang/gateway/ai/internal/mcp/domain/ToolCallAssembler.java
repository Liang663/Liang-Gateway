package com.liang.gateway.ai.internal.mcp.domain;

import com.liang.gateway.ai.McpUpstream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.web.util.UriUtils;

public final class ToolCallAssembler {

    private ToolCallAssembler() {}

    public static Result assemble(
            String httpUrl,
            String httpMethod,
            JsonNode staticHeaders,
            int timeoutMs,
            JsonNode args,
            JsonNode arguments,
            JsonMapper mapper) {
        List<JsonNode> definitions = ToolArgDefinitions.requireArray(args);
        ObjectNode provided = asObject(arguments, mapper);
        String unknown = firstUnknown(definitions, provided);
        if (unknown != null) {
            return Result.error(McpFailureTexts.unknownArgument(unknown));
        }
        for (JsonNode definition : definitions) {
            String name = ToolArgDefinitions.text(definition, "name");
            boolean required = definition.path("required").asBoolean(false);
            JsonNode value = provided.get(name);
            if (isAbsent(value)) {
                if (required) {
                    return Result.error(McpFailureTexts.missingArgument(name));
                }
                continue;
            }
            String valueType = ToolArgDefinitions.text(definition, "value_type");
            if (!matchesType(value, valueType)) {
                return Result.error(McpFailureTexts.argumentType(name));
            }
        }

        Map<String, String> headers = readStaticHeaders(staticHeaders);
        List<String> queryParts = new ArrayList<>();
        ObjectNode body = mapper.createObjectNode();
        boolean hasBodyArg = false;
        String url = httpUrl == null ? "" : httpUrl;

        for (JsonNode definition : definitions) {
            String name = ToolArgDefinitions.text(definition, "name");
            String position = ToolArgDefinitions.text(definition, "position");
            JsonNode value = provided.get(name);
            if ("body".equals(position)) {
                hasBodyArg = true;
            }
            if (isAbsent(value)) {
                continue;
            }
            switch (position) {
                case "path" -> {
                    if (!url.contains("{" + name + "}")) {
                        return Result.error(McpFailureTexts.pathMismatch());
                    }
                    url = url.replace("{" + name + "}", encodePath(stringify(value)));
                }
                case "query" -> queryParts.add(encodeQuery(name) + "=" + encodeQuery(stringify(value)));
                case "header" -> {
                    String headerValue = stringify(value);
                    if (HeaderFieldSafety.isUnsafe(name, headerValue)) {
                        return Result.error(McpFailureTexts.argumentType(name));
                    }
                    putHeader(headers, name, headerValue);
                }
                case "body" -> body.set(name, value);
                default -> {
                    return Result.error(McpFailureTexts.argumentType(name));
                }
            }
        }
        if (ToolArgDefinitions.hasUnresolvedPlaceholder(url)) {
            return Result.error(McpFailureTexts.pathMismatch());
        }
        if (!queryParts.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + String.join("&", queryParts);
        }
        byte[] bodyBytes = new byte[0];
        if (hasBodyArg) {
            bodyBytes = mapper.writeValueAsBytes(body);
        }
        return Result.ready(new McpUpstream(url, httpMethod, headers, bodyBytes, timeoutMs));
    }

    private static String firstUnknown(List<JsonNode> definitions, ObjectNode provided) {
        TreeSet<String> declared = new TreeSet<>();
        for (JsonNode definition : definitions) {
            declared.add(ToolArgDefinitions.text(definition, "name"));
        }
        TreeSet<String> extras = new TreeSet<>();
        provided.propertyNames().forEach(name -> {
            if (!declared.contains(name)) {
                extras.add(name);
            }
        });
        return extras.isEmpty() ? null : extras.getFirst();
    }

    private static ObjectNode asObject(JsonNode arguments, JsonMapper mapper) {
        if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
            return mapper.createObjectNode();
        }
        if (arguments.isObject()) {
            return (ObjectNode) arguments;
        }
        return mapper.createObjectNode();
    }

    private static boolean isAbsent(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull();
    }

    private static boolean matchesType(JsonNode value, String valueType) {
        return switch (valueType) {
            case "string" -> value.isString() || value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isNumber() && value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> false;
        };
    }

    private static Map<String, String> readStaticHeaders(JsonNode headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null || headers.isNull() || !headers.isObject()) {
            return result;
        }
        headers.properties().forEach(entry -> {
            if (entry.getValue() == null || entry.getValue().isNull()) {
                return;
            }
            String value = stringify(entry.getValue());
            if (HeaderFieldSafety.isUnsafe(entry.getKey(), value)) {
                throw new IllegalArgumentException("header is invalid: " + entry.getKey());
            }
            result.put(entry.getKey(), value);
        });
        return result;
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        String existing = headers.keySet().stream()
                .filter(key -> key.equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            headers.remove(existing);
        }
        headers.put(name, value);
    }

    private static String stringify(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isString() || value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return value.toString();
    }

    private static String encodePath(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private static String encodeQuery(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }

    public record Result(McpUpstream upstream, String errorText) {
        public static Result ready(McpUpstream upstream) {
            return new Result(upstream, null);
        }

        public static Result error(String errorText) {
            return new Result(null, errorText);
        }

        public boolean failed() {
            return errorText != null;
        }
    }
}
