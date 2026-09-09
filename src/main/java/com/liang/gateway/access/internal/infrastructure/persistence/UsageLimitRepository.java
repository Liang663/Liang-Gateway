package com.liang.gateway.access.internal.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UsageLimitRepository extends R2dbcRepository<UsageLimitEntity, Long> {

    Flux<UsageLimitEntity> findByTokenCodeOrderByLimitTypeAsc(String tokenCode);

    Mono<UsageLimitEntity> findByTokenCodeAndLimitType(String tokenCode, int limitType);

    @Modifying
    @Query("DELETE FROM usage_limit WHERE token_code = :tokenCode")
    Mono<Long> deleteByTokenCode(String tokenCode);
}
