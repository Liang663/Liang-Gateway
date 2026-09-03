package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.QuotaStoreUnavailableException;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.IdentityCodes;
import com.liang.gateway.access.internal.infrastructure.jpa.JpaExecutor;
import com.liang.gateway.access.internal.infrastructure.jpa.UsageLimitEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UsageLimitRepository;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenModelEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenModelRepository;
import com.liang.gateway.access.internal.infrastructure.jpa.UserAccessTokenRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

@Service
public class TokenAdminService {

    private final JpaExecutor jpaExecutor;
    private final UserAccessTokenRepository tokenRepository;
    private final UsageLimitRepository usageLimitRepository;
    private final UserAccessTokenModelRepository tokenModelRepository;
    private final UserAdminService userAdminService;
    private final QuotaWindowStore quotaWindowStore;
    private final AccessClock accessClock;
    private final TransactionTemplate transactionTemplate;

    public TokenAdminService(
            JpaExecutor jpaExecutor,
            UserAccessTokenRepository tokenRepository,
            UsageLimitRepository usageLimitRepository,
            UserAccessTokenModelRepository tokenModelRepository,
            UserAdminService userAdminService,
            QuotaWindowStore quotaWindowStore,
            AccessClock accessClock,
            PlatformTransactionManager transactionManager) {
        this.jpaExecutor = jpaExecutor;
        this.tokenRepository = tokenRepository;
        this.usageLimitRepository = usageLimitRepository;
        this.tokenModelRepository = tokenModelRepository;
        this.userAdminService = userAdminService;
        this.quotaWindowStore = quotaWindowStore;
        this.accessClock = accessClock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Mono<TokenSnapshot> create(
            String userCode,
            int qpmLimit,
            boolean enabled,
            LocalDateTime expireTime,
            List<String> models,
            List<UsageLimitInput> limits) {
        return Mono.defer(() -> {
            List<UsageLimitInput> validatedLimits = validateLimits(limits);
            List<String> validatedModels = validateModels(models);
            List<QuotaLayer> layers = amountLayers(validatedLimits);
            return userAdminService
                    .requireUser(userCode)
                    .then(jpaExecutor.call(() -> persist(
                            userCode, qpmLimit, enabled, expireTime, validatedModels, validatedLimits)))
                    .flatMap(saved -> quotaWindowStore
                            .openWindows(saved.getCode(), layers, accessClock.instant())
                            .then(jpaExecutor.call(() -> toSnapshot(saved)))
                            .onErrorResume(error -> jpaExecutor
                                    .run(() -> deleteTokenGraph(saved.getCode()))
                                    .then(Mono.error(asUnavailable(error)))));
        });
    }

    public Mono<List<TokenSnapshot>> list(String userCode) {
        return userAdminService.requireUser(userCode).then(jpaExecutor.call(() -> tokenRepository
                .findByUserCodeOrderByCreateTimeDesc(userCode)
                .stream()
                .map(this::toSnapshot)
                .toList()));
    }

    public Mono<TokenSnapshot> get(String userCode, String tokenCode) {
        return loadOwned(userCode, tokenCode).flatMap(entity -> jpaExecutor.call(() -> toSnapshot(entity)));
    }

    public Mono<TokenSnapshot> update(
            String userCode,
            String tokenCode,
            Boolean enabled,
            LocalDateTime expireTime,
            boolean expireTimePresent,
            int qpmLimit,
            List<String> models,
            List<UsageLimitInput> limits) {
        return loadOwned(userCode, tokenCode).flatMap(entity -> jpaExecutor
                .call(() -> transactionTemplate.execute(status -> {
                    boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
                    LocalDateTime nextExpire = expireTimePresent ? expireTime : entity.getExpireTime();
                    entity.update(nextEnabled, nextExpire, qpmLimit, accessClock.nowShanghai());
                    tokenRepository.save(entity);
                    LocalDateTime now = accessClock.nowShanghai();
                    if (models != null) {
                        replaceModels(entity.getCode(), validateModels(models), now);
                    }
                    List<QuotaLayer> toEnsure = List.of();
                    if (limits != null) {
                        List<UsageLimitEntity> previous =
                                usageLimitRepository.findByTokenCodeOrderByLimitTypeAsc(entity.getCode());
                        List<UsageLimitInput> validated = validateLimits(limits);
                        replaceLimits(entity.getUserCode(), entity.getCode(), validated, previous, now);
                        toEnsure = amountLayers(validated);
                    }
                    return new UpdatePersist(entity, toEnsure);
                }))
                .flatMap(result -> quotaWindowStore
                        .ensureWindows(result.entity().getCode(), result.toEnsure(), accessClock.instant())
                        .then(jpaExecutor.call(() -> toSnapshot(result.entity())))));
    }

    public Mono<Void> resetQuota(String userCode, String tokenCode, QuotaLayer layer) {
        return loadOwned(userCode, tokenCode)
                .then(quotaWindowStore.reset(tokenCode, layer, accessClock.instant()))
                .flatMap(snapshot -> jpaExecutor.run(() -> usageLimitRepository
                        .findByTokenCodeAndLimitType(tokenCode, DefaultAccessApi.limitType(layer))
                        .ifPresent(entity -> {
                            entity.updateUsed(snapshot.used(), accessClock.nowShanghai());
                            usageLimitRepository.save(entity);
                        })))
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
            int qpmLimit,
            boolean enabled,
            LocalDateTime expireTime,
            List<String> models,
            List<UsageLimitInput> limits) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = accessClock.nowShanghai();
            UserAccessTokenEntity saved = tokenRepository.save(UserAccessTokenEntity.create(
                    IdentityCodes.tokenCode(),
                    userCode,
                    IdentityCodes.accessToken(),
                    enabled,
                    expireTime,
                    qpmLimit,
                    now));
            replaceLimits(userCode, saved.getCode(), limits, List.of(), now);
            replaceModels(saved.getCode(), models, now);
            return saved;
        });
    }

    private void replaceLimits(
            String userCode,
            String tokenCode,
            List<UsageLimitInput> limits,
            List<UsageLimitEntity> previous,
            LocalDateTime now) {
        Map<Integer, Long> previousUsed = previous.stream()
                .collect(Collectors.toMap(UsageLimitEntity::getLimitType, UsageLimitEntity::getUsed, (left, right) -> left));
        usageLimitRepository.deleteByTokenCode(tokenCode);
        usageLimitRepository.flush();
        for (UsageLimitInput input : limits) {
            long used = previousUsed.getOrDefault(input.limitType(), 0L);
            usageLimitRepository.save(
                    UsageLimitEntity.create(userCode, tokenCode, input.limitType(), input.usage(), used, now));
        }
    }

    private void replaceModels(String tokenCode, List<String> models, LocalDateTime now) {
        tokenModelRepository.deleteByTokenCode(tokenCode);
        tokenModelRepository.flush();
        for (String model : models) {
            tokenModelRepository.save(UserAccessTokenModelEntity.create(tokenCode, model, now));
        }
    }

    private void deleteTokenGraph(String tokenCode) {
        transactionTemplate.executeWithoutResult(status -> {
            tokenModelRepository.deleteByTokenCode(tokenCode);
            usageLimitRepository.deleteByTokenCode(tokenCode);
            tokenRepository.deleteByCode(tokenCode);
        });
    }

    private TokenSnapshot toSnapshot(UserAccessTokenEntity entity) {
        List<String> models = tokenModelRepository.findByTokenCodeOrderByModelAsc(entity.getCode()).stream()
                .map(UserAccessTokenModelEntity::getModel)
                .toList();
        List<UsageLimitSnapshot> limits = usageLimitRepository
                .findByTokenCodeOrderByLimitTypeAsc(entity.getCode())
                .stream()
                .map(item -> new UsageLimitSnapshot(item.getLimitType(), item.getUsage(), item.getUsed()))
                .toList();
        return new TokenSnapshot(
                entity.getCode(),
                entity.getUserCode(),
                entity.getAccessToken(),
                entity.isEnabled(),
                entity.getExpireTime(),
                entity.getQpmLimit(),
                models,
                limits,
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    static List<UsageLimitInput> validateLimits(List<UsageLimitInput> limits) {
        List<UsageLimitInput> source =
                limits == null || limits.isEmpty() ? List.of(new UsageLimitInput(DefaultAccessApi.LIMIT_UNLIMITED, 0L)) : limits;
        Set<Integer> types = new HashSet<>();
        for (UsageLimitInput input : source) {
            if (input == null) {
                throw new AccessBadRequestException("Invalid usage limit");
            }
            int type = input.limitType();
            if (type != DefaultAccessApi.LIMIT_UNLIMITED
                    && type != DefaultAccessApi.LIMIT_FIVE_HOUR
                    && type != DefaultAccessApi.LIMIT_WEEK) {
                throw new AccessBadRequestException("Invalid limit_type");
            }
            if (input.usage() < 0) {
                throw new AccessBadRequestException("usage must not be negative");
            }
            if (type == DefaultAccessApi.LIMIT_UNLIMITED && input.usage() != 0) {
                throw new AccessBadRequestException("Unlimited usage must be 0");
            }
            if (!types.add(type)) {
                throw new AccessBadRequestException("Duplicate limit_type");
            }
        }
        if (types.contains(DefaultAccessApi.LIMIT_UNLIMITED)
                && (types.contains(DefaultAccessApi.LIMIT_FIVE_HOUR) || types.contains(DefaultAccessApi.LIMIT_WEEK))) {
            throw new AccessBadRequestException("Unlimited cannot mix with window limits");
        }
        return List.copyOf(source);
    }

    static List<String> validateModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String model : models) {
            if (model == null || model.isBlank()) {
                throw new AccessBadRequestException("model must not be blank");
            }
            if (!seen.add(model)) {
                throw new AccessBadRequestException("Duplicate model");
            }
            result.add(model);
        }
        return List.copyOf(result);
    }

    private static List<QuotaLayer> amountLayers(List<UsageLimitInput> limits) {
        List<QuotaLayer> layers = new ArrayList<>();
        for (UsageLimitInput input : limits) {
            QuotaLayer layer = DefaultAccessApi.layerOf(input.limitType());
            if (layer != null) {
                layers.add(layer);
            }
        }
        return layers;
    }

    private static Throwable asUnavailable(Throwable error) {
        if (error instanceof QuotaStoreUnavailableException) {
            return error;
        }
        return new QuotaStoreUnavailableException("Quota store unavailable", error);
    }

    private record UpdatePersist(UserAccessTokenEntity entity, List<QuotaLayer> toEnsure) {}
}
