package com.liang.gateway.access;

public enum QuotaLayer {
    FIVE_HOUR(18_000L, "5h"),
    WEEK(604_800L, "week");

    private final long durationSeconds;
    private final String keySuffix;

    QuotaLayer(long durationSeconds, String keySuffix) {
        this.durationSeconds = durationSeconds;
        this.keySuffix = keySuffix;
    }

    public long durationSeconds() {
        return durationSeconds;
    }

    public String keySuffix() {
        return keySuffix;
    }
}
