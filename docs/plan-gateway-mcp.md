# Plan｜落地 ai MCP 第一版

> 编码会话执行。口径以 `docs/spec-gateway-mcp.md` 为准。表以 `docs/sql-gateway-mcp.md` 为准。只实现 MCP 协议 API 与管理面，不要写数据面 `POST /{path}/mcp`，不要 import access/core，不要改 Chat。

## 文档信息

- 文档类型：Plan
- 文档状态：已完成
- 业务分类：网关
- 业务主题：MCP
- 更新时间：2026-09-04
- 上游依据：`docs/spec-gateway-mcp.md`、`docs/sql-gateway-mcp.md`、`docs/references/domains.md`
- 前置：Chat 已落地（`plan-gateway-ai.md`）；本 Plan 不依赖 Chat 代码
- 完成后：公开类型名若变，回流 Spec

## 1. 文档定位

- 回答：ai 如何提供 `server/discover`、`tools/list`、`tools/call` 组出站与失败原因、工具管理与 OpenAPI 导入。
- 不实现 orchestration 的 MCP HTTP 入口（传输头 Origin / `MCP-Protocol-Version` 等由编排会话做）。
- 不实现 Chat、不调 `checkQuota`。

## 2. 需求

- 做：Flyway V3；`mcp_server` / `mcp_tool` 仓储；`McpApi`；按 `args.position` 组 HTTP；工具层失败写入 `isError` 文本；管理 CRUD；OpenAPI 导入。
- 不做：数据面 Controller、core 反代、QPM/金额、session、`initialize`、legacy SSE、prompts/resources、Chat 子包。
- 待澄清：无。

已采纳：路径用 `path` 不是 `name`；参数用 `mcp_tool.args` JSON，不建 `mcp_tool_arg`；协议只 2026-07-28；MCP 与 Chat 并列、不共享限额；失败原因给 Agent 看，不只状态码。

## 3. 背景

- Chat / access billing 已提交。`ai` 已有 `ChatApi`。
- MCP 是另一条链：HTTP API → 工具，无模型 usage。

## 4. 目标

- `mvn test` 全绿。`ai.mcp` 不 import `ai` 的 Chat 类型。ai 无 core/access 出边。

## 5. 技术方案

**公开面 `McpApi`（包 `com.liang.gateway.ai`，实现在 `ai.internal` 的 mcp 子包）：**

- `discover(serverPath)`：启用中的 `mcp_server`。返回 JSON-RPC result：`supportedVersions: ["2026-07-28"]`、`capabilities.tools: {}`、`_meta.serverInfo`（表 `name`/`version`）、可选 `instructions`、`resultType: complete`、`ttlMs: 0`、`cacheScope: private`。path 不存在或未启用 → 调用方视为服务器不存在（测试断言明确失败类型，编排映射 404）。
- `listTools(serverPath)`：该 server 下 `enabled=1` 的工具，按 `name` 排序。`inputSchema` 由 `args` 生成（根 `object`）。同样带 `resultType` / `ttlMs` / `cacheScope`。
- `prepareCall(serverPath, toolName, arguments)`：
  - 工具不存在或未启用 → 已完成的 `tools/call` result，`isError=true`，文本 `tool_unavailable: ...`，**不出站**。
  - 缺必填 / 类型不符 / 多余键 / path 占位对不上 → `missing_argument` / `argument_type` / `unknown_argument` / `path_mismatch`，不出站。
  - 通过则返回出站描述：`url`、`httpMethod`、`headers`、`body`（无 body 参数则为空）、`timeoutMs`。禁止带入站 `Authorization`。
- `wrapCall(httpStatus, responseBody, timedOut)`：超时 → `upstream_timeout: 上游超时`。非 2xx → `upstream_error: HTTP {status} {reason}; body={截断正文}`。2xx → `isError=false`，`content` 为上游正文。禁止只回状态码数字。

失败文本：稳定前缀 + 中文，前缀固定为上列英文蛇形。正文截断建议 512 字符。密钥不得出现在文本里。

