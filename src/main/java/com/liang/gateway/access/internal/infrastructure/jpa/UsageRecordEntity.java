package com.liang.gateway.access.internal.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "usage_record")
public class UsageRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "token_code", nullable = false, length = 64)
    private String tokenCode;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    protected UsageRecordEntity() {}

    public static UsageRecordEntity create(
            String code,
            String tokenCode,
            String userCode,
            long promptTokens,
            long completionTokens,
            String model,
            String requestId,
            LocalDateTime createTime) {
        UsageRecordEntity entity = new UsageRecordEntity();
        entity.code = code;
        entity.tokenCode = tokenCode;
        entity.userCode = userCode;
        entity.promptTokens = promptTokens;
        entity.completionTokens = completionTokens;
        entity.totalTokens = promptTokens + completionTokens;
        entity.model = model;
        entity.requestId = requestId;
        entity.createTime = createTime;
        return entity;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTokenCode() {
        return tokenCode;
    }

    public String getUserCode() {
        return userCode;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public String getModel() {
        return model;
    }

    public String getRequestId() {
        return requestId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }
}
