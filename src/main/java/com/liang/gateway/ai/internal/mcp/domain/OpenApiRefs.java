package com.liang.gateway.ai.internal.mcp.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Set;

final class OpenApiRefs {

    private OpenApiRefs() {}

    static JsonNode follow(JsonNode document, JsonNode node) {
        return follow(document, node, new LinkedHashSet<>());
    }

    static JsonNode materialize(JsonNode document, JsonNode node, JsonMapper mapper) {
        return materialize(document, node, mapper, new LinkedHashSet<>());
    }

    private static JsonNode follow(JsonNode document, JsonNode node, Set<String> seen) {
        JsonNode current = node;
        while (current != null && current.isObject()) {
            JsonNode refNode = current.get("$ref");
            if (refNode == null || refNode.isNull() || !refNode.isString() && !refNode.isTextual()) {
                break;
            }
            String ref = refNode.asText("");
            if (ref.isBlank()) {
                throw new IllegalArgumentException("unresolved $ref: " + ref);
            }
            if (!seen.add(ref)) {
                throw new IllegalArgumentException("cyclic $ref: " + ref);
            }
            current = pointer(document, ref);
        }
        return current;
    }

    private static JsonNode materialize(JsonNode document, JsonNode node, JsonMapper mapper, Set<String> seen) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        JsonNode resolved = follow(document, node, seen);
        if (resolved == null || resolved.isNull() || resolved.isMissingNode()) {
            return resolved;
        }
        if (resolved.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            resolved.properties().forEach(entry -> {
                if ("$ref".equals(entry.getKey())) {
                    return;
                }
                copy.set(entry.getKey(), materialize(document, entry.getValue(), mapper, new LinkedHashSet<>(seen)));
            });
            return copy;
        }
        if (resolved.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            resolved.forEach(item -> copy.add(materialize(document, item, mapper, new LinkedHashSet<>(seen))));
            return copy;
        }
        return resolved;
    }

    static JsonNode pointer(JsonNode document, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("unresolved $ref: " + ref);
        }
        if (!ref.startsWith("#")) {
            throw new IllegalArgumentException("unsupported $ref: " + ref);
        }
        String pointer = ref.substring(1);
        if (pointer.isEmpty()) {
            return document;
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("unresolved $ref: " + ref);
        }
        JsonNode current = document;
        for (String raw : pointer.substring(1).split("/", -1)) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                throw new IllegalArgumentException("unresolved $ref: " + ref);
            }
            String key = raw.replace("~1", "/").replace("~0", "~");
            if (current.isArray()) {
                try {
                    current = current.get(Integer.parseInt(key));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("unresolved $ref: " + ref);
                }
            } else if (current.isObject()) {
                current = current.get(key);
            } else {
                throw new IllegalArgumentException("unresolved $ref: " + ref);
            }
        }
        if (current == null || current.isMissingNode()) {
            throw new IllegalArgumentException("unresolved $ref: " + ref);
        }
        return current;
    }
}
