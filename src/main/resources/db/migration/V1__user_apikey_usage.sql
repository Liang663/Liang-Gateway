CREATE TABLE `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `code`          VARCHAR(64)  NOT NULL COMMENT '逻辑主键，内部使用',
    `name`          VARCHAR(128) NOT NULL,
    `authority`     VARCHAR(256) NOT NULL COMMENT '如 DATA 或 DATA,ADMIN',
    `enabled`       TINYINT      NOT NULL DEFAULT 1,
    `create_time`   DATETIME(3)  NOT NULL,
    `update_time`   DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_code` (`code`)
) COMMENT '用户';

CREATE TABLE `llm_apikey_config` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `code`          VARCHAR(64)  NOT NULL COMMENT '逻辑主键，内部使用',
    `name`          VARCHAR(128) NOT NULL COMMENT '如 deepseek-main',
    `provider`      VARCHAR(32)  NOT NULL COMMENT '供应商',
    `base_url`      VARCHAR(256) NOT NULL,
    `secret`        VARCHAR(512) NOT NULL COMMENT '调模型的完整密钥，第一版明文',
    `prefix`        VARCHAR(16)  NOT NULL COMMENT '展示用，如 sk-1a2b',
    `enabled`       TINYINT      NOT NULL DEFAULT 1,
    `expire_time`   DATETIME(3)  NULL COMMENT '空表示不过期',
    `create_time`   DATETIME(3)  NOT NULL,
    `update_time`   DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_apikey_code` (`code`)
) COMMENT '大模型 API Key 配置';

CREATE TABLE `user_access_token` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `code`                 VARCHAR(64)  NOT NULL COMMENT '逻辑主键，内部与 Redis 用',
    `user_code`            VARCHAR(64)  NOT NULL COMMENT '关联 user.code',
    `access_token`         VARCHAR(128) NOT NULL COMMENT '对外凭证，Bearer 里那串',
    `apikey_code`          VARCHAR(64)  NOT NULL COMMENT '关联 llm_apikey_config.code',
    `enabled`              TINYINT      NOT NULL DEFAULT 1,
    `expire_time`         DATETIME(3)  NULL COMMENT '空表示不过期',
    `qpm_limit`            INT          NOT NULL COMMENT '每分钟请求次数（防刷）',
    `hourly_token_limit`   BIGINT       NOT NULL COMMENT '当前五小时窗口限额；字段名保留 hourly；窗口起点在 Redis',
    `weekly_token_limit`   BIGINT       NOT NULL COMMENT '当前七天窗口限额；窗口起点在 Redis',
    `create_time`          DATETIME(3)  NOT NULL,
    `update_time`          DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uat_code` (`code`),
    UNIQUE KEY `uk_uat_access_token` (`access_token`),
    KEY `idx_uat_user_code` (`user_code`),
    KEY `idx_uat_apikey_code` (`apikey_code`),
    CONSTRAINT `fk_uat_user` FOREIGN KEY (`user_code`) REFERENCES `user` (`code`),
    CONSTRAINT `fk_uat_apikey` FOREIGN KEY (`apikey_code`) REFERENCES `llm_apikey_config` (`code`)
) COMMENT '用户访问令牌';

CREATE TABLE `usage_record` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `code`               VARCHAR(64)  NOT NULL COMMENT '逻辑主键，内部使用',
    `token_code`         VARCHAR(64)  NOT NULL COMMENT '关联 user_access_token.code',
    `user_code`          VARCHAR(64)  NOT NULL COMMENT '关联 user.code，冗余便于按用户查',
    `prompt_tokens`      BIGINT       NOT NULL,
    `completion_tokens`  BIGINT       NOT NULL,
    `total_tokens`       BIGINT       NOT NULL COMMENT 'prompt+completion',
    `model`              VARCHAR(64)  NULL COMMENT '编排传入，可空',
    `request_id`         VARCHAR(64)  NULL,
    `create_time`        DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_usage_code` (`code`),
    KEY `idx_usage_token_time` (`token_code`, `create_time`),
    KEY `idx_usage_user_time` (`user_code`, `create_time`),
    CONSTRAINT `fk_usage_token` FOREIGN KEY (`token_code`) REFERENCES `user_access_token` (`code`),
    CONSTRAINT `fk_usage_user` FOREIGN KEY (`user_code`) REFERENCES `user` (`code`)
) COMMENT 'Token 用量明细';
