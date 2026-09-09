package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageRecordRepository;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class UsageQueryService {

    private final UsageRecordRepository usageRecordRepository;
    private final AccessClock accessClock;

    public UsageQueryService(UsageRecordRepository usageRecordRepository, AccessClock accessClock) {
        this.usageRecordRepository = usageRecordRepository;
        this.accessClock = accessClock;
    }

    public Mono<UsageStatsSnapshot> stats(String tokenCode, String range) {
        String normalized = range == null ? "" : range.toLowerCase(Locale.ROOT);
        LocalDateTime to = accessClock.nowShanghai();
        Mono<UsageTotals> totals;
        Flux<ModelUsageTotals> byModel;
        switch (normalized) {
            case "hour", "day", "week", "month" -> {
                LocalDateTime from = rangeStart(normalized);
                totals = usageRecordRepository.sumBetween(tokenCode, from, to);
                byModel = usageRecordRepository.sumByModelBetween(tokenCode, from, to);
            }
            case "total" -> {
                totals = usageRecordRepository.sumAll(tokenCode);
                byModel = usageRecordRepository.sumByModelAll(tokenCode);
            }
            default -> {
                return Mono.error(new AccessBadRequestException("Unsupported range"));
            }
        }
        return Mono.zip(totals.defaultIfEmpty(UsageTotals.zero()), byModel.collectList())
                .map(tuple -> new UsageStatsSnapshot(
                        normalized,
                        tuple.getT1().promptTokens(),
                        tuple.getT1().completionTokens(),
                        tuple.getT1().totalTokens(),
                        tuple.getT1().amountFen(),
                        tuple.getT2()));
    }

    public Mono<List<UsageRecordSnapshot>> records(String tokenCode, int limit) {
        int pageSize = limit <= 0 ? 100 : Math.min(limit, 500);
        return usageRecordRepository
                .findByTokenCodeOrderByCreateTimeDesc(tokenCode, PageRequest.of(0, pageSize))
                .map(entity -> new UsageRecordSnapshot(
                        entity.getCode(),
                        entity.getPromptTokens(),
                        entity.getCompletionTokens(),
                        entity.getTotalTokens(),
                        entity.getAmountFen(),
                        entity.getModel(),
                        entity.getRequestId(),
                        entity.getCreateTime()))
                .collectList();
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
