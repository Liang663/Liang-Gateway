package com.liang.gateway.access.internal.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "user")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "authority", nullable = false, length = 256)
    private String authority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    protected UserEntity() {}

    public static UserEntity create(
            String code, String name, String authority, boolean enabled, LocalDateTime now) {
        UserEntity entity = new UserEntity();
        entity.code = code;
        entity.name = name;
        entity.authority = authority;
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

    public String getAuthority() {
        return authority;
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

    public void update(String name, String authority, boolean enabled, LocalDateTime now) {
        this.name = name;
        this.authority = authority;
        this.enabled = enabled;
        this.updateTime = now;
    }
}
