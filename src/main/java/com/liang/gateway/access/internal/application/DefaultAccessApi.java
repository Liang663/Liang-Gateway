package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.AccessApi;
import com.liang.gateway.access.QuotaExceededException;
import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.QuotaView;
import com.liang.gateway.access.UsageMeta;
import com.liang.gateway.access.WindowView;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.IdentityCodes;
import com.liang.gateway.access.internal.infrastructure.jpa.JpaExecutor;
import com.liang.gateway.access.internal.infrastructure.jpa.UsageRecordEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UsageRecordRepository;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenRepository;
import com.liang.gateway.access.internal.infrastructure.redis.WindowSnapshot;
import java.time.Instant;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DefaultAccessApi implements AccessApi {

    private final JpaExecutor jpaExecutor;
    private final UserAccessTokenRepository tokenRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final QuotaWindowStore quotaWindowStore;
    private final AccessClock accessClock;

    public DefaultAccessApi(
            JpaExecutor jpaExecutor,
            UserAccessTokenRepository tokenRepository,
            UsageRecordRepository usageRecordRepository,
            QuotaWindowStore quotaWindowStore,
            AccessClock accessClock) {
        this.jpaExecutor = jpaExecutor;
        this.tokenRepository = tokenRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.quotaWindowStore = quotaWindowStore;
        this.accessClock = accessClock;
    }

    @Override
    public Mono<Void> checkQuota(String tokenCode) {
        Instant now = accessClock.instant();
        return loadToken(tokenCode)
                .flatMap(token -> quotaWindowStore
                        .refresh(tokenCode, QuotaLayer.FIVE_HOUR, now)
                        .flatMap(fiveHour -> {
                            if (fiveHour.used() > token.getHourlyTokenLimit()) {
                                return Mono.error(new QuotaExceededException("Five-hour quota exceeded"));
                            }
                            return quotaWindowStore.refresh(tokenCode, QuotaLayer.WEEK, now);
                        })
                        .flatMap(week -> {
                            if (week.used() > token.getWeeklyTokenLimit()) {
                                return Mono.error(new QuotaExceededException("Weekly quota exceeded"));
                            }
                            return quotaWindowStore.incrementQpm(tokenCode, accessClock.qpmMinute());
                        })
                        .flatMap(qpmUsed -> {
                            if (qpmUsed > token.getQpmLimit()) {
                                return Mono.error(new QuotaExceededException("QPM quota exceeded"));
                            }
                            return Mono.empty();
                        }));
    }

    @Override
    public Mono<Void> recordUsage(String tokenCode, long promptTokens, long completionTokens, UsageMeta meta) {
        if (promptTokens < 0 || completionTokens < 0) {
            return Mono.error(new AccessBadRequestException("Token counts must not be negative"));
        }
        if (promptTokens == 0 && completionTokens == 0) {
            return Mono.empty();
        }
        long total = promptTokens + completionTokens;
        Instant now = accessClock.instant();
        UsageMeta safeMeta = meta == null ? UsageMeta.empty() : meta;
        return loadToken(tokenCode)
                .flatMap(token -> quotaWindowStore
                        .refreshAndIncrement(tokenCode, QuotaLayer.FIVE_HOUR, total, now)
                        .then(quotaWindowStore.refreshAndIncrement(tokenCode, QuotaLayer.WEEK, total, now))
                        .then(jpaExecutor.call(() -> usageRecordRepository.save(UsageRecordEntity.create(
                                IdentityCodes.usageCode(),
                                token.getCode(),
                                token.getUserCode(),
                                promptTokens,
                                completionTokens,
                                safeMeta.model(),
                                safeMeta.requestId(),
                                accessClock.nowShanghai()))))
                        .then());
    }

    @Override
    public Mono<QuotaView> getQuota(String tokenCode) {
        Instant now = accessClock.instant();
        return loadToken(tokenCode)
                .flatMap(token -> Mono.zip(
                                quotaWindowStore.refresh(tokenCode, QuotaLayer.FIVE_HOUR, now),
                                quotaWindowStore.refresh(tokenCode, QuotaLayer.WEEK, now))
                        .map(tuple -> new QuotaView(
                                toView(tuple.getT1(), token.getHourlyTokenLimit(), QuotaLayer.FIVE_HOUR),
                                toView(tuple.getT2(), token.getWeeklyTokenLimit(), QuotaLayer.WEEK))));
    }

    @Override
    public Mono<Void> resetQuota(String tokenCode, QuotaLayer layer) {
        return loadToken(tokenCode)
                .then(quotaWindowStore.reset(tokenCode, layer, accessClock.instant()))
                .then();
    }

    private Mono<UserAccessTokenEntity> loadToken(String tokenCode) {
        return jpaExecutor
                .call(() -> tokenRepository.findByCode(tokenCode))
                .flatMap(optional -> optional
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new AccessNotFoundException("Access token not found"))));
    }

    private static WindowView toView(WindowSnapshot snapshot, long limit, QuotaLayer layer) {
        Instant start = Instant.ofEpochSecond(snapshot.startEpochSeconds());
        Instant end = start.plusSeconds(layer.durationSeconds());
        return new WindowView(snapshot.used(), limit, start, end);
    }
}
