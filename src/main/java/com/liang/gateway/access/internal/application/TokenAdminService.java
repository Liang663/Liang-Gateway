package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.QuotaLayer;
import com.liang.gateway.access.QuotaStoreUnavailableException;
import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.IdentityCodes;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageLimitEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UsageLimitRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenModelEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenModelRepository;
import com.liang.gateway.access.internal.infrastructure.persistence.UserAccessTokenRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class TokenAdminService {

    private final UserAccessTokenRepository tokenRepository;
    private final UsageLimitRepository usageLimitRepository;
    private final UserAccessTokenModelRepository tokenModelRepository;
    private final UserAdminService userAdminService;
    private final QuotaWindowStore quotaWindowStore;
    private final AccessClock accessClock;
    private final TransactionalOperator transactionalOperator;

    public TokenAdminService(
            UserAccessTokenRepository tokenRepository,
            UsageLimitRepository usageLimitRepository,
            UserAccessTokenModelRepository tokenModelRepository,
            UserAdminService userAdminService,
            QuotaWindowStore quotaWindowStore,
            AccessClock accessClock,
            TransactionalOperator transactionalOperator) {
        this.tokenRepository = tokenRepository;
        this.usageLimitRepository = usageLimitRepository;
        this.tokenModelRepository = tokenModelRepository;
        this.userAdminService = userAdminService;
        this.quotaWindowStore = quotaWindowStore;
        this.accessClock = accessClock;
        this.transactionalOperator = transactionalOperator;
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
                    .then(persist(userCode, qpmLimit, enabled, expireTime, validatedModels, validatedLimits))
                    .flatMap(saved -> quotaWindowStore
                            .openWindows(saved.getCode(), layers, accessClock.instant())
                            .then(toSnapshot(saved))
                            .onErrorResume(error -> deleteTokenGraph(saved.getCode())
                                    .then(Mono.error(asUnavailable(error)))));
        });
    }

    public Mono<List<TokenSnapshot>> list(String userCode) {
        return userAdminService
                .requireUser(userCode)
                .thenMany(tokenRepository.findByUserCodeOrderByCreateTimeDesc(userCode))
                .concatMap(this::toSnapshot)
                .collectList();
    }

    public Mono<TokenSnapshot> get(String userCode, String tokenCode) {
        return loadOwned(userCode, tokenCode).flatMap(this::toSnapshot);
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
        return loadOwned(userCode, tokenCode).flatMap(entity -> {
            boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
            LocalDateTime nextExpire = expireTimePresent ? expireTime : entity.getExpireTime();
            entity.update(nextEnabled, nextExpire, qpmLimit, accessClock.nowShanghai());
            LocalDateTime now = accessClock.nowShanghai();
            List<String> nextModels = models == null ? null : validateModels(models);
            List<UsageLimitInput> nextLimits = limits == null ? null : validateLimits(limits);
            Mono<UpdatePersist> work = tokenRepository.save(entity).flatMap(saved -> {
                Mono<Void> modelWrite =
                        nextModels == null ? Mono.empty() : replaceModels(saved.getCode(), nextModels, now);
                Mono<List<QuotaLayer>> limitWrite;
                if (nextLimits == null) {
                    limitWrite = Mono.just(List.of());
                } else {
                    limitWrite = usageLimitRepository
                            .findByTokenCodeOrderByLimitTypeAsc(saved.getCode())
                            .collectList()
                            .flatMap(previous -> replaceLimits(
                                            saved.getUserCode(), saved.getCode(), nextLimits, previous, now)
                                    .thenReturn(amountLayers(nextLimits)));
                }
                return modelWrite.then(limitWrite).map(layers -> new UpdatePersist(saved, layers));
            });
            return transactionalOperator.transactional(work).flatMap(result -> quotaWindowStore
                    .ensureWindows(result.entity().getCode(), result.toEnsure(), accessClock.instant())
                    .then(toSnapshot(result.entity())));
        });
    }

    public Mono<Void> resetQuota(String userCode, String tokenCode, QuotaLayer layer) {
        return loadOwned(userCode, tokenCode)
                .then(quotaWindowStore.reset(tokenCode, layer, accessClock.instant()))
                .flatMap(snapshot -> usageLimitRepository
                        .findByTokenCodeAndLimitType(tokenCode, DefaultAccessApi.limitType(layer))
                        .flatMap(entity -> {
                            entity.updateUsed(snapshot.used(), accessClock.nowShanghai());
                            return usageLimitRepository.save(entity);
                        }))
                .then();
    }

    private Mono<UserAccessTokenEntity> loadOwned(String userCode, String tokenCode) {
        return userAdminService
                .requireUser(userCode)
                .then(tokenRepository.findByCode(tokenCode))
                .switchIfEmpty(Mono.error(new AccessNotFoundException("Access token not found")))
                .flatMap(entity -> {
                    if (!userCode.equals(entity.getUserCode())) {
                        return Mono.error(new AccessNotFoundException("Access token not found"));
                    }
                    return Mono.just(entity);
                });
    }

    private Mono<UserAccessTokenEntity> persist(
            String userCode,
            int qpmLimit,
            boolean enabled,
            LocalDateTime expireTime,
            List<String> models,
            List<UsageLimitInput> limits) {
        LocalDateTime now = accessClock.nowShanghai();
        return transactionalOperator.transactional(tokenRepository
                .save(UserAccessTokenEntity.create(
                        IdentityCodes.tokenCode(),
                        userCode,
                        IdentityCodes.accessToken(),
                        enabled,
                        expireTime,
                        qpmLimit,
                        now))
                .flatMap(saved -> replaceLimits(userCode, saved.getCode(), limits, List.of(), now)
                        .then(replaceModels(saved.getCode(), models, now))
                        .thenReturn(saved)));
    }

    private Mono<Void> replaceLimits(
            String userCode,
            String tokenCode,
            List<UsageLimitInput> limits,
            List<UsageLimitEntity> previous,
            LocalDateTime now) {
        Map<Integer, Long> previousUsed = previous.stream()
                .collect(Collectors.toMap(UsageLimitEntity::getLimitType, UsageLimitEntity::getUsed, (left, right) -> left));
        return usageLimitRepository.deleteByTokenCode(tokenCode).thenMany(Flux.fromIterable(limits).concatMap(input -> {
                    long used = previousUsed.getOrDefault(input.limitType(), 0L);
                    return usageLimitRepository.save(UsageLimitEntity.create(
                            userCode, tokenCode, input.limitType(), input.usage(), used, now));
                }))
                .then();
    }

    private Mono<Void> replaceModels(String tokenCode, List<String> models, LocalDateTime now) {
        return tokenModelRepository
                .deleteByTokenCode(tokenCode)
                .thenMany(Flux.fromIterable(models)
                        .concatMap(model ->
                                tokenModelRepository.save(UserAccessTokenModelEntity.create(tokenCode, model, now))))
                .then();
    }

    private Mono<Void> deleteTokenGraph(String tokenCode) {
        return transactionalOperator.transactional(tokenModelRepository
                .deleteByTokenCode(tokenCode)
                .then(usageLimitRepository.deleteByTokenCode(tokenCode))
                .then(tokenRepository.deleteByCode(tokenCode))
                .then());
    }

    private Mono<TokenSnapshot> toSnapshot(UserAccessTokenEntity entity) {
        Mono<List<String>> models = tokenModelRepository
                .findByTokenCodeOrderByModelAsc(entity.getCode())
                .map(UserAccessTokenModelEntity::getModel)
                .collectList();
        Mono<List<UsageLimitSnapshot>> limits = usageLimitRepository
                .findByTokenCodeOrderByLimitTypeAsc(entity.getCode())
                .map(item -> new UsageLimitSnapshot(item.getLimitType(), item.getUsage(), item.getUsed()))
                .collectList();
        return Mono.zip(models, limits)
                .map(tuple -> new TokenSnapshot(
                        entity.getCode(),
                        entity.getUserCode(),
                        entity.getAccessToken(),
                        entity.isEnabled(),
                        entity.getExpireTime(),
                        entity.getQpmLimit(),
                        tuple.getT1(),
                        tuple.getT2(),
                        entity.getCreateTime(),
                        entity.getUpdateTime()));
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
