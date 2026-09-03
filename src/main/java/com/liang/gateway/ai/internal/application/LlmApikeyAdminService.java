package com.liang.gateway.ai.internal.application;

import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.infrastructure.IdentityCodes;
import com.liang.gateway.ai.internal.infrastructure.jpa.AiJpaExecutor;
import com.liang.gateway.ai.internal.infrastructure.jpa.LlmApikeyConfigEntity;
import com.liang.gateway.ai.internal.infrastructure.jpa.LlmApikeyConfigRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LlmApikeyAdminService {

    private final AiJpaExecutor jpaExecutor;
    private final LlmApikeyConfigRepository apikeyRepository;
    private final AiClock aiClock;

    public LlmApikeyAdminService(
            AiJpaExecutor jpaExecutor, LlmApikeyConfigRepository apikeyRepository, AiClock aiClock) {
        this.jpaExecutor = jpaExecutor;
        this.apikeyRepository = apikeyRepository;
        this.aiClock = aiClock;
    }

    public Mono<ApikeySnapshot> create(
            String name, String provider, String baseUrl, String secret, boolean enabled, LocalDateTime expireTime) {
        return jpaExecutor.call(() -> {
            requireText(name, "name");
            requireText(provider, "provider");
            requireText(baseUrl, "baseUrl");
            requireText(secret, "secret");
            LlmApikeyConfigEntity saved = apikeyRepository.save(LlmApikeyConfigEntity.create(
                    IdentityCodes.apikeyCode(),
                    name,
                    provider,
                    baseUrl,
                    secret,
                    prefixOf(secret),
                    enabled,
                    expireTime,
                    aiClock.nowShanghai()));
            return toSnapshot(saved);
        });
    }

    public Mono<List<ApikeySnapshot>> list() {
        return jpaExecutor.call(() -> apikeyRepository.findAllByOrderByCreateTimeDesc().stream()
                .map(LlmApikeyAdminService::toSnapshot)
                .toList());
    }

    public Mono<ApikeySnapshot> get(String code) {
        return jpaExecutor
                .call(() -> apikeyRepository.findByCode(code))
                .flatMap(optional -> optional
                        .map(LlmApikeyAdminService::toSnapshot)
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new AiNotFoundException("API key not found"))));
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
        return jpaExecutor.call(() -> {
            LlmApikeyConfigEntity entity = apikeyRepository
                    .findByCode(code)
                    .orElseThrow(() -> new AiNotFoundException("API key not found"));
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
            return toSnapshot(apikeyRepository.save(entity));
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
