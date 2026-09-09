package com.liang.gateway.ai.internal.application;

import com.liang.gateway.ai.AiBadRequestException;
import com.liang.gateway.ai.AiNotFoundException;
import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.infrastructure.IdentityCodes;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmApikeyConfigEntity;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmApikeyConfigRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LlmApikeyAdminService {

    private final LlmApikeyConfigRepository apikeyRepository;
    private final AiClock aiClock;

    public LlmApikeyAdminService(LlmApikeyConfigRepository apikeyRepository, AiClock aiClock) {
        this.apikeyRepository = apikeyRepository;
        this.aiClock = aiClock;
    }

    public Mono<ApikeySnapshot> create(
            String name, String provider, String baseUrl, String secret, boolean enabled, LocalDateTime expireTime) {
        return Mono.defer(() -> {
            requireText(name, "name");
            requireText(provider, "provider");
            requireText(baseUrl, "baseUrl");
            requireText(secret, "secret");
            return apikeyRepository
                    .save(LlmApikeyConfigEntity.create(
                            IdentityCodes.apikeyCode(),
                            name,
                            provider,
                            baseUrl,
                            secret,
                            prefixOf(secret),
                            enabled,
                            expireTime,
                            aiClock.nowShanghai()))
                    .map(LlmApikeyAdminService::toSnapshot);
        });
    }

    public Mono<List<ApikeySnapshot>> list() {
        return apikeyRepository.findAllByOrderByCreateTimeDesc().map(LlmApikeyAdminService::toSnapshot).collectList();
    }

    public Mono<ApikeySnapshot> get(String code) {
        return apikeyRepository
                .findByCode(code)
                .map(LlmApikeyAdminService::toSnapshot)
                .switchIfEmpty(Mono.error(new AiNotFoundException("API key not found")));
    }

    public Mono<ApikeySnapshot> update(
            String code,
            String name,
            String provider,
            String baseUrl,
            String secret,
            Boolean enabled,
            LocalDateTime expireTime,
            boolean expireTimePresent) {
        return apikeyRepository
                .findByCode(code)
                .switchIfEmpty(Mono.error(new AiNotFoundException("API key not found")))
                .flatMap(entity -> {
                    String nextName = name == null ? entity.getName() : name;
                    String nextProvider = provider == null ? entity.getProvider() : provider;
                    String nextBaseUrl = baseUrl == null ? entity.getBaseUrl() : baseUrl;
                    String nextSecret = secret == null ? entity.getSecret() : secret;
                    boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
                    LocalDateTime nextExpire = expireTimePresent ? expireTime : entity.getExpireTime();
                    requireText(nextName, "name");
                    requireText(nextProvider, "provider");
                    requireText(nextBaseUrl, "baseUrl");
                    requireText(nextSecret, "secret");
                    entity.update(
                            nextName,
                            nextProvider,
                            nextBaseUrl,
                            nextSecret,
                            prefixOf(nextSecret),
                            nextEnabled,
                            nextExpire,
                            aiClock.nowShanghai());
                    return apikeyRepository.save(entity).map(LlmApikeyAdminService::toSnapshot);
                });
    }

    static ApikeySnapshot toSnapshot(LlmApikeyConfigEntity entity) {
        return new ApikeySnapshot(
                entity.getCode(),
                entity.getName(),
                entity.getProvider(),
                entity.getBaseUrl(),
                entity.getPrefix(),
                entity.isEnabled(),
                entity.getExpireTime(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    static String prefixOf(String secret) {
        return secret.substring(0, Math.min(secret.length(), 8));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AiBadRequestException(field + " is required");
        }
    }
}
