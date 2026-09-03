package com.liang.gateway.access.internal.infrastructure.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface UsageLimitRepository extends JpaRepository<UsageLimitEntity, Long> {

    List<UsageLimitEntity> findByTokenCodeOrderByLimitTypeAsc(String tokenCode);

    Optional<UsageLimitEntity> findByTokenCodeAndLimitType(String tokenCode, int limitType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    void deleteByTokenCode(String tokenCode);
}
