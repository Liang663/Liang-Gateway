package com.liang.gateway.ai.internal.mcp.application;

import java.time.LocalDateTime;

public record McpServerSnapshot(
        String code,
        String name,
        String path,
        String description,
        String version,
        boolean enabled,
        LocalDateTime createTime,
        LocalDateTime updateTime) {}
