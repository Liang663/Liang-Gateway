package com.liang.gateway.access.internal.application;

import java.time.LocalDateTime;

public record UsageRecordSnapshot(
        String code,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        String model,
        String requestId,
        LocalDateTime createTime) {}
