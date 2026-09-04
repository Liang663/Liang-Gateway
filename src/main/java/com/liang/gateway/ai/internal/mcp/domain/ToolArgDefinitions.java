package com.liang.gateway.ai.internal.mcp.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToolArgDefinitions {

    public static final Set<String> VALUE_TYPES =
            Set.of("string", "number", "integer", "boolean", "object", "array");
    public static final Set<String> POSITIONS = Set.of("path", "query", "header", "body");
    public static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9._-]+)\\}");
    private static final Set<String> SCHEMA_SKIP_FIELDS = Set.of("name", "value_type", "required", "position");

    private ToolArgDefinitions() {}

    public static List<JsonNode> requireArray(JsonNode args) {
        if (args == null || args.isNull() || args.isMissingNode()) {
            return List.of();
        }
        if (!args.isArray()) {
            throw new IllegalArgumentException("args must be a JSON array");
        }
        List<JsonNode> elements = new ArrayList<>();
        args.forEach(elements::add);
        return List.copyOf(elements);
    }

    public static void validate(JsonNode args, String httpUrl) {
        List<JsonNode> elements = requireArray(args);
        Set<String> names = new LinkedHashSet<>();
        Set<String> pathNames = new LinkedHashSet<>();
        for (JsonNode element : elements) {
            if (element == null || !element.isObject()) {
                throw new IllegalArgumentException("args element must be an object");
            }
            String name = text(element, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("args.name is required");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate args.name: " + name);
            }
            String valueType = text(element, "value_type");
            if (!VALUE_TYPES.contains(valueType)) {
                throw new IllegalArgumentException("unsupported value_type: " + valueType);
            }
            String position = text(element, "position");
            if (!POSITIONS.contains(position)) {
                throw new IllegalArgumentException("unsupported position: " + position);
            }
            boolean required = element.path("required").asBoolean(false);
            if ("path".equals(position)) {
                if (!required) {
                    throw new IllegalArgumentException("path argument must be required: " + name);
                }
                pathNames.add(name);
            }
            if ("header".equals(position)) {
                requireSafeHeader(name, "");
            }
        }
        Set<String> placeholders = new LinkedHashSet<>(placeholders(httpUrl));
        if (!placeholders.equals(pathNames)) {
            throw new IllegalArgumentException("path arguments must match http_url placeholders");
        }
    }

    public static List<String> placeholders(String httpUrl) {
        Matcher matcher = PLACEHOLDER.matcher(httpUrl == null ? "" : httpUrl);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    public static boolean hasUnresolvedPlaceholder(String url) {
        return PLACEHOLDER.matcher(url == null ? "" : url).find();
    }

    public static Map<String, Object> inputSchema(JsonNode args, JsonMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        ArrayNode required = mapper.createArrayNode();
        for (JsonNode element : requireArray(args)) {
            String name = text(element, "name");
            ObjectNode property = mapper.createObjectNode();
            property.put("type", text(element, "value_type"));
            element.properties().forEach(entry -> {
                if (!SCHEMA_SKIP_FIELDS.contains(entry.getKey())) {
                    JsonNode value = entry.getValue();
                    if ("description".equals(entry.getKey())
                            && (value == null || value.isNull() || value.asText("").isBlank())) {
                        return;
                    }
                    property.set(entry.getKey(), value);
                }
            });
            properties.set(name, property);
            if (element.path("required").asBoolean(false)) {
                required.add(name);
            }
        }
        schema.set("properties", properties);
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return jsonMap(schema, mapper);
    }

    public static String canonicalize(JsonNode args, JsonMapper mapper) {
        JsonNode node = args == null || args.isNull() || args.isMissingNode() ? mapper.createArrayNode() : args;
        return mapper.writeValueAsString(node);
    }

    public static String uppercaseMethod(String httpMethod) {
        if (httpMethod == null || httpMethod.isBlank()) {
            throw new IllegalArgumentException("httpMethod is required");
        }
        String method = httpMethod.trim().toUpperCase();
        if (!HTTP_METHODS.contains(method)) {
            throw new IllegalArgumentException("unsupported httpMethod: " + httpMethod);
        }
        return method;
    }

    public static void requireSafeHeader(String name, String value) {
        if (HeaderFieldSafety.isUnsafe(name, value)) {
            throw new IllegalArgumentException("httpHeaders must not contain CR, LF or NUL");
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMap(JsonNode node, JsonMapper mapper) {
        Map<String, Object> map = mapper.convertValue(node, LinkedHashMap.class);
        return map == null ? Map.of() : Map.copyOf(map);
    }
}
