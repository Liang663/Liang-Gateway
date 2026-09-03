package com.liang.gateway.ai.internal.application;

import java.time.LocalDateTime;

public record ApikeySnapshot(
        String code,
        String name,
        String provider,
        String baseUrl,
        String prefix,
        boolean enabled,
        LocalDateTime expireTime,
        LocalDateTime createTime,
        LocalDateTime updateTime) {}
