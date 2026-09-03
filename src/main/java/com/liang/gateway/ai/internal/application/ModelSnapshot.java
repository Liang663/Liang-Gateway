package com.liang.gateway.ai.internal.application;

import java.time.LocalDateTime;

public record ModelSnapshot(
        String code,
        String name,
        String provider,
        String apikeyCode,
        long inputPriceFenPerMillion,
        long outputPriceFenPerMillion,
        boolean enabled,
        LocalDateTime createTime,
        LocalDateTime updateTime) {}
