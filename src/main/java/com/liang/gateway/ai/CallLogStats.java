package com.liang.gateway.ai;

public record CallLogStats(long total, long successCount, Double averageFirstTokenMs, double failureRate) {}
