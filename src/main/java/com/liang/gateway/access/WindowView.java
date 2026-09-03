package com.liang.gateway.access;

import java.time.Instant;

public record WindowView(long used, long limit, Instant windowStart, Instant windowEnd) {}
