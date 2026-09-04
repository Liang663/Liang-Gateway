# Spec｜MCP 协议转换

> 定位：ai 内 MCP 子域的长期真相源。表见 `docs/sql-gateway-mcp.md`。Chat 见 `docs/spec-gateway-ai.md`。二者同属 Modulith 模块 `ai`，互不调用、不共享限额。

## 0. 元信息

- 文档类型：Spec
- 文档状态：生效
- 业务分类：网关
- 业务主题：MCP
- 更新时间：2026-09-04
- 关联：`docs/sql-gateway-mcp.md`、`docs/references/domains.md`、`docs/v1-plan.md`、`docs/spec-gateway-ai.md`、`docs/plan-gateway-mcp-protocol.md`

## 1. 背景与目标

- 背景：把已有 HTTP API 暴露成 MCP 工具。与 Chat 不是同一条业务：没有模型、没有 usage、没有金额窗。协议状态机曾堆在数据面，与「能力在 ai」冲突。
- 目标：MCP **`2026-07-28`** 无会话 Streamable HTTP 的 **JSON-RPC 语义**在本领域：信封、method 分发、版本、头与 body 一致性、组工具 HTTP、失败原因。数据面只做 HTTP 传输与出站捕获。

## 2. 范围

### 纳入范围

- 协议：仅 `2026-07-28`。无 `initialize` 实现、无 `Mcp-Session-Id`、无 GET SSE。未实现 method 由本领域拒绝。
- 协议入口：给定 server path、协议头字符串、JSON-RPC 正文，给出完整信封或「需要出站」；出站结束后再封 result。
- 工具目录与参数、管理 CRUD、OpenAPI 导入。
- `tools/call` 组出站请求（`McpUpstream`，不是 `core.Upstream`）；包装上游结果或失败原因。

### 不纳入范围

- Chat、模型目录、出站 Key、usage、TTFT、`llm_call_log`。
- QPM、金额窗口、`checkQuota`、`usage_record`。
- Origin / Accept / Content-Type（数据面 HTTP 传输）。进门鉴权（access）。
- 反向代理拷流（core）。数据面 HTTP 入口（orchestration）。
- Spring Web 类型（`ServerWebExchange`、`HttpHeaders`、`ResponseEntity`、`HttpStatus`）。
- MRTR、prompts/resources、legacy 握手、stdio、OAuth。

### 依赖领域

- 无。

### 禁止依赖领域

- `core`、`access`、`orchestration`。不得返回 `core.Upstream`。不得 import Chat 子包。

## 3. 公开能力

- **协议处理：** 校验 JSON-RPC 与协议头，分发 `server/discover` / `tools/list` / `tools/call`，给出信封或出站描述。
- **发现与目录：** 本服务器版本、仅 `tools` 能力；已启用工具及 inputSchema。
- **组调用与包装：** 校验参数后给出出站 URL/方法/头/body，或带原因的失败 result；把上游 HTTP 状态与正文收成 `tools/call` 结果。
- **工具管理：** 维护服务器与工具；OpenAPI 导入。

不公开：鉴权、限额、通用拷流、Chat 计价、HTTP 传输（Origin/Accept）。

## 4. 需求重点

- Chat 与 MCP 两套公开 API、两套表。MCP 链 **禁止** 调用 `checkQuota` / `recordUsage`。
- 只支持 `2026-07-28`。协议头与 `params._meta.protocolVersion`（若出现）均须为此版本。
- 协议结果带 `int` HTTP 状态提示 + JSON-RPC 对象，不含 Spring 类型。数据面原样写出该 int。

| 情况 | httpStatus | JSON-RPC |
| --- | --- | --- |
| 非法 JSON | 400 | `-32700` |
| 非单条 / 缺 jsonrpc 2.0 / `Mcp-Method` 或 `Mcp-Name` 与 body 不一致 | 400 | `-32600` |
| 版本不是 `2026-07-28` | 400 | `-32600`，`data.supported: ["2026-07-28"]` |
| 未实现 method（含 `initialize`） | 404 | `-32601` |
| server 不存在或未启用 | 404 | error |
| discover / list / call 成功或工具层 isError | 200 | `result` |

- `tools/call` 失败必须让 Agent 从 `content` 读到具体原因。至少覆盖：工具不存在或未启用、缺必填、类型不对、多余参数、path 占位不符、上游超时、上游非 2xx（状态短语与正文摘要）。
- 入站访问令牌不得进入出站描述。不谎称 prompts/resources。

## 5. 设计重点

- 子包 `ai.mcp`，公开 `McpApi`，与 `ChatApi` 并列。数据面走 `handle` / `completeCall`；`discover` / `listTools` / `prepareCall` / `wrapCall` 可保留给内部与单测。
- 协议头以纯字符串传入（`MCP-Protocol-Version`、`Mcp-Method`、`Mcp-Name`）。`NeedsOutbound` 必须带入站 JSON-RPC `id` 与 `McpUpstream`，避免数据面再拆信封。
- 协议错误用 JSON-RPC `error`。**工具层**错误用 `isError=true` 与 `content[].text`。失败文本稳定前缀 + 中文。
- `ttlMs=0`、`cacheScope=private`、`resultType=complete`。表结构以 SQL 为准。

## 6. 领域规约

- 不得 import `core` / `access` / `orchestration` / Chat 子包。
- 不得把入站密钥写入出站头或失败文本。
- 结构验证：ai 无出边；mcp 子包不依赖 chat 子包。

## 7. 验收标准

- 无 session、无 `initialize` 仍能 `discover` / `list` / `call`；`initialize` 得到 404 + `-32601`。
- 头对、`params._meta.protocolVersion` 错 → 400 且 `supported: ["2026-07-28"]`。
- 缺参等失败文本含约定前缀；上游 4xx/5xx 的 `content` 含状态与正文摘要。
- 入站 Key 不出现在出站头。Modulith：ai 无出边。
