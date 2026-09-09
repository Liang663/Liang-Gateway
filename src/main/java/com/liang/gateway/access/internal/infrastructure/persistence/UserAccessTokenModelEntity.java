package com.liang.gateway.access.internal.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_access_token_model")
public class UserAccessTokenModelEntity extends PersistableRow {

    @Column("token_code")
    private String tokenCode;

    @Column("model")
    private String model;

    @Column("create_time")
    private LocalDateTime createTime;

    protected UserAccessTokenModelEntity() {}

    public static UserAccessTokenModelEntity create(String tokenCode, String model, LocalDateTime createTime) {
        UserAccessTokenModelEntity entity = new UserAccessTokenModelEntity();
        entity.tokenCode = tokenCode;
        entity.model = model;
        entity.createTime = createTime;
        return entity;
    }

    public String getTokenCode() {
        return tokenCode;
    }

    public String getModel() {
        return model;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }
}
