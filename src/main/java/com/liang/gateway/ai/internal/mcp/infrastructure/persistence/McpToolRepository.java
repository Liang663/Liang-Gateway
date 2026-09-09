package com.liang.gateway.ai.internal.mcp.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface McpToolRepository extends R2dbcRepository<McpToolEntity, Long> {

    Mono<McpToolEntity> findByCode(String code);

    Mono<McpToolEntity> findByServerCodeAndName(String serverCode, String name);

    Flux<McpToolEntity> findByServerCodeAndEnabledTrueOrderByNameAsc(String serverCode);

    Flux<McpToolEntity> findByServerCodeOrderByNameAsc(String serverCode);

    @Modifying
    @Query("DELETE FROM mcp_tool WHERE server_code = :serverCode")
    Mono<Long> deleteByServerCode(String serverCode);
}
