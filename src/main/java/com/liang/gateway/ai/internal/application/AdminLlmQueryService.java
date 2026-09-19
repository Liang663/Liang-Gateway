package com.liang.gateway.ai.internal.application;

import com.liang.gateway.ai.AdminLlmQueryApi;
import com.liang.gateway.ai.AiBadRequestException;
import com.liang.gateway.ai.internal.infrastructure.AiClock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AdminLlmQueryService implements AdminLlmQueryApi {
    private final LlmAdminQueryStore store;
    private final AiClock clock;

    public AdminLlmQueryService(LlmAdminQueryStore store, AiClock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Mono<Stats> stats(String range) {
        return Mono.defer(() -> {
            if (!"24h".equals(range) && !"7d".equals(range)) {
                return Mono.error(new AiBadRequestException("range must be 24h or 7d"));
            }
            LocalDateTime to = clock.nowShanghai();
            LocalDateTime from = to.minusHours("7d".equals(range) ? 168 : 24);
            return Mono.zip(store.summary(from, to), store.trend(from, to).collectList(),
                            store.models(from, to).collectList())
                    .map(result -> {
                        var summary = result.getT1();
                        Map<LocalDateTime, Long> counts = result.getT2().stream().collect(Collectors.toMap(
                                LlmAdminQueryStore.Bucket::time, LlmAdminQueryStore.Bucket::count));
                        var trend = new ArrayList<TrendPoint>();
                        for (LocalDateTime hour = from.truncatedTo(ChronoUnit.HOURS);
                                hour.isBefore(to); hour = hour.plusHours(1)) {
                            trend.add(new TrendPoint(hour, counts.getOrDefault(hour, 0L)));
                        }
                        return new Stats(summary.total(), summary.successCount(),
                                summary.total() == 0 ? 0 : (double) summary.successCount() / summary.total(),
                                summary.averageFirstTokenMs(), trend,
                                result.getT3().stream().map(m -> new ModelCount(m.model(), m.count())).toList());
                    });
        });
    }

    @Override
    public Mono<LogPage> callLogs(LogQuery query) {
        return Mono.defer(() -> {
            if (query.page() < 1 || query.pageSize() < 1) {
                return Mono.error(new AiBadRequestException("page and pageSize must be positive"));
            }
            LocalDateTime to = query.to() == null ? clock.nowShanghai()
                    : LocalDateTime.ofInstant(query.to().toInstant(), AiClock.SHANGHAI);
            LocalDateTime from = query.from() == null ? to.minusHours(24)
                    : LocalDateTime.ofInstant(query.from().toInstant(), AiClock.SHANGHAI);
            if (!from.isBefore(to)) {
                return Mono.error(new AiBadRequestException("from must be earlier than to"));
            }
            int size = Math.min(query.pageSize(), 100);
            var filter = new LlmAdminQueryStore.Filter(from, to, trim(query.model()),
                    trim(query.apikeyCode()), query.success());
            return Mono.zip(store.count(filter), store.logs(filter, (long) (query.page() - 1) * size, size)
                            .map(row -> new LogItem(row.code(), row.llmApikeyCode(), row.model(), row.success(),
                                    row.message(), row.firstTokenMs(), row.totalDurationMs(), row.createTime()))
                            .collectList())
                    .map(result -> new LogPage(result.getT2(), result.getT1(), query.page(), size));
        });
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
