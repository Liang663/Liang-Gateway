package com.liang.gateway.access.internal.infrastructure.jpa;

import com.liang.gateway.access.internal.application.UsageTotals;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, Long> {

    List<UsageRecordEntity> findByTokenCodeOrderByCreateTimeDesc(String tokenCode, Pageable pageable);

    @Query(
            """
            select new com.liang.gateway.access.internal.application.UsageTotals(
                coalesce(sum(r.promptTokens), 0L),
                coalesce(sum(r.completionTokens), 0L),
                coalesce(sum(r.totalTokens), 0L))
            from UsageRecordEntity r
            where r.tokenCode = :tokenCode
              and r.createTime >= :fromTime
              and r.createTime <= :toTime
            """)
    UsageTotals sumBetween(
            @Param("tokenCode") String tokenCode,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime);

    @Query(
            """
            select new com.liang.gateway.access.internal.application.UsageTotals(
                coalesce(sum(r.promptTokens), 0L),
                coalesce(sum(r.completionTokens), 0L),
                coalesce(sum(r.totalTokens), 0L))
            from UsageRecordEntity r
            where r.tokenCode = :tokenCode
            """)
    UsageTotals sumAll(@Param("tokenCode") String tokenCode);
}
