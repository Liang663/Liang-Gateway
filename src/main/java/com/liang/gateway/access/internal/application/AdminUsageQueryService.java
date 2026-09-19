package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.AccessApi;
import com.liang.gateway.access.AdminUsageQueryApi;
import com.liang.gateway.access.QuotaView;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AdminUsageQueryService implements AdminUsageQueryApi {

    private final AdminUsageQueryStore store;
    private final AccessClock clock;
    private final TokenAdminService tokens;
    private final AccessApi accessApi;

    public AdminUsageQueryService(AdminUsageQueryStore store, AccessClock clock,
            TokenAdminService tokens, AccessApi accessApi) {
        this.store = store;
        this.clock = clock;
        this.tokens = tokens;
        this.accessApi = accessApi;
    }

    @Override
    public Mono<Stats> stats(String range, String userCode, String tokenCode, String model) {
        return Mono.defer(() -> {
            LocalDateTime to = clock.nowShanghai();
            LocalDateTime from = switch (range == null ? "24h" : range) {
                case "24h" -> to.minusHours(24);
                case "7d" -> to.minusDays(7);
                default -> throw new AccessBadRequestException("range must be 24h or 7d");
            };
            return store.aggregate(filter(from, to, userCode, tokenCode, model));
        });
    }

    @Override
    public Mono<Page> records(RecordQuery query) {
        return Mono.defer(() -> {
            if (query.page() < 1 || query.pageSize() < 1) {
                return Mono.error(new AccessBadRequestException("page and pageSize must be positive"));
            }
            int pageSize = Math.min(query.pageSize(), 100);
            LocalDateTime to = query.to() == null ? clock.nowShanghai() : shanghai(query.to());
            LocalDateTime from = query.from() == null ? to.minusHours(24) : shanghai(query.from());
            if (!from.isBefore(to)) {
                return Mono.error(new AccessBadRequestException("from must be before to"));
            }
            var filter = filter(from, to, query.userCode(), query.tokenCode(), query.model());
            long offset = (long) (query.page() - 1) * pageSize;
            return Mono.zip(store.records(filter, offset, pageSize).collectList(), store.count(filter))
                    .map(result -> new Page(result.getT1(), result.getT2(), query.page(), pageSize));
        });
    }

    @Override
    public Mono<QuotaView> quota(String userCode, String tokenCode) {
        return tokens.get(userCode, tokenCode).flatMap(owned -> accessApi.getQuota(owned.code()));
    }

    private static LocalDateTime shanghai(OffsetDateTime time) {
        return time.atZoneSameInstant(AccessClock.SHANGHAI).toLocalDateTime();
    }

    private static AdminUsageQueryStore.Filter filter(LocalDateTime from, LocalDateTime to,
            String userCode, String tokenCode, String model) {
        return new AdminUsageQueryStore.Filter(from, to, optional(userCode), optional(tokenCode), optional(model));
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
