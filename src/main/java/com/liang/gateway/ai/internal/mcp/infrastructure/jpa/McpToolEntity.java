package com.liang.gateway.ai.internal.mcp.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "mcp_tool")
public class McpToolEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "server_code", nullable = false, length = 64)
    private String serverCode;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", nullable = false, length = 512)
    private String description;

    @Column(name = "http_url", nullable = false, length = 512)
    private String httpUrl;

    @Column(name = "http_method", nullable = false, length = 16)
    private String httpMethod;

    @Column(name = "http_headers", columnDefinition = "TEXT")
    private String httpHeaders;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @Column(name = "args", nullable = false, columnDefinition = "TEXT")
    private String args;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
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

    public Long getId() {
        return id;
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
