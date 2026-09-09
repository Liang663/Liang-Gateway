package com.liang.gateway.ai.internal.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

public interface LlmCallLogRepository extends R2dbcRepository<LlmCallLogEntity, Long> {

    Flux<LlmCallLogEntity> findByLlmApikeyCodeOrderByCreateTimeDesc(String llmApikeyCode);

    @Query(
            """
            SELECT * FROM llm_call_log
            WHERE llm_apikey_code = :apikeyCode
              AND create_time >= :fromTime
              AND create_time <= :toTime
            """)
    Flux<LlmCallLogEntity> findForStats(String apikeyCode, LocalDateTime fromTime, LocalDateTime toTime);
}
