package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.AdminUsageQueryApi;
import java.time.LocalDateTime;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AdminUsageQueryStore {

    Mono<AdminUsageQueryApi.Stats> aggregate(Filter filter);

    Mono<Long> count(Filter filter);

    Flux<AdminUsageQueryApi.Record> records(Filter filter, long offset, int limit);

    record Filter(LocalDateTime from, LocalDateTime to, String userCode, String tokenCode, String model) {}
}
