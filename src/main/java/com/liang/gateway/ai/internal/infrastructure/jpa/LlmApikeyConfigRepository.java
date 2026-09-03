package com.liang.gateway.ai.internal.infrastructure.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmApikeyConfigRepository extends JpaRepository<LlmApikeyConfigEntity, Long> {

    Optional<LlmApikeyConfigEntity> findByCode(String code);

    List<LlmApikeyConfigEntity> findAllByOrderByCreateTimeDesc();
}
