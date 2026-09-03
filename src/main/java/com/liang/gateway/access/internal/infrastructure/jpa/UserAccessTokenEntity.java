package com.liang.gateway.access.internal.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_access_token")
public class UserAccessTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "access_token", nullable = false, unique = true, length = 128)
    private String accessToken;

    @Column(name = "apikey_code", nullable = false, length = 64)
    private String apikeyCode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    @Column(name = "qpm_limit", nullable = false)
    private int qpmLimit;

    @Column(name = "hourly_token_limit", nullable = false)
    private long hourlyTokenLimit;

    @Column(name = "weekly_token_limit", nullable = false)
    private long weeklyTokenLimit;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    protected UserAccessTokenEntity() {}

    public static UserAccessTokenEntity create(
            String code,
            String userCode,
            String accessToken,
            String apikeyCode,
            boolean enabled,
            LocalDateTime expireTime,
            int qpmLimit,
            long hourlyTokenLimit,
            long weeklyTokenLimit,
            LocalDateTime now) {
        UserAccessTokenEntity entity = new UserAccessTokenEntity();
        entity.code = code;
        entity.userCode = userCode;
        entity.accessToken = accessToken;
        entity.apikeyCode = apikeyCode;
        entity.enabled = enabled;
        entity.expireTime = expireTime;
        entity.qpmLimit = qpmLimit;
        entity.hourlyTokenLimit = hourlyTokenLimit;
        entity.weeklyTokenLimit = weeklyTokenLimit;
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

    public String getUserCode() {
        return userCode;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getApikeyCode() {
        return apikeyCode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public int getQpmLimit() {
        return qpmLimit;
    }

    public long getHourlyTokenLimit() {
        return hourlyTokenLimit;
    }

    public long getWeeklyTokenLimit() {
        return weeklyTokenLimit;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void update(
            boolean enabled,
            LocalDateTime expireTime,
            int qpmLimit,
            long hourlyTokenLimit,
            long weeklyTokenLimit,
            LocalDateTime now) {
        this.enabled = enabled;
        this.expireTime = expireTime;
        this.qpmLimit = qpmLimit;
        this.hourlyTokenLimit = hourlyTokenLimit;
        this.weeklyTokenLimit = weeklyTokenLimit;
        this.updateTime = now;
    }

    public boolean isExpired(LocalDateTime now) {
        return expireTime != null && !expireTime.isAfter(now);
    }
}
