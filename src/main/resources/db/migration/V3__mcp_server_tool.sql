CREATE TABLE `mcp_server` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `code`          VARCHAR(64)   NOT NULL COMMENT '逻辑主键，仅内部与 FK 用',
    `name`          VARCHAR(128)  NOT NULL COMMENT 'serverInfo.name，给人看',
    `path`          VARCHAR(64)   NOT NULL COMMENT 'URL 路径段，POST /{path}/mcp',
    `description`   VARCHAR(512)  NULL COMMENT 'discover 的 instructions，可空',
    `version`       VARCHAR(32)   NOT NULL COMMENT 'serverInfo.version，如 1.0.0',
    `enabled`       TINYINT       NOT NULL DEFAULT 1,
    `create_time`   DATETIME(3)   NOT NULL,
    `update_time`   DATETIME(3)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mcp_server_code` (`code`),
    UNIQUE KEY `uk_mcp_server_path` (`path`)
) COMMENT 'MCP 服务器';

CREATE TABLE `mcp_tool` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `code`           VARCHAR(64)   NOT NULL COMMENT '逻辑主键',
    `server_code`    VARCHAR(64)   NOT NULL,
    `name`           VARCHAR(128)  NOT NULL COMMENT 'JSON-RPC 工具名，Mcp-Name',
    `description`    VARCHAR(512)  NOT NULL,
    `http_url`       VARCHAR(512)  NOT NULL COMMENT '可含 {argName} 供 path 替换',
    `http_method`    VARCHAR(16)   NOT NULL COMMENT 'GET/POST/PUT/DELETE',
    `http_headers`   TEXT          NULL COMMENT '静态出站头 JSON 对象，不含入站 Authorization',
    `timeout_ms`     INT           NOT NULL DEFAULT 30000,
    `args`           TEXT          NOT NULL COMMENT '参数 JSON 数组，见字段语义',
    `enabled`        TINYINT       NOT NULL DEFAULT 1,
    `create_time`    DATETIME(3)   NOT NULL,
    `update_time`    DATETIME(3)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mcp_tool_code` (`code`),
    UNIQUE KEY `uk_mcp_tool_server_name` (`server_code`, `name`),
    KEY `idx_mcp_tool_server` (`server_code`),
    CONSTRAINT `fk_mcp_tool_server` FOREIGN KEY (`server_code`) REFERENCES `mcp_server` (`code`)
) COMMENT 'MCP 工具：HTTP API';
