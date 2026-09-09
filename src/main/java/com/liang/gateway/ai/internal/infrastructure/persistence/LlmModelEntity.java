package com.liang.gateway.ai.internal.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("llm_model")
public class LlmModelEntity extends PersistableRow {

    @Column("code")
    private String code;

    @Column("name")
    private String name;

    @Column("provider")
    private String provider;

    @Column("apikey_code")
    private String apikeyCode;

    @Column("input_price_fen_per_million")
    private long inputPriceFenPerMillion;

    @Column("output_price_fen_per_million")
    private long outputPriceFenPerMillion;

    @Column("enabled")
    private boolean enabled;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
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
