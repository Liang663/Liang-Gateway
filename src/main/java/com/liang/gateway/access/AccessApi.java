package com.liang.gateway.access;

import reactor.core.publisher.Mono;

public interface AccessApi {

    Mono<Void> checkQuota(String tokenCode);

    Mono<Void> recordUsage(String tokenCode, long promptTokens, long completionTokens, UsageMeta meta);

    Mono<QuotaView> getQuota(String tokenCode);

    Mono<Void> resetQuota(String tokenCode, QuotaLayer layer);
}
