package com.liang.gateway.access.internal.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("usage_record")
public class UsageRecordEntity extends PersistableRow {

    @Column("code")
    private String code;

    @Column("token_code")
    private String tokenCode;

    @Column("user_code")
    private String userCode;

    @Column("prompt_tokens")
    private long promptTokens;

    @Column("completion_tokens")
    private long completionTokens;

    @Column("total_tokens")
    private long totalTokens;

    @Column("amount_fen")
    private long amountFen;

    @Column("model")
    private String model;

    @Column("request_id")
    private String requestId;

    @Column("create_time")
    private LocalDateTime createTime;

    protected UsageRecordEntity() {}

    public static UsageRecordEntity create(
            String code,
            String tokenCode,
            String userCode,
            long promptTokens,
            long completionTokens,
            long amountFen,
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
        entity.amountFen = amountFen;
        entity.model = model;
        entity.requestId = requestId;
        entity.createTime = createTime;
        return entity;
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

    public long getAmountFen() {
        return amountFen;
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
