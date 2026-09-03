package com.liang.gateway.access.internal.application;

public record UsageTotals(long promptTokens, long completionTokens, long totalTokens) {

    public static UsageTotals zero() {
        return new UsageTotals(0L, 0L, 0L);
    }
}
