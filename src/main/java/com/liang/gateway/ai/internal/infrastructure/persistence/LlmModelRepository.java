package com.liang.gateway.ai.internal.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LlmModelRepository extends R2dbcRepository<LlmModelEntity, Long> {

    Mono<LlmModelEntity> findByCode(String code);

    Mono<LlmModelEntity> findByName(String name);

    Flux<LlmModelEntity> findByEnabledTrueOrderByNameAsc();

    Flux<LlmModelEntity> findAllByOrderByCreateTimeDesc();
}
