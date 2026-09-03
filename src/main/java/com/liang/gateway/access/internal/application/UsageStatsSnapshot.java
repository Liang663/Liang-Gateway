package com.liang.gateway.access.internal.application;

public record UsageStatsSnapshot(String range, long promptTokens, long completionTokens, long totalTokens) {}
