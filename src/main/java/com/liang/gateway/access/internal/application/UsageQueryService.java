package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.jpa.JpaExecutor;
import com.liang.gateway.access.internal.infrastructure.jpa.UsageRecordRepository;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class UsageQueryService {

    private final JpaExecutor jpaExecutor;
    private final UsageRecordRepository usageRecordRepository;
    private final AccessClock accessClock;

    public UsageQueryService(
            JpaExecutor jpaExecutor, UsageRecordRepository usageRecordRepository, AccessClock accessClock) {
        this.jpaExecutor = jpaExecutor;
        this.usageRecordRepository = usageRecordRepository;
        this.accessClock = accessClock;
    }

    public Mono<UsageStatsSnapshot> stats(String tokenCode, String range) {
        String normalized = range == null ? "" : range.toLowerCase(Locale.ROOT);
        LocalDateTime to = accessClock.nowShanghai();
        return jpaExecutor.call(() -> {
            UsageTotals totals =
                    switch (normalized) {
                        case "hour", "day", "week", "month" -> usageRecordRepository.sumBetween(
                                tokenCode, rangeStart(normalized), to);
                        case "total" -> usageRecordRepository.sumAll(tokenCode);
                        default -> throw new AccessBadRequestException("Unsupported range");
                    };
            if (totals == null) {
                totals = UsageTotals.zero();
            }
            return new UsageStatsSnapshot(
                    normalized, totals.promptTokens(), totals.completionTokens(), totals.totalTokens());
        });
    }

    public Mono<List<UsageRecordSnapshot>> records(String tokenCode, int limit) {
        int pageSize = limit <= 0 ? 100 : Math.min(limit, 500);
        return jpaExecutor.call(() -> usageRecordRepository
                .findByTokenCodeOrderByCreateTimeDesc(tokenCode, PageRequest.of(0, pageSize))
                .stream()
                .map(entity -> new UsageRecordSnapshot(
                        entity.getCode(),
                        entity.getPromptTokens(),
                        entity.getCompletionTokens(),
                        entity.getTotalTokens(),
                        entity.getModel(),
                        entity.getRequestId(),
                        entity.getCreateTime()))
                .toList());
    }

    private LocalDateTime rangeStart(String range) {
        ZonedDateTime now = accessClock.instant().atZone(AccessClock.SHANGHAI);
        ZonedDateTime start =
                switch (range) {
                    case "hour" -> now.truncatedTo(ChronoUnit.HOURS);
                    case "day" -> now.toLocalDate().atStartOfDay(AccessClock.SHANGHAI);
                    case "week" -> now.toLocalDate()
                            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                            .atStartOfDay(AccessClock.SHANGHAI);
                    case "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(AccessClock.SHANGHAI);
                    default -> throw new AccessBadRequestException("Unsupported range");
                };
        return start.toLocalDateTime();
    }
}
