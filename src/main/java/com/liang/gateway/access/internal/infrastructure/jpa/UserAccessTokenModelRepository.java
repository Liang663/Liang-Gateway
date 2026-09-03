package com.liang.gateway.access.internal.infrastructure.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface UserAccessTokenModelRepository extends JpaRepository<UserAccessTokenModelEntity, Long> {

    boolean existsByTokenCodeAndModel(String tokenCode, String model);

    List<UserAccessTokenModelEntity> findByTokenCodeOrderByModelAsc(String tokenCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    void deleteByTokenCode(String tokenCode);
}
