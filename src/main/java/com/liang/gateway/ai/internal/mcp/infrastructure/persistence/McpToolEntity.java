package com.liang.gateway.ai.internal.mcp.infrastructure.persistence;

import java.time.LocalDateTime;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("mcp_tool")
public class McpToolEntity extends PersistableRow {

    @Column("code")
    private String code;

    @Column("server_code")
    private String serverCode;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    @Column("http_url")
    private String httpUrl;

    @Column("http_method")
    private String httpMethod;

    @Column("http_headers")
    private String httpHeaders;

    @Column("timeout_ms")
    private int timeoutMs;

    @Column("args")
    private String args;

    @Column("enabled")
    private boolean enabled;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    protected McpToolEntity() {}

    public static McpToolEntity create(
            String code,
            String serverCode,
            String name,
            String description,
            String httpUrl,
            String httpMethod,
            String httpHeaders,
            int timeoutMs,
            String args,
            boolean enabled,
            LocalDateTime now) {
        McpToolEntity entity = new McpToolEntity();
        entity.code = code;
        entity.serverCode = serverCode;
        entity.name = name;
        entity.description = description;
        entity.httpUrl = httpUrl;
        entity.httpMethod = httpMethod;
        entity.httpHeaders = httpHeaders;
        entity.timeoutMs = timeoutMs;
        entity.args = args;
        entity.enabled = enabled;
        entity.createTime = now;
        entity.updateTime = now;
        return entity;
    }

    public String getCode() {
        return code;
    }

    public String getServerCode() {
        return serverCode;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getHttpUrl() {
        return httpUrl;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getHttpHeaders() {
        return httpHeaders;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public String getArgs() {
        return args;
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
            String description,
            String httpUrl,
            String httpMethod,
            String httpHeaders,
            int timeoutMs,
            String args,
            boolean enabled,
            LocalDateTime now) {
        this.name = name;
        this.description = description;
        this.httpUrl = httpUrl;
        this.httpMethod = httpMethod;
        this.httpHeaders = httpHeaders;
        this.timeoutMs = timeoutMs;
        this.args = args;
        this.enabled = enabled;
        this.updateTime = now;
    }
}
