package com.liang.gateway.access.internal.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("usage_limit")
public class UsageLimitEntity extends PersistableRow {

    @Column("user_code")
    private String userCode;

    @Column("token_code")
    private String tokenCode;

    @Column("limit_type")
    private int limitType;

    @Column("usage")
    private long usage;

    @Column("used")
    private long used;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    protected UsageLimitEntity() {}

    public static UsageLimitEntity create(
            String userCode, String tokenCode, int limitType, long usage, long used, LocalDateTime now) {
        UsageLimitEntity entity = new UsageLimitEntity();
        entity.userCode = userCode;
        entity.tokenCode = tokenCode;
        entity.limitType = limitType;
        entity.usage = usage;
        entity.used = used;
        entity.createTime = now;
        entity.updateTime = now;
        return entity;
    }

    public String getUserCode() {
        return userCode;
    }

    public String getTokenCode() {
        return tokenCode;
    }

    public int getLimitType() {
        return limitType;
    }

    public long getUsage() {
        return usage;
    }

    public long getUsed() {
        return used;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void updateUsed(long used, LocalDateTime now) {
        this.used = used;
        this.updateTime = now;
    }
}
