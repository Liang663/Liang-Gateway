package com.liang.gateway.access.internal.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_access_token")
public class UserAccessTokenEntity extends PersistableRow {

    @Column("code")
    private String code;

    @Column("user_code")
    private String userCode;

    @Column("access_token")
    private String accessToken;

    @Column("enabled")
    private boolean enabled;

    @Column("expire_time")
    private LocalDateTime expireTime;

    @Column("qpm_limit")
    private int qpmLimit;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    protected UserAccessTokenEntity() {}

    public static UserAccessTokenEntity create(
            String code,
            String userCode,
            String accessToken,
            boolean enabled,
            LocalDateTime expireTime,
            int qpmLimit,
            LocalDateTime now) {
        UserAccessTokenEntity entity = new UserAccessTokenEntity();
        entity.code = code;
        entity.userCode = userCode;
        entity.accessToken = accessToken;
        entity.enabled = enabled;
        entity.expireTime = expireTime;
        entity.qpmLimit = qpmLimit;
        entity.createTime = now;
        entity.updateTime = now;
        return entity;
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

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public int getQpmLimit() {
        return qpmLimit;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void update(boolean enabled, LocalDateTime expireTime, int qpmLimit, LocalDateTime now) {
        this.enabled = enabled;
        this.expireTime = expireTime;
        this.qpmLimit = qpmLimit;
        this.updateTime = now;
    }

    public boolean isExpired(LocalDateTime now) {
        return expireTime != null && !expireTime.isAfter(now);
    }
}
