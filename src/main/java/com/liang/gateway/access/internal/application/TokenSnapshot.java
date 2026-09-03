package com.liang.gateway.access.internal.application;

import java.time.LocalDateTime;
import java.util.List;

public record TokenSnapshot(
        String code,
        String userCode,
        String accessToken,
        boolean enabled,
        LocalDateTime expireTime,
        int qpmLimit,
        List<String> models,
        List<UsageLimitSnapshot> limits,
        LocalDateTime createTime,
        LocalDateTime updateTime) {}