`McpUpstream`：`url`、`httpMethod`、`headers`、`body`、`timeoutMs`。禁止引用 `core.Upstream`。

JSON-RPC 的 method 分发、`initialize` → `-32601`、版本不是 `2026-07-28` → 由编排做头校验；本领域对 `params._meta.protocolVersion` 若存在则须为 `2026-07-28`，否则返回带 `supported` 的版本错误 result/error（单测覆盖）。本 Plan 不写 HTTP 状态码映射。

**数据：** Entity 覆盖 `mcp_server`、`mcp_tool`。Flyway `V3__mcp_server_tool.sql` 按 SQL。JPA `boundedElastic`。`args` / `http_headers` 存 JSON 字符串。

**管理 HTTP：** `/admin/mcp/servers`、`/admin/mcp/servers/{code}/tools`；导入 `POST .../tools:import`（swagger JSON + 勾选 path）。`X-Admin-Token` 复用 access 已对 `/admin/**` 的过滤器。保存时校验 `args`（`position` 枚举、path 与 `{name}` 一致、`name` 不重复）。

**OpenAPI：** `parameters.in` → `position`；`requestBody` 顶层 properties → `position=body`（嵌套放在该 arg 的 `properties`）；`servers.url` + path 模板 → `http_url`。不存 swagger 原文。

**测试：** 不打真实业务 HTTP。discover/list 对表；prepareCall 断言 URL/query/header/body 与失败前缀；wrapCall 断言非 2xx 含 body 摘要；导入一份最小 swagger 能生成工具；mcp 测试不调用 `ChatApi` / `checkQuota`。

## 6. 领域规划

- 只填 `ai`，依赖 `{}`。子包 `mcp`，不拆 Modulith 模块，不依赖 `chat`。

## 7. 前置条件

- SQL 已写 `mcp_server` / `mcp_tool`。
- Chat 已在 master；本 Plan 不改 V2 与 Chat 测试。

## 8. 拆分原则

- 先表，再 discover/list，再 prepareCall/wrapCall，再管理与导入。
- 不写 orchestration Controller，不把 MCP 挂进 Chat 过滤器。

## 9. 实施计划

| 序号 | 任务 | 主要内容 | 前置 | 完成标准 |
| --- | --- | --- | --- | --- |
| 1 | Flyway 与仓储 | V3；Entity/Repository | 无 | 按 path 能查到 server，按 name 能查到 tool |
| 2 | discover / list | schema 从 `args` 生成 | 1 | 禁用 server/tool 不出现；list 顺序稳定 |
| 3 | prepareCall | position 填 HTTP；缺参等 isError 不出站 | 2 | path/query/header/body 用例绿；失败前缀固定 |
| 4 | wrapCall | 2xx 正文；非 2xx/超时带原因 | 3 | 不只回状态码 |
| 5 | 管理与导入 | CRUD；OpenAPI 勾 path 生成行 | 1 | 导入后 list 能看到工具 |
| 6 | 停 | 不写 MCP HTTP 入口、不调 access 额度 | 5 | mcp 不 import chat/core/access |

建议类型：`McpApi`、`McpUpstream`、`McpCallPrepare`（出站或已完成的失败 result）。

## 10. 门禁

- A（1–4）：discover/list/call 单测绿，失败文本含前缀。
- B（5–6）：管理可配；verify 通过。未过不开始 orchestration MCP 入口。

## 11. 验证

- `mvn test`

## 12. 风险

- 把 `checkQuota` 抄进 MCP 链。
- prepareCall 把入站 Authorization 带出去。
- wrapCall 只写 `404` 不带 body。
- 引用 `core.Upstream` 或 Chat 类型。
- 实现 `initialize` / session「图个兼容」。

## 13. 后置吸收

- 公开类型名若变，改 Spec。

## 14. 当前状态

- 状态：已实现（编码完成，待 orchestration POST）
- 下一步：orchestration 的 MCP `POST /{path}/mcp` 入口

## 15. 执行要求

- 不要 Lombok。不要 import access/core/chat。禁止提交真实 Key。
