# Spec｜编排入口（orchestration）

> 定位：数据面编排长期真相源。不拥有表。Chat 协议见 `docs/spec-gateway-ai.md`；MCP 见 `docs/spec-gateway-mcp.md`；额度见 `docs/spec-gateway-access.md`；转发见 `docs/spec-gateway-core.md`。

## 0. 元信息

- 文档类型：Spec
- 文档状态：生效
- 业务分类：网关
- 业务主题：编排
- 更新时间：2026-09-04
- 关联：`docs/plan-gateway-orchestration.md`、`docs/references/domains.md`、`docs/v1-plan.md`

## 1. 背景与目标

- 背景：`core` / `access` / `ai` 互不依赖。一次 Chat 或 MCP 必须有人按序去问额度、组上游、转发、记账。这段顺序不能写进三者任一，否则成环，或把协议和配额缠在一起。
- 目标：数据面 HTTP 只在本领域。Chat 与 MCP **两条链**，只排序调用公开 API。本领域不实现配额算法、拷流、JSON-RPC 语义、DeepSeek 细节。

## 2. 范围

### 纳入范围

- `POST /v1/chat/completions`：进门后查模型授权与金额窗，组上游，转发，记 TTFT / 出站日志与用量。
- `GET /v1/models`：令牌授权名与目录启用名的交集（无单价、无 secret）。
- `POST /mcp/{path}`：MCP `2026-07-28` 传输头校验与 JSON-RPC 信封；discover / list 直接回；call 组工具 HTTP、捕获上游、包装结果。
- 把 `ChatUpstream` / `McpUpstream` 转成 core 的通用 `Upstream`（不含 Chat/MCP 名字）。
- Chat 流式：逐帧写给客户端；打 TTFT；逐帧读 usage；客户端断开后仍 drain 直到 usage 或结束。

### 不纳入范围

- 表、Redis、过滤器链实现、WebClient 拷流、usage 解析、工具参数映射、单价。
- 管理面（仍在 access / ai）。Health（core）。
- MCP 的 QPM / 金额（不做）。legacy `initialize` / GET SSE。

### 依赖领域

- `core`、`access`、`ai`。

### 禁止依赖领域

- 无其它业务域。禁止在本领域实现上述「不纳入」。

## 3. 公开能力

- **Chat 数据面：** 对调用方提供 OpenAI 兼容聊天入口，以及该令牌实际能用的模型列表。
- **MCP 数据面：** 对 Agent 提供一台 MCP 服务器的 POST 入口。
- **编排：** 按链调用 access / ai / core；Chat 失败映射成网关 HTTP 信封（401/403/429/503 已由 access 处理）；MCP 协议错误映射成 JSON-RPC。

不公开：配额算法、反代实现、模型协议、工具映射。

## 4. 需求重点

- Chat 链必须：`assertModelAllowed` → `checkQuota` → `prepare` → 转发 → 有 usage 才 `priceUsage` / `recordUsage`，并 `recordCallLog`。缺 usage 不得记 0 成功。
- 流式强制 `include_usage` 已在 ai `prepare` 写入出站 body。本领域必须转发 **`ChatUpstream.body`**，不得把入站 body 原样交给 core。
- `recordCallLog` 使用本次 `prepare` 给出的出站 Key code。组上游产物必须带该 code（不含 secret）。
- Chat 流式禁止 `collectList`。TTFT = 发出上游到第一帧（`isFirstContentFrame`）。客户端断开后仍读上游直到 usage 或结束。第一帧之前取消且 drain 也无 usage：不写日志、不记账。
- `GET /v1/models` 只做授权名与目录启用名求交，不调 `checkQuota`。
- MCP 链必须：**不**调用 `checkQuota` / `recordUsage`。校验 Origin（配置允许列表）、`MCP-Protocol-Version`、`Mcp-Method`（与 JSON-RPC method 一致）、`tools/call` 时 `Mcp-Name` 与 `params.name` 一致、一次一条 JSON-RPC。版本只允许 `2026-07-28`。
- MCP `tools/call` 的上游响应 **不得** 当作 HTTP 响应透传给 Agent；必须 `wrapCall` 后再写 JSON-RPC。金额窗已满的令牌仍可走 MCP。
- 入站 `Authorization` 不得进入出站（core 默认不转发；本领域不得把它塞进 extraHeaders）。
- 本领域无表。

## 5. 设计重点

- 路由用 Spring 映射，不用 core 过滤器挂业务。Chat 与 MCP 两个应用服务，不要一个大 if。
- core 现有链末反代会抄入站 method/body，且客户端断开即取消上游，**不够**。编排直接调用 core 的显式转发，不走 `PipelineApi.execute` 的隐式反代：① 覆盖 method / body / timeout；② 流式边写边观察帧，客户端断开仍 drain；③ 捕获上游状态与正文、不写入入站响应（MCP，以及 Chat 非流式收齐）。类型仍是通用 `Upstream`。
- Chat 非流式允许收齐后再写回客户端；流式必须边写边观察。MCP 一律捕获，永不把业务 HTTP 状态写给 Agent。
- 数据面路径：`POST /mcp/{path}`（`path` = `mcp_server.path`），避免 `/{path}/mcp` 与 `/admin` 抢路由。
- Principal 用 `AccessPrincipal.tokenCode()`。
- JSON-RPC 信封（`jsonrpc` / `id` / `result` | `error`）在本领域组装；method 分发只调 `McpApi`，不实现参数映射。`initialize` 等未实现 method 视为协议错误，不在本领域补握手。

## 6. 领域规约

- 只允许依赖 core、access、ai。
- 禁止 `block()`；流式禁止 `collectList`。
- 禁止在本领域写 Lua、JPA Entity、解析 DeepSeek usage、解析 OpenAPI、实现工具 args 映射。
- 结构验证：orchestration 依赖三者；不反向被依赖。

## 7. 验收标准

- 合法令牌 Chat 非流式 / 流式都能到上游；出站 `include_usage` 为 true；有 usage 才记账。
- 未授权模型 403；超额 429；Redis 挂 503。
- 流式客户端中途断开仍能记到 usage（或记协议失败，不得静默 0）。
- `GET /v1/models` 不含单价与 secret。
- MCP discover / list / call 在金额窗已满时仍 200（未查额度）。
- MCP call 成功时 Agent 看到 JSON-RPC result，不是业务 HTTP 原状态。
- call 缺参等失败文本含约定前缀。
- Modulith：依赖边正确。
