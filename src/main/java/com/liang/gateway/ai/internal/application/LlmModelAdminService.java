package com.liang.gateway.ai.internal.application;

import com.liang.gateway.ai.AiBadRequestException;
import com.liang.gateway.ai.AiNotFoundException;
import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.infrastructure.IdentityCodes;
import com.liang.gateway.ai.internal.infrastructure.jpa.AiJpaExecutor;
import com.liang.gateway.ai.internal.infrastructure.jpa.LlmApikeyConfigRepository;
import com.liang.gateway.ai.internal.infrastructure.jpa.LlmModelEntity;
import com.liang.gateway.ai.internal.infrastructure.jpa.LlmModelRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LlmModelAdminService {

    private final AiJpaExecutor jpaExecutor;
    private final LlmModelRepository modelRepository;
    private final LlmApikeyConfigRepository apikeyRepository;
    private final AiClock aiClock;

    public LlmModelAdminService(
            AiJpaExecutor jpaExecutor,
            LlmModelRepository modelRepository,
            LlmApikeyConfigRepository apikeyRepository,
            AiClock aiClock) {
        this.jpaExecutor = jpaExecutor;
        this.modelRepository = modelRepository;
        this.apikeyRepository = apikeyRepository;
        this.aiClock = aiClock;
    }

    public Mono<ModelSnapshot> create(
            String name,
            String provider,
            String apikeyCode,
            long inputPriceFenPerMillion,
            long outputPriceFenPerMillion,
            boolean enabled) {
        return jpaExecutor.call(() -> {
            requireText(name, "name");
            requireText(provider, "provider");
            requireText(apikeyCode, "apikeyCode");
            requirePrices(inputPriceFenPerMillion, outputPriceFenPerMillion);
            requireApikey(apikeyCode);
            if (modelRepository.findByName(name).isPresent()) {
                throw new AiBadRequestException("Model name already exists");
            }
            LlmModelEntity saved = modelRepository.save(LlmModelEntity.create(
                    IdentityCodes.modelCode(),
                    name,
                    provider,
                    apikeyCode,
                    inputPriceFenPerMillion,
                    outputPriceFenPerMillion,
                    enabled,
                    aiClock.nowShanghai()));
            return toSnapshot(saved);
        });
    }

    public Mono<List<ModelSnapshot>> list() {
        return jpaExecutor.call(
                () -> modelRepository.findAllByOrderByCreateTimeDesc().stream()
                        .map(LlmModelAdminService::toSnapshot)
                        .toList());
    }

    public Mono<ModelSnapshot> get(String code) {
        return jpaExecutor
                .call(() -> modelRepository.findByCode(code))
                .flatMap(optional -> optional
                        .map(LlmModelAdminService::toSnapshot)
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new AiNotFoundException("Model not found"))));
    }

    public Mono<ModelSnapshot> update(
            String code,
            String name,
            String provider,
            String apikeyCode,
            Long inputPriceFenPerMillion,
            Long outputPriceFenPerMillion,
            Boolean enabled) {
        return jpaExecutor.call(() -> {
            LlmModelEntity entity = modelRepository
                    .findByCode(code)
                    .orElseThrow(() -> new AiNotFoundException("Model not found"));
            String nextName = name == null ? entity.getName() : name;
            String nextProvider = provider == null ? entity.getProvider() : provider;
            String nextApikey = apikeyCode == null ? entity.getApikeyCode() : apikeyCode;
            long nextIn = inputPriceFenPerMillion == null
                    ? entity.getInputPriceFenPerMillion()
                    : inputPriceFenPerMillion;
            long nextOut = outputPriceFenPerMillion == null
                    ? entity.getOutputPriceFenPerMillion()
                    : outputPriceFenPerMillion;
            boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
            requireText(nextName, "name");
            requireText(nextProvider, "provider");
            requireText(nextApikey, "apikeyCode");
            requirePrices(nextIn, nextOut);
            requireApikey(nextApikey);
            modelRepository.findByName(nextName).ifPresent(existing -> {
                if (!existing.getCode().equals(code)) {
                    throw new AiBadRequestException("Model name already exists");
                }
            });
            entity.update(nextName, nextProvider, nextApikey, nextIn, nextOut, nextEnabled, aiClock.nowShanghai());
            return toSnapshot(modelRepository.save(entity));
        });
    }

    static ModelSnapshot toSnapshot(LlmModelEntity entity) {
        return new ModelSnapshot(
                entity.getCode(),
                entity.getName(),
                entity.getProvider(),
                entity.getApikeyCode(),
                entity.getInputPriceFenPerMillion(),
                entity.getOutputPriceFenPerMillion(),
                entity.isEnabled(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    private void requireApikey(String apikeyCode) {
        if (apikeyRepository.findByCode(apikeyCode).isEmpty()) {
            throw new AiBadRequestException("Unknown apikey_code");
        }
    }

    private static void requirePrices(long input, long output) {
        if (input < 0 || output < 0) {
            throw new AiBadRequestException("prices must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AiBadRequestException(field + " is required");
        }
    }
}
