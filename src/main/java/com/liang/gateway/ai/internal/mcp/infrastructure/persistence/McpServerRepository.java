package com.liang.gateway.ai.internal.mcp.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface McpServerRepository extends R2dbcRepository<McpServerEntity, Long> {

    Mono<McpServerEntity> findByCode(String code);

    Mono<McpServerEntity> findByPath(String path);

    Flux<McpServerEntity> findAllByOrderByCreateTimeDesc();
}
