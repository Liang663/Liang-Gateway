package com.liang.gateway.ai.internal.mcp.infrastructure.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpToolRepository extends JpaRepository<McpToolEntity, Long> {

    Optional<McpToolEntity> findByCode(String code);

    Optional<McpToolEntity> findByServerCodeAndName(String serverCode, String name);

    List<McpToolEntity> findByServerCodeAndEnabledTrueOrderByNameAsc(String serverCode);

    List<McpToolEntity> findByServerCodeOrderByNameAsc(String serverCode);

    void deleteByServerCode(String serverCode);
}
