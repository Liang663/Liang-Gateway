package com.liang.gateway.access.internal.application;

public record UsageLimitSnapshot(int limitType, long usage, long used) {}
