package com.liang.gateway.access.internal.infrastructure.persistence;

import com.liang.gateway.access.internal.application.ModelUsageTotals;
import com.liang.gateway.access.internal.application.UsageTotals;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UsageRecordRepository extends R2dbcRepository<UsageRecordEntity, Long> {

    Flux<UsageRecordEntity> findByTokenCodeOrderByCreateTimeDesc(String tokenCode, Pageable pageable);

    @Query(
            """
            SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
                   COALESCE(SUM(completion_tokens), 0) AS completionTokens,
                   COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(amount_fen), 0) AS amountFen
            FROM usage_record
            WHERE token_code = :tokenCode
              AND create_time >= :fromTime
              AND create_time <= :toTime
            """)
    Mono<UsageTotals> sumBetween(String tokenCode, LocalDateTime fromTime, LocalDateTime toTime);

    @Query(
            """
            SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
                   COALESCE(SUM(completion_tokens), 0) AS completionTokens,
                   COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(amount_fen), 0) AS amountFen
            FROM usage_record
            WHERE token_code = :tokenCode
            """)
    Mono<UsageTotals> sumAll(String tokenCode);

    @Query(
            """
            SELECT model AS model,
                   COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
                   COALESCE(SUM(completion_tokens), 0) AS completionTokens,
                   COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(amount_fen), 0) AS amountFen
            FROM usage_record
            WHERE token_code = :tokenCode
              AND create_time >= :fromTime
              AND create_time <= :toTime
            GROUP BY model
            ORDER BY model
            """)
    Flux<ModelUsageTotals> sumByModelBetween(String tokenCode, LocalDateTime fromTime, LocalDateTime toTime);

    @Query(
            """
            SELECT model AS model,
                   COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
                   COALESCE(SUM(completion_tokens), 0) AS completionTokens,
                   COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(amount_fen), 0) AS amountFen
            FROM usage_record
            WHERE token_code = :tokenCode
            GROUP BY model
            ORDER BY model
            """)
    Flux<ModelUsageTotals> sumByModelAll(String tokenCode);
}
