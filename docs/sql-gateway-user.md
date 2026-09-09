# SQL｜用户、访问令牌、模型、用量、出站日志

> 文件名：`sql-gateway-user.md`  
> 定位：这些表的结构、关系、约束真相源。执行源是 Flyway。运行时访问是 R2DBC。

## 1. 背景与范围

- 覆盖：`user`、`user_access_token`、`user_access_token_model`、`usage_limit`、`usage_record`、`llm_apikey_config`、`llm_model`、`llm_call_log`
- 归属：前五张 → access；`llm_apikey_config`、`llm_model`、`llm_call_log` → ai
- 关联：`docs/spec-gateway-access.md`、`docs/spec-gateway-ai.md`
- 不覆盖：MCP 表（见 `docs/sql-gateway-mcp.md`）、额度 Redis 键结构（判超限仍读 Redis，见字段 `usage_limit.used`）

关系：

```
user 1 ──< M user_access_token 1 ──< M user_access_token_model
user_access_token 1 ──< M usage_limit
user_access_token 1 ──< M usage_record

llm_apikey_config 1 ──< M llm_model
llm_apikey_config 1 ──< M llm_call_log
```

令牌授权的是 **模型名字符串**，不对 `llm_model` 建外键（access 不依赖 ai）。

金额：整数 **分**（人民币，1 元 = 100 分）。限额、已用、明细、单价同一单位。

约定：`id` 自增物理主键；`code` 逻辑主键不对外；调用方亮 `access_token`。

## 2. 数据对象

| 对象 | 职责 | 生命周期 |
|---|---|---|
| `user` | 用户身份 | 管理维护 |
| `user_access_token` | 凭证、QPM | 随用户 |
| `user_access_token_model` | 令牌可用的模型名 | 随令牌 |
| `usage_limit` | 金额窗口限额与当前窗已用镜像 | 随令牌 |
| `usage_record` | 每笔 Token + 金额 | 只追加 |
| `llm_apikey_config` | 出站 Key | ai 维护 |
| `llm_model` | 模型名、供应商、单价、绑哪把 Key | ai 维护 |
| `llm_call_log` | 出站调用日志（成败、首字耗时、说明） | 只追加（结束时可补 `update_time`） |

## 3. 表结构

现行目标结构（空库可用 V1+V2 到达；V1 见仓库 `V1__user_apikey_usage.sql`）：

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
    `name`          VARCHAR(128) NOT NULL,
    `provider`      VARCHAR(32)  NOT NULL,
    `base_url`      VARCHAR(256) NOT NULL,
    `secret`        VARCHAR(512) NOT NULL COMMENT '第一版明文',
    `prefix`        VARCHAR(16)  NOT NULL,
    `enabled`       TINYINT      NOT NULL DEFAULT 1,
    `expire_time`   DATETIME(3)  NULL,
    `create_time`   DATETIME(3)  NOT NULL,
    `update_time`   DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_apikey_code` (`code`)
) COMMENT '大模型 API Key 配置';

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

CREATE TABLE `user_access_token` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `code`          VARCHAR(64)  NOT NULL COMMENT '逻辑主键，内部与 Redis 用',
    `user_code`     VARCHAR(64)  NOT NULL,
    `access_token`  VARCHAR(128) NOT NULL,
    `enabled`       TINYINT      NOT NULL DEFAULT 1,
    `expire_time`   DATETIME(3)  NULL,
    `qpm_limit`     INT          NOT NULL COMMENT '每分钟请求次数',
    `create_time`   DATETIME(3)  NOT NULL,
    `update_time`   DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uat_code` (`code`),
    UNIQUE KEY `uk_uat_access_token` (`access_token`),
    KEY `idx_uat_user_code` (`user_code`),
    CONSTRAINT `fk_uat_user` FOREIGN KEY (`user_code`) REFERENCES `user` (`code`)
) COMMENT '用户访问令牌';

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

CREATE TABLE `usage_record` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `code`               VARCHAR(64)  NOT NULL,
    `token_code`         VARCHAR(64)  NOT NULL,
    `user_code`          VARCHAR(64)  NOT NULL,
    `model`              VARCHAR(64)  NOT NULL COMMENT '模型名',
    `prompt_tokens`      BIGINT       NOT NULL,
    `completion_tokens`  BIGINT       NOT NULL,
    `total_tokens`       BIGINT       NOT NULL,
    `amount_fen`         BIGINT       NOT NULL COMMENT '本笔金额，分',
    `request_id`         VARCHAR(64)  NULL,
    `create_time`        DATETIME(3)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_usage_code` (`code`),
    KEY `idx_usage_token_time` (`token_code`, `create_time`),
    KEY `idx_usage_user_time` (`user_code`, `create_time`),
    KEY `idx_usage_model_time` (`model`, `create_time`),
    CONSTRAINT `fk_usage_token` FOREIGN KEY (`token_code`) REFERENCES `user_access_token` (`code`),
    CONSTRAINT `fk_usage_user` FOREIGN KEY (`user_code`) REFERENCES `user` (`code`)
) COMMENT '用量明细：Token 与金额';

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
```

