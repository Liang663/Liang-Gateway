package com.liang.gateway.ai;

import java.util.List;

public record McpCallResult(
        List<McpTextContent> content, boolean isError, String resultType, long ttlMs, String cacheScope) {

    public McpCallResult {
        content = content == null ? List.of() : List.copyOf(content);
        resultType = resultType == null ? "complete" : resultType;
        cacheScope = cacheScope == null ? "private" : cacheScope;
    }

    public static McpCallResult ok(String text) {
        return new McpCallResult(List.of(new McpTextContent(text)), false, "complete", 0L, "private");
    }

    public static McpCallResult error(String text) {
        return new McpCallResult(List.of(new McpTextContent(text)), true, "complete", 0L, "private");
    }

    public String text() {
        return content.isEmpty() ? "" : content.getFirst().text();
    }

    public record McpTextContent(String type, String text) {
        public McpTextContent {
            type = type == null ? "text" : type;
            text = text == null ? "" : text;
        }

        public McpTextContent(String text) {
            this("text", text);
        }
    }
}
