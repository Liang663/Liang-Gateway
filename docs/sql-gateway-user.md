# SQL｜用户、访问令牌、大模型 Key、用量明细

> 文件名：`sql-gateway-user.md`  
> 定位：这些表的结构、关系、约束真相源。执行源以后是 Flyway；未落迁移前以本文为准。

## 1. 背景与范围

- 覆盖：`user`、`user_access_token`、`llm_apikey_config`、`usage_record`
- 归属：`user`、`user_access_token`、`usage_record` → access；`llm_apikey_config` → ai
- 关联：`docs/spec-gateway-access.md`
- 不覆盖：MCP 表、额度 Redis 键（键形态见 `docs/plan-gateway-access.md`）

关系：

```
user 1 ──< M user_access_token M >── 1 llm_apikey_config
```

- 一个用户多把访问令牌
- 多把访问令牌可共用一把大模型 Key

约定：

- `id`：库自增物理主键，业务代码不拿它当对外标识
- `code`：全局唯一**逻辑主键**，仅内部关联（外键、Redis），**不对调用方暴露**
- 调用方进网关亮出来的是 `user_access_token.access_token`（Bearer / `api-key` / query）

## 2. 数据对象

| 对象 | 职责 | 生命周期 |
|---|---|---|
| `user` | 用户身份、名称、权限 | 管理接口维护 |
| `user_access_token` | 访问凭证、套餐限额；创建时打开当前五小时/七天窗口 | 随用户；可单独作废、过期 |
| `llm_apikey_config` | 出站调大模型用的 Key | 由 ai 侧维护；可被多把访问令牌引用 |
| `usage_record` | 每一笔 Token 用量明细 | 只追加；统计从本表聚合 |

## 3. 表结构

```sql
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
```

## 4. 字段语义与约束

### user

| 字段 | 语义 | 约束 |
|---|---|---|
| `id` | 物理主键 | 自增，不对外、不作 Redis 键 |
| `code` | 逻辑主键 | 全局唯一；只给内部关联 |
| `name` | 显示名 | |
| `authority` | 权限 | 第一版字符串，如 `DATA`、`DATA,ADMIN` |
| `enabled` | 是否启用 | 0 则名下所有访问令牌不能进网关 |
| `create_time` / `update_time` | 通用时间 | |

### user_access_token

| 字段 | 语义 | 约束 |
|---|---|---|
| `id` | 物理主键 | 自增 |
| `code` | 逻辑主键 | 全局唯一；额度 Redis 键用它；**不是** Bearer |
| `user_code` | 所属用户 | 多对一 `user.code` |
| `access_token` | 对外凭证 | 全局唯一；调用方 Bearer / `api-key` / query 与此精确匹配 |
| `apikey_code` | 出站大模型 Key | 多对一 `llm_apikey_config.code` |
| `enabled` | 此令牌是否启用 | |
| `expire_time` | 令牌过期 | NULL = 不过期 |
| `qpm_limit` | 每分钟请求次数上限 | 防刷；不是 Token |
| `hourly_token_limit` | 当前五小时窗口上限 | 字段名保留 hourly；时长 18000 秒；`start`/`used` 在 Redis |
| `weekly_token_limit` | 当前七天窗口上限 | 时长 604800 秒 |
| `create_time` | 创建时间 | 写入本行时同时打开两扇 Redis 窗口 |
| `update_time` | 通用时间 | |

### usage_record

| 字段 | 语义 | 约束 |
|---|---|---|
| `id` | 物理主键 | 自增 |
| `code` | 逻辑主键 | 全局唯一 |
| `token_code` | 哪把访问令牌 | |
| `user_code` | 所属用户 | 冗余，不替代令牌关联 |
| `prompt_tokens` / `completion_tokens` / `total_tokens` | 本笔用量 | `total = prompt + completion` |
| `model` | 模型名 | 可空；access 不解析响应 |
| `request_id` | 请求关联 | 可空 |
| `create_time` | 记账时刻 | 统计按此时刻、时区 `Asia/Shanghai` 分桶 |

### llm_apikey_config

| 字段 | 语义 | 约束 |
|---|---|---|
| `id` | 物理主键 | 自增 |
| `code` | 逻辑主键 | 全局唯一；只内部引用 |
| `name` | 配置名 | |
| `provider` | 厂商 | 第一版 `deepseek` |
| `base_url` | 上游根路径 | |
| `secret` | 调模型的完整密钥 | 第一版明文；不是访问令牌 |
| `prefix` | 展示前缀 | 如 `sk-1a2b`，不能当密钥用 |
| `enabled` | 是否启用 | 禁用则引用它的访问令牌出站应失败 |
| `expire_time` | 密钥过期 | NULL = 不过期 |
| `create_time` / `update_time` | 通用时间 | |

## 5. 索引与查询边界

- 鉴权：用调用方亮出的串查 `user_access_token.access_token`（唯一索引），再带 `user_code` 查用户、带 `apikey_code` 查出站 Key。
- 额度热路径：Redis 按 `user_access_token.code` 各一层一把 HASH（`start`+`used`），不按 `id`、不按 `access_token`。不预切段号。
- 明细 / 统计：`idx_usage_token_time`、`idx_usage_user_time`。
- 按用户列令牌：`idx_uat_user_code`。
- 第一版无缓存，鉴权每次打库。

## 6. 迁移

- 执行源：后续 Flyway，建议 `V1__user_and_apikey.sql` 按上面 DDL（含 `usage_record`）。
- 尚无回填。无旧表。

## 7. 验证

- 不能用同一 `code` 插两行用户。
- 不能用同一 `access_token` 插两行令牌。
- 令牌的 `user_code` / `apikey_code` 必须能关联到已有行。
- 业务代码与 Redis 不得使用 `id` 当调用方标识。
- 明细 `total_tokens` 必须等于 prompt+completion。
