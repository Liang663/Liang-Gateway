package com.liang.gateway.ai.internal.application;

import com.liang.gateway.ai.AiBadRequestException;
import com.liang.gateway.ai.AiNotFoundException;
import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.infrastructure.IdentityCodes;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmApikeyConfigRepository;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmModelEntity;
import com.liang.gateway.ai.internal.infrastructure.persistence.LlmModelRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LlmModelAdminService {

    private final LlmModelRepository modelRepository;
    private final LlmApikeyConfigRepository apikeyRepository;
    private final AiClock aiClock;

    public LlmModelAdminService(
            LlmModelRepository modelRepository, LlmApikeyConfigRepository apikeyRepository, AiClock aiClock) {
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
        return Mono.defer(() -> {
            requireText(name, "name");
            requireText(provider, "provider");
            requireText(apikeyCode, "apikeyCode");
            requirePrices(inputPriceFenPerMillion, outputPriceFenPerMillion);
            return requireApikey(apikeyCode)
                    .then(modelRepository.findByName(name))
                    .flatMap(existing -> Mono.error(new AiBadRequestException("Model name already exists")))
                    .switchIfEmpty(Mono.defer(() -> modelRepository.save(LlmModelEntity.create(
                            IdentityCodes.modelCode(),
                            name,
                            provider,
                            apikeyCode,
                            inputPriceFenPerMillion,
                            outputPriceFenPerMillion,
                            enabled,
                            aiClock.nowShanghai()))))
                    .cast(LlmModelEntity.class)
                    .map(LlmModelAdminService::toSnapshot);
        });
    }

    public Mono<List<ModelSnapshot>> list() {
        return modelRepository.findAllByOrderByCreateTimeDesc().map(LlmModelAdminService::toSnapshot).collectList();
    }

    public Mono<ModelSnapshot> get(String code) {
        return modelRepository
                .findByCode(code)
                .map(LlmModelAdminService::toSnapshot)
                .switchIfEmpty(Mono.error(new AiNotFoundException("Model not found")));
    }

    public Mono<ModelSnapshot> update(
            String code,
            String name,
            String provider,
            String apikeyCode,
            Long inputPriceFenPerMillion,
            Long outputPriceFenPerMillion,
            Boolean enabled) {
        return modelRepository
                .findByCode(code)
                .switchIfEmpty(Mono.error(new AiNotFoundException("Model not found")))
                .flatMap(entity -> {
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
                    return requireApikey(nextApikey)
                            .then(modelRepository.findByName(nextName))
                            .flatMap(existing -> {
                                if (!existing.getCode().equals(code)) {
                                    return Mono.error(new AiBadRequestException("Model name already exists"));
                                }
                                return Mono.just(entity);
                            })
                            .switchIfEmpty(Mono.just(entity))
                            .flatMap(current -> {
                                current.update(
                                        nextName,
                                        nextProvider,
                                        nextApikey,
                                        nextIn,
                                        nextOut,
                                        nextEnabled,
                                        aiClock.nowShanghai());
                                return modelRepository.save(current).map(LlmModelAdminService::toSnapshot);
                            });
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

    private Mono<Void> requireApikey(String apikeyCode) {
        return apikeyRepository
                .findByCode(apikeyCode)
                .switchIfEmpty(Mono.error(new AiBadRequestException("Unknown apikey_code")))
                .then();
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
