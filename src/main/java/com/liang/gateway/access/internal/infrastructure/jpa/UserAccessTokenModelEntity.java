package com.liang.gateway.access.internal.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_access_token_model")
public class UserAccessTokenModelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_code", nullable = false, length = 64)
    private String tokenCode;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    protected UserAccessTokenModelEntity() {}

    public static UserAccessTokenModelEntity create(String tokenCode, String model, LocalDateTime createTime) {
        UserAccessTokenModelEntity entity = new UserAccessTokenModelEntity();
        entity.tokenCode = tokenCode;
        entity.model = model;
        entity.createTime = createTime;
        return entity;
    }

    public Long getId() {
        return id;
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
