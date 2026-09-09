package com.liang.gateway.access.internal.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserAccessTokenModelRepository extends R2dbcRepository<UserAccessTokenModelEntity, Long> {

    Mono<Boolean> existsByTokenCodeAndModel(String tokenCode, String model);

    Flux<UserAccessTokenModelEntity> findByTokenCodeOrderByModelAsc(String tokenCode);

    @Modifying
    @Query("DELETE FROM user_access_token_model WHERE token_code = :tokenCode")
    Mono<Long> deleteByTokenCode(String tokenCode);
}
