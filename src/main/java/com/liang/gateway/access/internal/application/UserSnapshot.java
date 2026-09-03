package com.liang.gateway.access.internal.application;

import java.time.LocalDateTime;

public record UserSnapshot(
        String code,
        String name,
        String authority,
        boolean enabled,
        LocalDateTime createTime,
        LocalDateTime updateTime) {}