## 4. 字段语义与约束

### user_access_token

| 字段 | 语义 | 约束 |
|---|---|---|
| `qpm_limit` | 每分钟请求次数 | 与金额窗同时生效 |
| 已删除 | `apikey_code`、`hourly_token_limit`、`weekly_token_limit`、`hourly_limit`、`weekly_limit` | V2 去掉；金额限额改到 `usage_limit` |

### usage_limit

| 字段 | 语义 | 约束 |
|---|---|---|
| `limit_type` | `0` 金额不限额；`1` 五小时窗；`2` 七天窗（时长 7 天，不是日历周） | 同一令牌 `(token_code, limit_type)` 唯一 |
| `usage` | 该窗口金额上限 | 整数分；`limit_type=0` 时为 0 |
| `used` | 当前窗口已用 | 整数分；判超限读 Redis；记账时把 Redis 新值回写本列 |

同一令牌：要么只有 `limit_type=0` 一行，要么有 `1` 和/或 `2`，不要 0 与 1/2 并存。`1` 与 `2` 可同时存在，两扇窗都生效。

### user_access_token_model

| 字段 | 语义 | 约束 |
|---|---|---|
| `model` | 请求里允许的模型名 | 与 ai 目录对齐靠编排，不靠 FK |

### llm_model

| 字段 | 语义 | 约束 |
|---|---|---|
| `name` | 调用方传入的 model | 全局唯一 |
| `input_price_fen_per_million` / `output_price_fen_per_million` | 官网价折合分 / 百万 Token | 第一版按录入时官网价 |

### usage_record

| 字段 | 语义 | 约束 |
|---|---|---|
| `amount_fen` | 本笔实收金额 | 整数分；由 ai 算好经编排写入 |
| `model` | 模型名 | 必填；调用方统计按此分组 |

只记拿到 usage、要收钱的成功单。进门拒绝、未出站、出站失败不写本表。

### llm_call_log

| 字段 | 语义 | 约束 |
|---|---|---|
| `llm_apikey_code` | 出站 Key | 不是访问令牌 |
| `success` | 是否出站成功且可记账 | `1` / `0` |
| `message` | 失败或补充说明 | 不含密钥、不含堆栈；业务不要解析本列做分支 |
| `first_token_ms` | 发出到第一帧 | 无第一帧为 NULL；平均 TTFT 只统计非 NULL |
| `total_duration_ms` | 发出到结束 | 必填 |

第一帧前调用方取消：不写本表。第一帧后断开仍写（有 usage 则 `success=1`，否则 `success=0` 并填 `message`）。

不建按小时/日预聚合表。调用方日历统计扫 `usage_record`；出站 TTFT / 故障率扫 `llm_call_log`。

## 5. 索引与查询边界

- 鉴权仍按 `access_token`。
- 授权：`(token_code, model)` 唯一。
- 限额：`(token_code, limit_type)` 唯一。判超限读 Redis（`used` 为分）；本笔金额一次 `HINCRBY`，不是按分循环加。然后将 Redis 新 `used` 写入 `usage_limit.used`。窗口到期清零时同样回写 `0`。
- 调用方统计：`idx_usage_token_time`；按模型分组可再走时间范围。
- 出站统计：`idx_lcl_apikey_time`、`idx_lcl_apikey_model_time`。故障率 = `success=0` 行 / 本表行数（未写日志的取消不进分母）。

## 6. 迁移

- V1：仓库已有 `V1__user_apikey_usage.sql`（Token 限额列在令牌上 + 令牌绑 Key）。
- V2（建议 `V2__billing_and_model.sql`）：
  - `user_access_token`：删 `fk_uat_apikey` 与 `apikey_code`；删 `hourly_token_limit`、`weekly_token_limit`（不要改名为 `hourly_limit` / `weekly_limit`）。
  - 建 `usage_limit`、`user_access_token_model`、`llm_model`、`llm_call_log`。
  - `usage_record`：加 `amount_fen` NOT NULL DEFAULT 0；`model` 改为 NOT NULL（空库可直接改；有 NULL 先回填 `unknown`）。
  - 无生产脏数据：不必从旧限额列回填 `usage_limit`；种子或管理接口重新写入。
- 回滚：DROP 新表；令牌表加回旧列。

## 7. 验证

- 不能两行同一 `access_token` 或同一 `llm_model.name`。
- 同一令牌不能重复授权同一模型名。
- 同一令牌同一 `limit_type` 不能两行。
- `usage_record.total_tokens = prompt + completion`。
- 业务代码不用 `id` 当令牌或出站 Key 标识。
