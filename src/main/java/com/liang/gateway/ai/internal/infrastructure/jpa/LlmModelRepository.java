package com.liang.gateway.ai.internal.infrastructure.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmModelRepository extends JpaRepository<LlmModelEntity, Long> {

    Optional<LlmModelEntity> findByCode(String code);

    Optional<LlmModelEntity> findByName(String name);

    List<LlmModelEntity> findByEnabledTrueOrderByNameAsc();

    List<LlmModelEntity> findAllByOrderByCreateTimeDesc();
}
