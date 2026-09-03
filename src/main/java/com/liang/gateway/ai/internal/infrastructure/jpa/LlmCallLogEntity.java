package com.liang.gateway.ai.internal.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_call_log")
public class LlmCallLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "llm_apikey_code", nullable = false, length = 64)
    private String llmApikeyCode;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "message", length = 512)
    private String message;

    @Column(name = "first_token_ms")
    private Integer firstTokenMs;

    @Column(name = "total_duration_ms", nullable = false)
    private int totalDurationMs;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
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

    public Long getId() {
        return id;
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
