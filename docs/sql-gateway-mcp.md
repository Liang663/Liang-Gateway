# SQL｜MCP 服务器与工具

> 文件名：`sql-gateway-mcp.md`  
> 定位：MCP 相关表的结构、关系、约束真相源。执行源是 Flyway。运行时访问是 R2DBC。

## 1. 背景与范围

- 覆盖：`mcp_server`、`mcp_tool`
- 归属：全部 → ai
- 关联：`docs/spec-gateway-mcp.md`。协议 `2026-07-28`（无 `initialize`、无协议 session）。
- 不覆盖：访问令牌（access Security）、QPM/金额（MCP 第一版不查）、Chat 模型/出站日志、MCP 调用明细、session 表、OpenAPI 原文存档、独立参数表

关系：

```
mcp_server 1 ──< M mcp_tool
```

不与 `user_access_token` / `llm_model` 建外键。数据面可要求访问令牌进门，但 **不调 QPM、不查金额窗**。工具授权第一版不做（能进网关即可调该 server 下已启用工具）。

约定：`id` 自增物理主键；`code` 逻辑主键只给内部 FK，不进 URL。数据面路径亮 `mcp_server.path`；discover 的展示名亮 `mcp_server.name`；JSON-RPC `Mcp-Name` 亮 `mcp_tool.name`。

## 2. 数据对象

| 对象 | 职责 | 生命周期 |
|---|---|---|
| `mcp_server` | 一台 MCP 服务器，对应 `POST /mcp/{path}` | 管理维护 |
| `mcp_tool` | 一个工具：出站 HTTP + 参数（schema 与落点都在 `args`） | 随服务器 |

不建：`mcp_tool_arg`、session、网关自己的 MCP Key、按字段拆行的 mapping 表、调用日志。

## 3. 表结构

现行目标结构（建议 Flyway `V3__mcp_server_tool.sql`）：

```sql
CREATE TABLE `mcp_server` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `code`          VARCHAR(64)   NOT NULL COMMENT '逻辑主键，仅内部与 FK 用',
    `name`          VARCHAR(128)  NOT NULL COMMENT 'serverInfo.name，给人看',
    `path`          VARCHAR(64)   NOT NULL COMMENT 'URL 路径段，POST /mcp/{path}',
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
```

## 4. 字段语义与约束

### mcp_server

| 字段 | 语义 | 约束 |
|---|---|---|
| `code` | 内部逻辑主键 | 全局唯一；不进 URL |
| `name` | discover / 响应里的 `serverInfo.name` | 给人看；允许中文；不必全局唯一 |
| `path` | `POST /mcp/{path}` | 全局唯一；改 path 即改地址 |
| `version` | `serverInfo.version` | 非空 |
| `description` | discover 的 `instructions` | 可空则响应不带该字段 |
| `enabled=0` | 该入口不服务 | discover/list/call 都视为不存在 |

协议版本不入库，代码写死 `2026-07-28`。`path` 须能当 URL 一段（建议字母数字、`-`、`_`，不要 `/` 和空格）。

### mcp_tool

| 字段 | 语义 | 约束 |
|---|---|---|
| `name` | `tools/call` 的 `params.name` 与头 `Mcp-Name` | 同一 server 下唯一 |
| `http_url` | 出站 URL 模板 | `position=path` 的参数必须以 `{name}` 出现在 URL 中 |
| `http_method` | 出站方法 | 仅 GET/POST/PUT/DELETE（入库大写） |
| `http_headers` | 工具上的固定出站头 | JSON 对象；禁止把入站网关 Key 抄进去 |
| `args` | 参数清单，同时服务 list 与 call | JSON 数组，见下 |
| `enabled=0` | 不出现在 list，call 当工具不存在 | |

不另存 `input_schema`。`tools/list` 由 `args` 生成 JSON Schema（根类型 `object`，`properties` / `required` 来自各元素）。`tools/call` 按各元素的 `position` 填 HTTP。

`args` 元素：

```json
[
  {
    "name": "id",
    "value_type": "string",
    "required": true,
    "description": "员工 ID",
    "position": "path"
  },
  {
    "name": "city",
    "value_type": "string",
    "required": true,
    "description": "城市",
    "position": "body"
  }
]
```

| 键 | 语义 | 约束 |
|---|---|---|
| `name` | `arguments` 顶层键 | 同一工具内不重复 |
| `value_type` | `string` / `number` / `integer` / `boolean` / `object` / `array` | 嵌套结构可放在该元素上（如 `properties`），不必再拆表 |
| `required` | 缺参则 call 失败 | `position=path` 必须为 true |
| `description` | 写入 list 的 schema | 可空 |
| `position` | 填到 HTTP 哪一段 | 只允许 `path` / `query` / `header` / `body` |

运行时：

- `path`：替换 `http_url` 中 `{name}`
- `query`：追加查询串
- `header`：出站头（可覆盖同名静态头）
- `body`：并入 JSON body；没有任何 `body` 参数则不出 body

`arguments` 里未在 `args` 出现的键视为非法。

## 5. 索引与查询边界

- 数据面：按 `mcp_server.path` 找启用服务器，再按 `(server_code, mcp_tool.name)` 找工具；参数在行内 `args`，不再 JOIN。
- `tools/list`：该 server 下 `enabled=1` 的工具，schema 从 `args` 生成。
- 管理面：按 `server_code` 列工具。
- 工具为配置量级，JSON 数组足够。

## 6. 迁移

- 执行源：Flyway，建议 `V3__mcp_server_tool.sql`（V1 用户令牌，V2 金额与 Chat 模型）。
- 空库可从 V1+V2+V3 起出。无回填。
- 本地联用演示数据：`docs/dev-ops/seed-local.sql`，不是 Flyway，只打 `liang_gateway`。
- 回滚：DROP `mcp_tool`、`mcp_server`。

## 7. 验证

- 不能两台 server 同一 `code` 或同一 `path`。
- 同一 server 不能两行同一 `mcp_tool.name`。
- 同一工具的 `args[].name` 不重复。
- `position=path` 的名字必须出现在 `http_url` 的 `{name}` 中。
- 业务代码不用 `id` 当对外标识；URL 用 `mcp_server.path`，不用 `code` 或 `name`。
