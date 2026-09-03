package com.liang.gateway.ai;

public record ChatUsage(long promptTokens, long completionTokens, long amountFen) {}
