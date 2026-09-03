package com.liang.gateway.access.internal.infrastructure.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface UserAccessTokenRepository extends JpaRepository<UserAccessTokenEntity, Long> {

    Optional<UserAccessTokenEntity> findByCode(String code);

    Optional<UserAccessTokenEntity> findByAccessToken(String accessToken);

    List<UserAccessTokenEntity> findByUserCodeOrderByCreateTimeDesc(String userCode);

    @Modifying
    @Transactional
    void deleteByCode(String code);
}
