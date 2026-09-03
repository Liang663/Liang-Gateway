package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.internal.infrastructure.redis.WindowSnapshot;
import java.time.Instant;
import reactor.core.publisher.Mono;

public interface QuotaWindowStore {

    Mono<Void> openWindows(String tokenCode, Instant now);

    Mono<WindowSnapshot> refresh(String tokenCode, QuotaLayer layer, Instant now);

    Mono<WindowSnapshot> refreshAndIncrement(String tokenCode, QuotaLayer layer, long tokens, Instant now);

    Mono<WindowSnapshot> reset(String tokenCode, QuotaLayer layer, Instant now);

    Mono<Long> incrementQpm(String tokenCode, String yyyyMMddHHmm);
}
