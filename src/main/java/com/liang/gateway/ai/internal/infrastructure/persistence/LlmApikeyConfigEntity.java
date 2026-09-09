package com.liang.gateway.ai.internal.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("llm_apikey_config")
public class LlmApikeyConfigEntity extends PersistableRow {

    @Column("code")
    private String code;

    @Column("name")
    private String name;

    @Column("provider")
    private String provider;

    @Column("base_url")
    private String baseUrl;

    @Column("secret")
    private String secret;

    @Column("prefix")
    private String prefix;

    @Column("enabled")
    private boolean enabled;

    @Column("expire_time")
    private LocalDateTime expireTime;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    protected LlmApikeyConfigEntity() {}

    public static LlmApikeyConfigEntity create(
            String code,
            String name,
            String provider,
            String baseUrl,
            String secret,
            String prefix,
            boolean enabled,
            LocalDateTime expireTime,
            LocalDateTime now) {
        LlmApikeyConfigEntity entity = new LlmApikeyConfigEntity();
        entity.code = code;
        entity.name = name;
        entity.provider = provider;
        entity.baseUrl = baseUrl;
        entity.secret = secret;
        entity.prefix = prefix;
        entity.enabled = enabled;
        entity.expireTime = expireTime;
        entity.createTime = now;
        entity.updateTime = now;
        return entity;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getProvider() {
        return provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getSecret() {
        return secret;
    }

    public String getPrefix() {
        return prefix;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void update(
            String name,
            String provider,
            String baseUrl,
            String secret,
            String prefix,
            boolean enabled,
            LocalDateTime expireTime,
            LocalDateTime now) {
        this.name = name;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.secret = secret;
        this.prefix = prefix;
        this.enabled = enabled;
        this.expireTime = expireTime;
        this.updateTime = now;
    }

    public boolean isExpired(LocalDateTime now) {
        return expireTime != null && !expireTime.isAfter(now);
    }
}
