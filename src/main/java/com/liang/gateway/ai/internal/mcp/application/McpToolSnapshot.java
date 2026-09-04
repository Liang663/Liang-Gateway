package com.liang.gateway.ai.internal.mcp.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record McpToolSnapshot(
        String code,
        String serverCode,
        String name,
        String description,
        String httpUrl,
        String httpMethod,
        Map<String, String> httpHeaders,
        int timeoutMs,
        List<Map<String, Object>> args,
        boolean enabled,
        LocalDateTime createTime,
        LocalDateTime updateTime) {}
