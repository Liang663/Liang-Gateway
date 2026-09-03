package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.QuotaStoreUnavailableException;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.IdentityCodes;
import com.liang.gateway.access.internal.infrastructure.jpa.JpaExecutor;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class TokenAdminService {

    private final JpaExecutor jpaExecutor;
    private final UserAccessTokenRepository tokenRepository;
    private final UserAdminService userAdminService;
    private final QuotaWindowStore quotaWindowStore;
    private final AccessClock accessClock;
    private final JdbcTemplate jdbcTemplate;

    public TokenAdminService(
            JpaExecutor jpaExecutor,
            UserAccessTokenRepository tokenRepository,
            UserAdminService userAdminService,
            QuotaWindowStore quotaWindowStore,
            AccessClock accessClock,
            JdbcTemplate jdbcTemplate) {
        this.jpaExecutor = jpaExecutor;
        this.tokenRepository = tokenRepository;
        this.userAdminService = userAdminService;
        this.quotaWindowStore = quotaWindowStore;
        this.accessClock = accessClock;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Mono<TokenSnapshot> create(
            String userCode,
            String apikeyCode,
            int qpmLimit,
            long hourlyTokenLimit,
            long weeklyTokenLimit,
            boolean enabled,
            LocalDateTime expireTime) {
        return userAdminService
                .requireUser(userCode)
                .then(jpaExecutor.call(() -> persist(
                        userCode, apikeyCode, qpmLimit, hourlyTokenLimit, weeklyTokenLimit, enabled, expireTime)))
                .flatMap(saved -> quotaWindowStore
                        .openWindows(saved.getCode(), accessClock.instant())
                        .thenReturn(toSnapshot(saved))
                        .onErrorResume(error -> jpaExecutor
                                .run(() -> tokenRepository.deleteByCode(saved.getCode()))
                                .then(Mono.error(asUnavailable(error)))));
    }

    public Mono<List<TokenSnapshot>> list(String userCode) {
        return userAdminService.requireUser(userCode).then(jpaExecutor.call(() -> tokenRepository
                .findByUserCodeOrderByCreateTimeDesc(userCode)
                .stream()
                .map(TokenAdminService::toSnapshot)
                .toList()));
    }

    public Mono<TokenSnapshot> get(String userCode, String tokenCode) {
        return loadOwned(userCode, tokenCode).map(TokenAdminService::toSnapshot);
    }

    public Mono<TokenSnapshot> update(
            String userCode,
            String tokenCode,
            Boolean enabled,
            LocalDateTime expireTime,
            boolean expireTimePresent,
            int qpmLimit,
            long hourlyTokenLimit,
            long weeklyTokenLimit) {
        return loadOwned(userCode, tokenCode).flatMap(entity -> jpaExecutor.call(() -> {
            boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
            LocalDateTime nextExpire = expireTimePresent ? expireTime : entity.getExpireTime();
            entity.update(
                    nextEnabled, nextExpire, qpmLimit, hourlyTokenLimit, weeklyTokenLimit, accessClock.nowShanghai());
            return toSnapshot(tokenRepository.save(entity));
        }));
    }

    public Mono<Void> resetQuota(String userCode, String tokenCode, QuotaLayer layer) {
        return loadOwned(userCode, tokenCode)
                .then(quotaWindowStore.reset(tokenCode, layer, accessClock.instant()))
                .then();
    }

    private Mono<UserAccessTokenEntity> loadOwned(String userCode, String tokenCode) {
        return userAdminService.requireUser(userCode).then(jpaExecutor.call(() -> tokenRepository.findByCode(tokenCode)))
                .flatMap(optional -> {
                    if (optional.isEmpty() || !userCode.equals(optional.get().getUserCode())) {
                        return Mono.error(new AccessNotFoundException("Access token not found"));
                    }
                    return Mono.just(optional.get());
                });
    }

    private UserAccessTokenEntity persist(
            String userCode,
            String apikeyCode,
            int qpmLimit,
            long hourlyTokenLimit,
            long weeklyTokenLimit,
            boolean enabled,
            LocalDateTime expireTime) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from llm_apikey_config where code = ?", Integer.class, apikeyCode);
        if (count == null || count == 0) {
            throw new AccessBadRequestException("Unknown apikey_code");
        }
        return tokenRepository.save(UserAccessTokenEntity.create(
                IdentityCodes.tokenCode(),
                userCode,
                IdentityCodes.accessToken(),
                apikeyCode,
                enabled,
                expireTime,
                qpmLimit,
                hourlyTokenLimit,
                weeklyTokenLimit,
                accessClock.nowShanghai()));
    }

    private static Throwable asUnavailable(Throwable error) {
        if (error instanceof QuotaStoreUnavailableException) {
            return error;
        }
        return new QuotaStoreUnavailableException("Quota store unavailable", error);
    }

    static TokenSnapshot toSnapshot(UserAccessTokenEntity entity) {
        return new TokenSnapshot(
                entity.getCode(),
                entity.getUserCode(),
                entity.getAccessToken(),
                entity.getApikeyCode(),
                entity.isEnabled(),
                entity.getExpireTime(),
                entity.getQpmLimit(),
                entity.getHourlyTokenLimit(),
                entity.getWeeklyTokenLimit(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }
}
