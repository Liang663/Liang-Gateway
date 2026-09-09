package com.liang.gateway.ai.internal.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("llm_call_log")
public class LlmCallLogEntity extends PersistableRow {

    @Column("code")
    private String code;

    @Column("llm_apikey_code")
    private String llmApikeyCode;

    @Column("model")
    private String model;

    @Column("success")
    private boolean success;

    @Column("message")
    private String message;

    @Column("first_token_ms")
    private Integer firstTokenMs;

    @Column("total_duration_ms")
    private int totalDurationMs;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    protected LlmCallLogEntity() {}

    public static LlmCallLogEntity create(
            String code,
            String llmApikeyCode,
            String model,
            boolean success,
            String message,
            Integer firstTokenMs,
            int totalDurationMs,
            LocalDateTime now) {
        LlmCallLogEntity entity = new LlmCallLogEntity();
        entity.code = code;
        entity.llmApikeyCode = llmApikeyCode;
        entity.model = model;
        entity.success = success;
        entity.message = message;
        entity.firstTokenMs = firstTokenMs;
        entity.totalDurationMs = totalDurationMs;
        entity.createTime = now;
        entity.updateTime = now;
        return entity;
    }

    public String getCode() {
        return code;
    }

    public String getLlmApikeyCode() {
        return llmApikeyCode;
    }

    public String getModel() {
        return model;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Integer getFirstTokenMs() {
        return firstTokenMs;
    }

    public int getTotalDurationMs() {
        return totalDurationMs;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }
}
