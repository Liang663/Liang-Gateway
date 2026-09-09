package com.liang.gateway.ai.internal.mcp.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("mcp_server")
public class McpServerEntity extends PersistableRow {

    @Column("code")
    private String code;

    @Column("name")
    private String name;

    @Column("path")
    private String path;

    @Column("description")
    private String description;

    @Column("version")
    private String version;

    @Column("enabled")
    private boolean enabled;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
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
