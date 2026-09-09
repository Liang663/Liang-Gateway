package com.liang.gateway.access.internal.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("user")
public class UserEntity extends PersistableRow {

    @Column("code")
    private String code;

    @Column("name")
    private String name;

    @Column("authority")
    private String authority;

    @Column("enabled")
    private boolean enabled;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
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
