package com.liang.gateway.access;

import java.util.List;
import reactor.core.publisher.Mono;

public interface AccessApi {

    Mono<Void> checkQuota(String tokenCode);

    Mono<Void> assertModelAllowed(String tokenCode, String model);

    Mono<List<String>> listAllowedModels(String tokenCode);

    Mono<Void> recordUsage(
            String tokenCode,
            long promptTokens,
            long completionTokens,
            long amountFen,
            String model,
            UsageMeta meta);

    Mono<QuotaView> getQuota(String tokenCode);

    Mono<Void> resetQuota(String tokenCode, QuotaLayer layer);
}
