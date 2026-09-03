ALTER TABLE `user_access_token` DROP FOREIGN KEY `fk_uat_apikey`;
ALTER TABLE `user_access_token` DROP INDEX `idx_uat_apikey_code`;
ALTER TABLE `user_access_token`
    DROP COLUMN `apikey_code`,
    DROP COLUMN `hourly_token_limit`,
    DROP COLUMN `weekly_token_limit`;

CREATE TABLE `user_access_token_model` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `token_code`  VARCHAR(64) NOT NULL,
    `model`       VARCHAR(64) NOT NULL COMMENT '模型名，不对 llm_model 建外键',
    `create_time` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uat_model` (`token_code`, `model`),
    KEY `idx_uat_model_token` (`token_code`),
    CONSTRAINT `fk_uat_model_token` FOREIGN KEY (`token_code`) REFERENCES `user_access_token` (`code`)
) COMMENT '令牌可用模型名';

CREATE TABLE `usage_limit` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_code`   VARCHAR(64)  NOT NULL,
    `token_code`  VARCHAR(64)  NOT NULL,
    `limit_type`  TINYINT      NOT NULL COMMENT '0=金额不限额；1=五小时窗；2=七天窗',
    `usage`       BIGINT       NOT NULL COMMENT '该窗口金额上限，单位分；limit_type=0 时为 0',
    `used`        BIGINT       NOT NULL DEFAULT 0 COMMENT '当前窗口已用，单位分；热路径以 Redis 为准，写时回写',
    `create_time` DATETIME(3)  NOT NULL,
    `update_time` DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_usage_limit_token_type` (`token_code`, `limit_type`),
    KEY `idx_usage_limit_user` (`user_code`),
    CONSTRAINT `fk_usage_limit_token` FOREIGN KEY (`token_code`) REFERENCES `user_access_token` (`code`),
    CONSTRAINT `fk_usage_limit_user` FOREIGN KEY (`user_code`) REFERENCES `user` (`code`)
) COMMENT '调用方金额窗口限额';

ALTER TABLE `usage_record`
    ADD COLUMN `amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '本笔金额，分' AFTER `total_tokens`;

UPDATE `usage_record` SET `model` = 'unknown' WHERE `model` IS NULL;

ALTER TABLE `usage_record`
    MODIFY COLUMN `model` VARCHAR(64) NOT NULL COMMENT '模型名';

ALTER TABLE `usage_record`
    ADD KEY `idx_usage_model_time` (`model`, `create_time`);

CREATE TABLE `llm_model` (
    `id`                           BIGINT       NOT NULL AUTO_INCREMENT,
    `code`                         VARCHAR(64)  NOT NULL COMMENT '逻辑主键',
    `name`                         VARCHAR(64)  NOT NULL COMMENT '请求里的 model，如 deepseek-v4-flash',
    `provider`                     VARCHAR(32)  NOT NULL,
    `apikey_code`                  VARCHAR(64)  NOT NULL,
    `input_price_fen_per_million`  BIGINT       NOT NULL COMMENT '官方输入价，分/百万 Token',
    `output_price_fen_per_million` BIGINT       NOT NULL COMMENT '官方输出价，分/百万 Token',
    `enabled`                      TINYINT      NOT NULL DEFAULT 1,
    `create_time`                  DATETIME(3)  NOT NULL,
    `update_time`                  DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_model_code` (`code`),
    UNIQUE KEY `uk_llm_model_name` (`name`),
    KEY `idx_llm_model_apikey` (`apikey_code`),
    CONSTRAINT `fk_llm_model_apikey` FOREIGN KEY (`apikey_code`) REFERENCES `llm_apikey_config` (`code`)
) COMMENT '模型目录';

CREATE TABLE `llm_call_log` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `code`               VARCHAR(64)   NOT NULL COMMENT '逻辑主键',
    `llm_apikey_code`    VARCHAR(64)   NOT NULL COMMENT '出站 Key，llm_apikey_config.code',
    `model`              VARCHAR(64)   NOT NULL COMMENT '出站模型名',
    `success`            TINYINT       NOT NULL COMMENT '1=出站成功且拿到可记账 usage',
    `message`            VARCHAR(512)  NULL COMMENT '失败或补充说明，不含密钥',
    `first_token_ms`     INT           NULL COMMENT '发出请求到第一帧的毫秒；无第一帧为空',
    `total_duration_ms`  INT           NOT NULL COMMENT '发出请求到结束的毫秒',
    `create_time`        DATETIME(3)   NOT NULL,
    `update_time`        DATETIME(3)   NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_call_log_code` (`code`),
    KEY `idx_lcl_apikey_time` (`llm_apikey_code`, `create_time`),
    KEY `idx_lcl_apikey_model_time` (`llm_apikey_code`, `model`, `create_time`),
    CONSTRAINT `fk_lcl_apikey` FOREIGN KEY (`llm_apikey_code`) REFERENCES `llm_apikey_config` (`code`)
) COMMENT '大模型出站调用日志';
