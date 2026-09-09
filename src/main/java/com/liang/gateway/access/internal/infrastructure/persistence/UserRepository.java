package com.liang.gateway.access.internal.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRepository extends R2dbcRepository<UserEntity, Long> {

    Mono<UserEntity> findByCode(String code);

    Flux<UserEntity> findAllByOrderByCreateTimeDesc();
}
