package com.liang.gateway.access.internal.infrastructure.redis;

import com.liang.gateway.access.QuotaLayer;

public final class QuotaKeys {

    private final String prefix;

    public QuotaKeys(String prefix) {
        this.prefix = prefix;
    }

    public String window(String tokenCode, QuotaLayer layer) {
        return prefix + ":{" + tokenCode + "}:" + layer.keySuffix();
    }

    public String qpm(String tokenCode, String yyyyMMddHHmm) {
        return prefix + ":{" + tokenCode + "}:qpm:" + yyyyMMddHHmm;
    }
}
