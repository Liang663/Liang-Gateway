package com.liang.gateway.ai.internal.mcp.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenApiToolImporter {

    private static final List<String> METHODS = List.of("get", "post", "put", "delete");
    private static final Pattern SERVER_VARIABLE = Pattern.compile("\\{([A-Za-z0-9._-]+)\\}");

    private OpenApiToolImporter() {}

    public static List<Draft> importSelected(JsonNode document, List<String> selectedPaths, JsonMapper mapper) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("swagger must be a JSON object");
        }
        if (selectedPaths == null || selectedPaths.isEmpty()) {
            throw new IllegalArgumentException("paths is required");
        }
        JsonNode pathsNode = document.path("paths");
        if (!pathsNode.isObject()) {
            throw new IllegalArgumentException("swagger paths is required");
        }
        String baseUrl = baseUrl(document);
        List<Draft> drafts = new ArrayList<>();
        for (String selected : selectedPaths) {
            if (selected == null || selected.isBlank()) {
                throw new IllegalArgumentException("path is required");
            }
            String path = selected.trim();
            JsonNode rawPathItem = pathsNode.get(path);
            if (rawPathItem == null || rawPathItem.isMissingNode()) {
                throw new IllegalArgumentException("unknown swagger path: " + path);
            }
            JsonNode pathItem = OpenApiRefs.follow(document, rawPathItem);
            if (pathItem == null || !pathItem.isObject()) {
                throw new IllegalArgumentException("unknown swagger path: " + path);
            }
            JsonNode pathParameters = pathItem.get("parameters");
            boolean imported = false;
            for (String method : METHODS) {
                JsonNode rawOperation = pathItem.get(method);
                if (rawOperation == null || rawOperation.isNull() || rawOperation.isMissingNode()) {
                    continue;
                }
                JsonNode operation = OpenApiRefs.follow(document, rawOperation);
                if (operation == null || !operation.isObject()) {
                    continue;
                }
                drafts.add(toDraft(document, baseUrl, path, method.toUpperCase(Locale.ROOT), operation, pathParameters, mapper));
                imported = true;
            }
            if (!imported) {
                throw new IllegalArgumentException("no GET/POST/PUT/DELETE operation for path: " + path);
            }
        }
        return List.copyOf(drafts);
    }

    private static Draft toDraft(
            JsonNode document,
            String baseUrl,
            String path,
            String httpMethod,
            JsonNode operation,
            JsonNode pathParameters,
            JsonMapper mapper) {
        String name = operationName(operation, httpMethod, path);
        String description = firstText(operation, "summary", "description");
        if (description.isBlank()) {
            description = name;
        }
        if (description.length() > 512) {
            description = description.substring(0, 512);
        }
        ArrayNode args = mapper.createArrayNode();
        Map<String, ObjectNode> byKey = new LinkedHashMap<>();
        List<JsonNode> bodySchemas = new ArrayList<>();
        collectParameters(document, pathParameters, byKey, bodySchemas, mapper);
        collectParameters(document, operation.get("parameters"), byKey, bodySchemas, mapper);
        byKey.values().forEach(args::add);
        for (JsonNode bodySchema : bodySchemas) {
            addBodyPropertiesFromSchema(bodySchema, args, mapper);
        }
        JsonNode requestBody = OpenApiRefs.follow(document, operation.get("requestBody"));
        addBodyPropertiesFromSchema(jsonBodySchema(document, requestBody, mapper), args, mapper);
        String httpUrl = joinUrl(baseUrl, path);
        ToolArgDefinitions.validate(args, httpUrl);
        return new Draft(name, description, httpUrl, httpMethod, args);
    }

    private static void collectParameters(
            JsonNode document,
            JsonNode parameters,
            Map<String, ObjectNode> byKey,
            List<JsonNode> bodySchemas,
            JsonMapper mapper) {
        if (parameters == null || !parameters.isArray()) {
            return;
        }
        for (JsonNode raw : parameters) {
            JsonNode parameter = OpenApiRefs.follow(document, raw);
            if (parameter == null || !parameter.isObject()) {
                continue;
            }
            String position = parameter.path("in").asText("");
            if ("formData".equals(position)) {
                throw new IllegalArgumentException("formData is not supported");
            }
            if ("body".equals(position)) {
                JsonNode schema = parameterSchema(document, parameter, mapper);
                if (schema != null && schema.isObject()) {
                    bodySchemas.add(schema);
                }
                continue;
            }
            if (!Set.of("path", "query", "header").contains(position)) {
                continue;
            }
            String name = parameter.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            ObjectNode arg = mapper.createObjectNode();
            arg.put("name", name);
            arg.put("position", position);
            boolean required = "path".equals(position) || parameter.path("required").asBoolean(false);
            arg.put("required", required);
            JsonNode schema = parameterSchema(document, parameter, mapper);
            arg.put("value_type", schemaType(schema != null && schema.isObject() ? schema : parameter));
            String description = firstText(parameter, "description");
            if (description.isBlank() && schema != null) {
                description = schema.path("description").asText("");
            }
            if (!description.isBlank()) {
                arg.put("description", description);
            }
            copyNestedSchema(schema != null && schema.isObject() ? schema : parameter, arg);
            byKey.put(position + ":" + name, arg);
        }
    }

    private static JsonNode parameterSchema(JsonNode document, JsonNode parameter, JsonMapper mapper) {
        JsonNode schema = parameter.get("schema");
        if (schema != null && !schema.isNull() && !schema.isMissingNode()) {
            return OpenApiRefs.materialize(document, schema, mapper);
        }
        return parameter;
    }

    private static void addBodyPropertiesFromSchema(JsonNode schema, ArrayNode args, JsonMapper mapper) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return;
        }
        List<String> required = new ArrayList<>();
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            requiredNode.forEach(item -> required.add(item.asText()));
        }
        properties.properties().forEach(entry -> {
            ObjectNode arg = mapper.createObjectNode();
            arg.put("name", entry.getKey());
            arg.put("position", "body");
            arg.put("required", required.contains(entry.getKey()));
            JsonNode property = entry.getValue();
            arg.put("value_type", schemaType(property));
            String description = property.path("description").asText("");
            if (!description.isBlank()) {
                arg.put("description", description);
            }
            copyNestedSchema(property, arg);
            args.add(arg);
        });
    }

    private static JsonNode jsonBodySchema(JsonNode document, JsonNode requestBody, JsonMapper mapper) {
        if (requestBody == null || !requestBody.isObject()) {
            return mapper.missingNode();
        }
        JsonNode content = requestBody.path("content");
        if (content.isObject()) {
            JsonNode json = content.get("application/json");
            if (json != null && json.isObject()) {
                return OpenApiRefs.materialize(document, json.path("schema"), mapper);
            }
            for (var entry : content.properties()) {
                JsonNode schema = entry.getValue().path("schema");
                if (schema != null && !schema.isMissingNode() && !schema.isNull()) {
                    return OpenApiRefs.materialize(document, schema, mapper);
                }
            }
        }
        JsonNode schema = requestBody.get("schema");
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return mapper.missingNode();
        }
        return OpenApiRefs.materialize(document, schema, mapper);
    }

    private static void copyNestedSchema(JsonNode schema, ObjectNode arg) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            arg.set("properties", properties);
        }
        JsonNode items = schema.get("items");
        if (items != null && !items.isNull()) {
            arg.set("items", items);
        }
    }

    private static String schemaType(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return "string";
        }
        String type = schema.path("type").asText("");
        if (ToolArgDefinitions.VALUE_TYPES.contains(type)) {
            return type;
        }
        if (schema.path("properties").isObject()) {
            return "object";
        }
        if (schema.has("items")) {
            return "array";
        }
        return "string";
    }

    private static String operationName(JsonNode operation, String httpMethod, String path) {
        String operationId = operation.path("operationId").asText("").trim();
        if (!operationId.isBlank()) {
            return operationId;
        }
        StringBuilder name = new StringBuilder(httpMethod.toLowerCase(Locale.ROOT));
        for (String part : path.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            String token = part.replace("{", "").replace("}", "").replace("-", "_").replace(".", "_");
            name.append('_').append(token);
        }
        return name.toString();
    }

    private static String baseUrl(JsonNode document) {
        JsonNode servers = document.get("servers");
        if (servers != null && servers.isArray() && !servers.isEmpty()) {
            JsonNode server = servers.get(0);
            String url = server.path("url").asText("").trim();
            if (!url.isBlank()) {
                return substituteServerVariables(url, server.path("variables"));
            }
        }
        String swagger = document.path("swagger").asText("");
        if (!swagger.isBlank()) {
            String host = document.path("host").asText("").trim();
            if (host.isBlank()) {
                throw new IllegalArgumentException("swagger host is required");
            }
            String scheme = "https";
            JsonNode schemes = document.get("schemes");
            if (schemes != null && schemes.isArray() && !schemes.isEmpty()) {
                scheme = schemes.get(0).asText("https");
            }
            String basePath = document.path("basePath").asText("");
            return joinUrl(scheme + "://" + host, basePath);
        }
        throw new IllegalArgumentException("servers.url is required");
    }

    static String substituteServerVariables(String url, JsonNode variables) {
        Matcher matcher = SERVER_VARIABLE.matcher(url == null ? "" : url);
        StringBuilder replaced = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            JsonNode defaultNode = variables == null ? null : variables.path(name).get("default");
            if (defaultNode == null || defaultNode.isNull() || defaultNode.asText("").isBlank()) {
                throw new IllegalArgumentException("server variable default is required: " + name);
            }
            matcher.appendReplacement(replaced, Matcher.quoteReplacement(defaultNode.asText()));
        }
        matcher.appendTail(replaced);
        return replaced.toString();
    }

    private static String joinUrl(String base, String path) {
        String left = base == null ? "" : base.trim();
        String right = path == null ? "" : path.trim();
        if (right.isEmpty()) {
            return left;
        }
        if (left.endsWith("/") && right.startsWith("/")) {
            return left.substring(0, left.length() - 1) + right;
        }
        if (!left.endsWith("/") && !right.startsWith("/") && !left.isEmpty()) {
            return left + "/" + right;
        }
        return left + right;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return "";
        }
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public record Draft(String name, String description, String httpUrl, String httpMethod, JsonNode args) {}
}
