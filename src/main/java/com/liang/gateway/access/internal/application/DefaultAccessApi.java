package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.AccessApi;
import com.liang.gateway.access.ModelForbiddenException;
import com.liang.gateway.access.QuotaExceededException;
import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.QuotaView;
import com.liang.gateway.access.UsageMeta;
import com.liang.gateway.access.WindowView;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.IdentityCodes;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageLimitEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageLimitRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageRecordEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageRecordRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenModelEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenModelRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenRepository;
import com.liang.gateway.access.internal.infrastructure.redis.WindowSnapshot;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DefaultAccessApi implements AccessApi {

    static final int LIMIT_UNLIMITED = 0;
    static final int LIMIT_FIVE_HOUR = 1;
    static final int LIMIT_WEEK = 2;

    private final UserAccessTokenRepository tokenRepository;
    private final UsageLimitRepository usageLimitRepository;
    private final UserAccessTokenModelRepository tokenModelRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final QuotaWindowStore quotaWindowStore;
    private final AccessClock accessClock;

    public DefaultAccessApi(
            UserAccessTokenRepository tokenRepository,
            UsageLimitRepository usageLimitRepository,
            UserAccessTokenModelRepository tokenModelRepository,
            UsageRecordRepository usageRecordRepository,
            QuotaWindowStore quotaWindowStore,
            AccessClock accessClock) {
        this.tokenRepository = tokenRepository;
        this.usageLimitRepository = usageLimitRepository;
        this.tokenModelRepository = tokenModelRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.quotaWindowStore = quotaWindowStore;
        this.accessClock = accessClock;
    }

    @Override
    public Mono<Void> checkQuota(String tokenCode) {
        Instant now = accessClock.instant();
        return loadContext(tokenCode).flatMap(ctx -> {
            Mono<Void> checks = Mono.empty();
            UsageLimitEntity fiveHour = ctx.limit(LIMIT_FIVE_HOUR);
            if (fiveHour != null) {
                long cap = fiveHour.getUsage();
                checks = checks.then(refreshAndPersist(tokenCode, QuotaLayer.FIVE_HOUR, now)
                        .flatMap(snapshot -> exceedIfOver(snapshot.used(), cap, "Five-hour quota exceeded")));
            }
            UsageLimitEntity week = ctx.limit(LIMIT_WEEK);
            if (week != null) {
                long cap = week.getUsage();
                checks = checks.then(refreshAndPersist(tokenCode, QuotaLayer.WEEK, now)
                        .flatMap(snapshot -> exceedIfOver(snapshot.used(), cap, "Weekly quota exceeded")));
            }
            return checks.then(quotaWindowStore.incrementQpm(tokenCode, accessClock.qpmMinute())
                    .flatMap(qpmUsed -> exceedIfOver(qpmUsed, ctx.token().getQpmLimit(), "QPM quota exceeded")));
        });
    }

    @Override
    public Mono<Void> assertModelAllowed(String tokenCode, String model) {
        if (model == null || model.isBlank()) {
            return Mono.error(new AccessBadRequestException("model is required"));
        }
        return loadToken(tokenCode)
                .then(tokenModelRepository.existsByTokenCodeAndModel(tokenCode, model))
                .flatMap(allowed -> Boolean.TRUE.equals(allowed)
                        ? Mono.empty()
                        : Mono.error(new ModelForbiddenException("Model is not allowed")));
    }

    @Override
    public Mono<List<String>> listAllowedModels(String tokenCode) {
        return loadToken(tokenCode)
                .thenMany(tokenModelRepository.findByTokenCodeOrderByModelAsc(tokenCode)
                        .map(UserAccessTokenModelEntity::getModel))
                .collectList();
    }

    @Override
    public Mono<Void> recordUsage(
            String tokenCode,
            long promptTokens,
            long completionTokens,
            long amountFen,
            String model,
            UsageMeta meta) {
        if (promptTokens < 0 || completionTokens < 0 || amountFen < 0) {
            return Mono.error(new AccessBadRequestException("Usage counts must not be negative"));
        }
        if (promptTokens == 0 && completionTokens == 0 && amountFen == 0) {
            return Mono.empty();
        }
        if (model == null || model.isBlank()) {
            return Mono.error(new AccessBadRequestException("model is required"));
        }
        Instant now = accessClock.instant();
        UsageMeta safeMeta = meta == null ? UsageMeta.empty() : meta;
        return loadContext(tokenCode).flatMap(ctx -> {
            Mono<Void> increments = Mono.empty();
            if (ctx.limit(LIMIT_FIVE_HOUR) != null) {
                increments = increments.then(incrementAndPersist(tokenCode, QuotaLayer.FIVE_HOUR, amountFen, now));
            }
            if (ctx.limit(LIMIT_WEEK) != null) {
                increments = increments.then(incrementAndPersist(tokenCode, QuotaLayer.WEEK, amountFen, now));
            }
            return increments.then(usageRecordRepository
                    .save(UsageRecordEntity.create(
                            IdentityCodes.usageCode(),
                            ctx.token().getCode(),
                            ctx.token().getUserCode(),
                            promptTokens,
                            completionTokens,
                            amountFen,
                            model,
                            safeMeta.requestId(),
                            accessClock.nowShanghai()))
                    .then());
        });
    }

    @Override
    public Mono<QuotaView> getQuota(String tokenCode) {
        Instant now = accessClock.instant();
        return loadContext(tokenCode).flatMap(ctx -> {
            Mono<WindowView> fiveHourView = windowView(ctx, LIMIT_FIVE_HOUR, QuotaLayer.FIVE_HOUR, tokenCode, now);
            Mono<WindowView> weekView = windowView(ctx, LIMIT_WEEK, QuotaLayer.WEEK, tokenCode, now);
            return Mono.zip(fiveHourView, weekView).map(tuple -> new QuotaView(tuple.getT1(), tuple.getT2()));
        });
    }

    @Override
    public Mono<Void> resetQuota(String tokenCode, QuotaLayer layer) {
        return loadToken(tokenCode)
                .then(quotaWindowStore.reset(tokenCode, layer, accessClock.instant()))
                .flatMap(snapshot -> persistUsed(tokenCode, limitType(layer), snapshot.used()))
                .then();
    }

    private Mono<WindowView> windowView(
            TokenContext ctx, int limitType, QuotaLayer layer, String tokenCode, Instant now) {
        UsageLimitEntity limit = ctx.limit(limitType);
        if (limit == null) {
            return Mono.just(new WindowView(0L, 0L, null, null));
        }
        return refreshAndPersist(tokenCode, layer, now).map(snapshot -> toView(snapshot, limit.getUsage(), layer));
    }

    private Mono<WindowSnapshot> refreshAndPersist(String tokenCode, QuotaLayer layer, Instant now) {
        return quotaWindowStore
                .refresh(tokenCode, layer, now)
                .flatMap(snapshot -> persistUsed(tokenCode, limitType(layer), snapshot.used()).thenReturn(snapshot));
    }

    private Mono<Void> incrementAndPersist(String tokenCode, QuotaLayer layer, long amountFen, Instant now) {
        return quotaWindowStore
                .refreshAndIncrement(tokenCode, layer, amountFen, now)
                .flatMap(snapshot -> persistUsed(tokenCode, limitType(layer), snapshot.used()));
    }

    private Mono<Void> persistUsed(String tokenCode, int limitType, long used) {
        return usageLimitRepository
                .findByTokenCodeAndLimitType(tokenCode, limitType)
                .flatMap(entity -> {
                    entity.updateUsed(used, accessClock.nowShanghai());
                    return usageLimitRepository.save(entity);
                })
                .then();
    }

    private Mono<TokenContext> loadContext(String tokenCode) {
        return tokenRepository
                .findByCode(tokenCode)
                .switchIfEmpty(Mono.error(new AccessNotFoundException("Access token not found")))
                .flatMap(token -> usageLimitRepository
                        .findByTokenCodeOrderByLimitTypeAsc(tokenCode)
                        .collectList()
                        .map(limits -> new TokenContext(token, limits)));
    }

    private Mono<UserAccessTokenEntity> loadToken(String tokenCode) {
        return tokenRepository
                .findByCode(tokenCode)
                .switchIfEmpty(Mono.error(new AccessNotFoundException("Access token not found")));
    }

    private static Mono<Void> exceedIfOver(long used, long limit, String message) {
        if (used > limit) {
            return Mono.error(new QuotaExceededException(message));
        }
        return Mono.empty();
    }

    private static WindowView toView(WindowSnapshot snapshot, long limit, QuotaLayer layer) {
        Instant start = Instant.ofEpochSecond(snapshot.startEpochSeconds());
        Instant end = start.plusSeconds(layer.durationSeconds());
        return new WindowView(snapshot.used(), limit, start, end);
    }

    static int limitType(QuotaLayer layer) {
        return switch (layer) {
            case FIVE_HOUR -> LIMIT_FIVE_HOUR;
            case WEEK -> LIMIT_WEEK;
        };
    }

    static QuotaLayer layerOf(int limitType) {
        return switch (limitType) {
            case LIMIT_FIVE_HOUR -> QuotaLayer.FIVE_HOUR;
            case LIMIT_WEEK -> QuotaLayer.WEEK;
            default -> null;
        };
    }

    private record TokenContext(UserAccessTokenEntity token, List<UsageLimitEntity> limits) {
        UsageLimitEntity limit(int type) {
            for (UsageLimitEntity limit : limits) {
                if (limit.getLimitType() == type) {
                    return limit;
                }
            }
            return null;
        }
    }
}
