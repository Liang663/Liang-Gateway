package com.liang.gateway.ai.internal.application;

import java.time.LocalDateTime;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Persistence port for bounded, database-side administrative queries. */
public interface LlmAdminQueryStore {
    Mono<Summary> summary(LocalDateTime from, LocalDateTime to);
    Flux<Bucket> trend(LocalDateTime from, LocalDateTime to);
    Flux<ModelCount> models(LocalDateTime from, LocalDateTime to);
    Mono<Long> count(Filter filter);
    Flux<LogRow> logs(Filter filter, long offset, int limit);

    record Summary(long total, long successCount, Double averageFirstTokenMs) {}
    record Bucket(LocalDateTime time, long count) {}
    record ModelCount(String model, long count) {}
    record Filter(LocalDateTime from, LocalDateTime to, String model, String apikeyCode, Boolean success) {}
    record LogRow(String code, String llmApikeyCode, String model, boolean success, String message,
                  Integer firstTokenMs, int totalDurationMs, LocalDateTime createTime) {}
}
