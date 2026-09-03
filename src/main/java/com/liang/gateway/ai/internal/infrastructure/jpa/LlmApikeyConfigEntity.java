package com.liang.gateway.ai.internal.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_apikey_config")
public class LlmApikeyConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "base_url", nullable = false, length = 256)
    private String baseUrl;

    @Column(name = "secret", nullable = false, length = 512)
    private String secret;

    @Column(name = "prefix", nullable = false, length = 16)
    private String prefix;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
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

    public Long getId() {
        return id;
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
