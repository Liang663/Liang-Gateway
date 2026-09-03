package com.liang.gateway.ai.internal.infrastructure.jpa;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLogEntity, Long> {

    List<LlmCallLogEntity> findByLlmApikeyCodeOrderByCreateTimeDesc(String llmApikeyCode);

    @Query(
            """
            select l from LlmCallLogEntity l
            where l.llmApikeyCode = :apikeyCode
              and l.createTime >= :fromTime
              and l.createTime <= :toTime
              and (:model is null or l.model = :model)
            """)
    List<LlmCallLogEntity> findForStats(
            @Param("apikeyCode") String apikeyCode,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime,
            @Param("model") String model);
}
