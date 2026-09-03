package com.liang.gateway.access;

public record UsageMeta(String model, String requestId) {

    public static UsageMeta empty() {
        return new UsageMeta(null, null);
    }
}
