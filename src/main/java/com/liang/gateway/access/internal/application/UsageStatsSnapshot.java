package com.liang.gateway.access.internal.application;

import java.util.List;

public record UsageStatsSnapshot(
        String range,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long amountFen,
        List<ModelUsageTotals> byModel) {}
