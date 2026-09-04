package com.liang.gateway.ai.internal.mcp.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "mcp_server")
public class McpServerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "path", nullable = false, unique = true, length = 64)
    private String path;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    protected McpServerEntity() {}

    public static McpServerEntity create(
            String code,
            String name,
            String path,
            String description,
            String version,
            boolean enabled,
            LocalDateTime now) {
        McpServerEntity entity = new McpServerEntity();
        entity.code = code;
        entity.name = name;
        entity.path = path;
        entity.description = description;
        entity.version = version;
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

    public String getPath() {
        return path;
    }

    public String getDescription() {
        return description;
    }

    public String getVersion() {
        return version;
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
            String name, String path, String description, String version, boolean enabled, LocalDateTime now) {
        this.name = name;
        this.path = path;
        this.description = description;
        this.version = version;
        this.enabled = enabled;
        this.updateTime = now;
    }
}
