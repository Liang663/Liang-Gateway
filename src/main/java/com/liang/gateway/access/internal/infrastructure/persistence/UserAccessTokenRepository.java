package com.liang.gateway.access.internal.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserAccessTokenRepository extends R2dbcRepository<UserAccessTokenEntity, Long> {

    Mono<UserAccessTokenEntity> findByCode(String code);

    Mono<UserAccessTokenEntity> findByAccessToken(String accessToken);

    Flux<UserAccessTokenEntity> findByUserCodeOrderByCreateTimeDesc(String userCode);

    @Modifying
    @Query("DELETE FROM user_access_token WHERE code = :code")
    Mono<Long> deleteByCode(String code);
}
