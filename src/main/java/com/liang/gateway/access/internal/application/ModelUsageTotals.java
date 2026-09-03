package com.liang.gateway.access.internal.application;

public record ModelUsageTotals(
        String model, long promptTokens, long completionTokens, long totalTokens, long amountFen) {}
