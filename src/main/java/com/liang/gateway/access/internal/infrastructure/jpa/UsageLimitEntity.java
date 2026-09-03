package com.liang.gateway.access.internal.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "usage_limit")
public class UsageLimitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "token_code", nullable = false, length = 64)
    private String tokenCode;

    @Column(name = "limit_type", nullable = false)
    private int limitType;

    @Column(name = "`usage`", nullable = false)
    private long usage;

    @Column(name = "used", nullable = false)
    private long used;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
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

    public Long getId() {
        return id;
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
