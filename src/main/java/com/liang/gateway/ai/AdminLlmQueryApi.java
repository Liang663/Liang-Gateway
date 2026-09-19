package com.liang.gateway.ai;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import reactor.core.publisher.Mono;

/** Read-only administrator queries. Local timestamps in results use Asia/Shanghai. */
public interface AdminLlmQueryApi {
    Mono<Stats> stats(String range);

    Mono<LogPage> callLogs(LogQuery query);

    record LogQuery(int page, int pageSize, OffsetDateTime from, OffsetDateTime to,
                    String model, String apikeyCode, Boolean success) {}

    record Stats(long total, long successCount, double successRate, Double averageFirstTokenMs,
                 List<TrendPoint> trend, List<ModelCount> models) {}

    record TrendPoint(LocalDateTime time, long count) {}

    record ModelCount(String model, long count) {}

    record LogItem(String code, String llmApikeyCode, String model, boolean success, String message,
                   Integer firstTokenMs, int totalDurationMs, LocalDateTime createTime) {}

    record LogPage(List<LogItem> items, long total, int page, int pageSize) {}
}
