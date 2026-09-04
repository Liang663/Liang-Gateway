# Spec｜MCP 协议转换

> 定位：ai 内 MCP 子域的长期真相源。表见 `docs/sql-gateway-mcp.md`。Chat 见 `docs/spec-gateway-ai.md`。二者同属 Modulith 模块 `ai`，互不调用、不共享限额。

## 0. 元信息

- 文档类型：Spec
- 文档状态：生效
- 业务分类：网关
- 业务主题：MCP
- 更新时间：2026-09-03
- 关联：`docs/sql-gateway-mcp.md`、`docs/plan-gateway-mcp.md`、`docs/references/domains.md`、`docs/v1-plan.md`、`docs/spec-gateway-ai.md`

## 1. 背景与目标

- 背景：把已有 HTTP API 暴露成 MCP 工具，给 Agent 调。这与 Chat 调大模型不是同一条业务：没有模型、没有 usage、没有金额窗。只是都放在 `ai` 模块里，内部用子包切开。
- 目标：实现 MCP **`2026-07-28`** 的无会话 Streamable HTTP：`server/discover`、`tools/list`、`tools/call`；按配置把 `arguments` 填进 path/query/header/body，经 core 打业务 HTTP。工具失败必须返回可排查的原因，不能只给状态码。

## 2. 范围

### 纳入范围

- 协议：仅 `2026-07-28`。无 `initialize`、无 `Mcp-Session-Id`、无 GET SSE。
- 传输：`POST /mcp/{path}`（`path` 为 `mcp_server.path`）；一次一条 JSON-RPC；第一版响应 `application/json`。
- 工具目录与参数（`mcp_server` / `mcp_tool.args`）、管理 CRUD、OpenAPI 导入。
- `tools/call` 组出站请求；包装上游结果或失败原因。

### 不纳入范围

- Chat、模型目录、出站 Key、usage、TTFT、`llm_call_log`（`spec-gateway-ai`）。
- QPM、金额窗口、`checkQuota`、`usage_record`、令牌–工具授权。第一版 MCP **不限并发、不算限额**。
- 进门鉴权（access Security，编排可仍要求访问令牌，但不得因此去查额度）。
- 反向代理拷流（core）。数据面 HTTP 入口（orchestration）。
- MRTR、`subscriptions/listen`、prompts/resources、legacy 握手、远程 MCP 反代、stdio、OAuth。

### 依赖领域

- 无。

### 禁止依赖领域

- `core`、`access`、`orchestration`。不得返回 `core.Upstream`。不得 import Chat 子包。

## 3. 公开能力

- **发现：** 给出本服务器版本、仅 `tools` 能力、身份。
- **工具目录：** 列出已启用工具及由 `args` 生成的 inputSchema。
- **组调用：** 校验参数后给出出站 URL/方法/头/body（或给出带原因的失败结果，不必出站）。
- **包装结果：** 把上游 HTTP 状态与正文收成 MCP `tools/call` 结果；失败带原因文本。
- **工具管理：** 维护服务器与工具；OpenAPI 导入生成工具行。

不公开：鉴权、限额、通用拷流、Chat 计价。

## 4. 需求重点

- Chat 与 MCP 两套公开 API、两套表，编排两条链。MCP 链 **禁止** 调用 `checkQuota` / `recordUsage`。
- 只支持 `2026-07-28`。其它版本 400，并列出 `supported: ["2026-07-28"]`。未实现的 method（含 `initialize`）HTTP 404 + JSON-RPC `-32601`。
- `tools/call` 失败必须让 Agent 读到**具体原因**，禁止只回一个数字状态码。至少覆盖：工具不存在或未启用、缺少必填参数、参数类型不对、未声明的多余参数、path 占位与参数对不上、上游超时、上游非 2xx（含状态短语与响应正文摘要）。
- 入站访问令牌不得默认转到业务 HTTP。
- 不谎称 prompts/resources。

## 5. 设计重点

- 子包 `ai.mcp`，公开 `McpApi`，与 `ChatApi` 并列。
- 数据面：编排过 Security（不查额度）后，传输头（Origin、`MCP-Protocol-Version`、`Mcp-Method`、`Mcp-Name`）由编排校验；JSON-RPC method/params/`arguments` 由本领域校验。discover/list 当场返回。call 要么给出站描述供编排调 core，要么直接给出带原因的失败 result（缺参等不必出站）。core 返回后本领域再包装。
- 协议/传输错误用 JSON-RPC `error`（版本、头不一致、方法不存在）。**工具层**错误用 `tools/call` 的 `isError=true` 与 `content[].text`，方便 Agent 当工具结果阅读。
- 失败文本带稳定前缀 + 中文说明，例如 `missing_argument: 缺少参数 id`；上游失败带 HTTP 状态与正文截断，不得只写 `404`。
- `ttlMs=0`、`cacheScope=private`、`resultType=complete`。表结构以 SQL 为准。

## 6. 领域规约

- 不得 import `core` / `access` / `orchestration` / `ai` 的 Chat 子包。
- 不得把入站密钥写入出站头或失败文本。
- 结构验证：ai 无出边；mcp 子包不依赖 chat 子包。

## 7. 验收标准

- 无 session、无 `initialize` 仍能 `discover` / `list` / `call`。
- MCP 路径即使令牌金额窗已满也不 429（未调限额）。
- 缺参、错类型、工具禁用时，Agent 能从 `content` 读出原因，而不是只看到状态码。
- 上游 4xx/5xx 的 `content` 含状态与正文摘要。
- 入站 Key 不出现在出站头。Modulith：ai 无出边。
