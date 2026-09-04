package com.liang.gateway.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record McpToolsListResult(List<Tool> tools, String resultType, long ttlMs, String cacheScope) {

    public McpToolsListResult {
        tools = tools == null ? List.of() : List.copyOf(tools);
        resultType = resultType == null ? "complete" : resultType;
        cacheScope = cacheScope == null ? "private" : cacheScope;
    }

    public record Tool(String name, String description, Map<String, Object> inputSchema) {
        public Tool {
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }
}
