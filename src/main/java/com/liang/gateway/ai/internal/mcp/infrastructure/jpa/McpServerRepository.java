package com.liang.gateway.ai.internal.mcp.infrastructure.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<McpServerEntity, Long> {

    Optional<McpServerEntity> findByCode(String code);

    Optional<McpServerEntity> findByPath(String path);

    List<McpServerEntity> findAllByOrderByCreateTimeDesc();
}
