package com.liang.gateway.ai.internal.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_model")
public class LlmModelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "apikey_code", nullable = false, length = 64)
    private String apikeyCode;

    @Column(name = "input_price_fen_per_million", nullable = false)
    private long inputPriceFenPerMillion;

    @Column(name = "output_price_fen_per_million", nullable = false)
    private long outputPriceFenPerMillion;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    protected LlmModelEntity() {}

    public static LlmModelEntity create(
            String code,
            String name,
            String provider,
            String apikeyCode,
            long inputPriceFenPerMillion,
            long outputPriceFenPerMillion,
            boolean enabled,
            LocalDateTime now) {
        LlmModelEntity entity = new LlmModelEntity();
        entity.code = code;
        entity.name = name;
        entity.provider = provider;
        entity.apikeyCode = apikeyCode;
        entity.inputPriceFenPerMillion = inputPriceFenPerMillion;
        entity.outputPriceFenPerMillion = outputPriceFenPerMillion;
        entity.enabled = enabled;
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

    public String getApikeyCode() {
        return apikeyCode;
    }

    public long getInputPriceFenPerMillion() {
        return inputPriceFenPerMillion;
    }

    public long getOutputPriceFenPerMillion() {
        return outputPriceFenPerMillion;
    }

    public boolean isEnabled() {
        return enabled;
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
            String apikeyCode,
            long inputPriceFenPerMillion,
            long outputPriceFenPerMillion,
            boolean enabled,
            LocalDateTime now) {
        this.name = name;
        this.provider = provider;
        this.apikeyCode = apikeyCode;
        this.inputPriceFenPerMillion = inputPriceFenPerMillion;
        this.outputPriceFenPerMillion = outputPriceFenPerMillion;
        this.enabled = enabled;
        this.updateTime = now;
    }
}
