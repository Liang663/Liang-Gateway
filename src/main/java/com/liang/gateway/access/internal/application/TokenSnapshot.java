package com.liang.gateway.access.internal.application;

import java.time.LocalDateTime;

public record TokenSnapshot(
        String code,
        String userCode,
        String accessToken,
        String apikeyCode,
        boolean enabled,
        LocalDateTime expireTime,
        int qpmLimit,
        long hourlyTokenLimit,
        long weeklyTokenLimit,
        LocalDateTime createTime,
        LocalDateTime updateTime) {}
