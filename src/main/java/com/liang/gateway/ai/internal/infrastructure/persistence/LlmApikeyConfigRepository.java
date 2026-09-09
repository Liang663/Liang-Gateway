package com.liang.gateway.ai.internal.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LlmApikeyConfigRepository extends R2dbcRepository<LlmApikeyConfigEntity, Long> {

    Mono<LlmApikeyConfigEntity> findByCode(String code);

    Flux<LlmApikeyConfigEntity> findAllByOrderByCreateTimeDesc();
}
