# Spec｜数据面（orchestration）

> 定位：数据面流程域长期真相源。不拥有表。Chat 协议见 `docs/spec-gateway-ai.md`；MCP JSON-RPC 见 `docs/spec-gateway-mcp.md`；额度见 `docs/spec-gateway-access.md`；转发见 `docs/spec-gateway-core.md`。包名仍是 `orchestration`，不要为改名而改包。

## 0. 元信息

- 文档类型：Spec
- 文档状态：生效
- 业务分类：网关
- 业务主题：数据面
- 更新时间：2026-09-04
- 关联：`docs/references/domains.md`、`docs/v1-plan.md`、`docs/plan-gateway-mcp-protocol.md`

## 1. 背景与目标

- 背景：`core` / `access` / `ai` 互不依赖。一次 Chat 必须有人按序问额度、组上游、转发、记账；这段顺序放进任一业务域会成环或把协议和配额缠死。Chat 还有自己的收口：TTFT、断开后 drain、缺 usage 不得记 0。
- 目标：数据面 HTTP 只在本领域。Chat 链组合 + 收口；MCP 链只做 HTTP 适配与出站捕获，JSON-RPC 语义在 `McpApi`。本领域不是全局 application，禁止再抽第二个编排域。

## 2. 范围

### 纳入范围

- `POST /v1/chat/completions`：授权与金额窗、组上游、转发、TTFT / 出站日志与用量收口。
- `GET /v1/models`：令牌授权名与目录启用名的交集（无单价、无 secret）。
- `POST /mcp/{path}`：Origin / Accept / Content-Type；把协议头与 JSON-RPC 正文交给 `McpApi.handle`；`NeedsOutbound` 时捕获上游再 `completeCall`。
- 把 `ChatUpstream` / `McpUpstream` 转成 core 的通用 `Upstream`。
- Chat 流式：逐帧写给客户端；打 TTFT；逐帧问 `ChatApi` 读 usage；客户端断开后仍 drain。

### 不纳入范围

- 表、Redis、过滤器链实现、WebClient 拷流、usage 解析、工具参数映射、单价。
- MCP JSON-RPC 信封、method 分发、协议版本、错误码、工具失败文本（`spec-gateway-mcp`）。
- 管理面（access / ai）。Health（core）。
- MCP 的 QPM / 金额。legacy `initialize` 握手实现（未实现 method 由 `McpApi` 拒绝）。

### 依赖领域

- `core`、`access`、`ai`。

### 禁止依赖领域

- 无其它业务域。禁止在本领域实现上述「不纳入」。

## 3. 公开能力

- **Chat 数据面：** OpenAI 兼容聊天入口，以及该令牌实际能用的模型列表；拥有本笔调用的 TTFT/drain/记账收口。
- **MCP 数据面：** 一台 MCP 服务器的 POST 入口；只做 HTTP 传输适配与出站捕获。
- **反腐翻译：** 把 ai 的上游描述译成 core `Upstream`，不含协议名。

不公开：配额算法、反代实现、DeepSeek usage 解析、MCP JSON-RPC、工具映射。

## 4. 需求重点

- Chat 链必须：`assertModelAllowed` → `checkQuota` → `prepare` → 转发 → 有 usage 才 `priceUsage` / `recordUsage`，并 `recordCallLog`。缺 usage 不得记 0 成功。
- 必须转发 **`ChatUpstream.body`**，不得把入站 body 原样交给 core。`recordCallLog` 使用 prepare 给出的出站 Key code。
- Chat 流式禁止 `collectList`。TTFT = 发出上游到第一帧（`isFirstContentFrame`）。客户端断开后仍读上游直到 usage 或结束。第一帧之前取消且 drain 也无 usage：不写日志、不记账。
- `GET /v1/models` 只做授权名与目录启用名求交，不调 `checkQuota`。响应为 OpenAI 列表形，条目只含 id。
- MCP 链必须：**不**调用 `checkQuota` / `recordUsage`。Origin 不在允许列表 → 403；`Accept` 须同时含 json 与 event-stream，否则 406；Content-Type 非 JSON 由本域先挡。协议头抽成字符串后只调 `McpApi.handle` / `completeCall`。金额窗已满的令牌仍可走 MCP。
- MCP 上游 HTTP **不得**透传给 Agent；出站后必须 `completeCall`。本领域不得拼 JSON-RPC 错误码或工具失败前缀。
- 入站 `Authorization` 不得进入出站。本领域无表。

## 5. 设计重点

- 路由用 Spring 映射，不用 core 过滤器挂业务。Chat 与 MCP 两个应用服务。
- 生产数据面走 `ProxyApi`，不走 `PipelineApi.execute` 的隐式反代。不要借机做动态路由/LB，也不要在本轮删除过滤器框架。
- Chat 非流式允许收齐后再写回；流式必须边写边观察。MCP 一律捕获。
- 数据面路径：`POST /mcp/{path}`（`path` = `mcp_server.path`）。
- Principal 用 `AccessPrincipal.tokenCode()`。
- `McpApi.handle` 的结果：完整 JSON-RPC 对象 + `int` HTTP 状态，或「需要出站」并带入站 `id` 与 `McpUpstream`。数据面原样写出状态，不翻译协议。

## 6. 领域规约

- 只允许依赖 core、access、ai。禁止再增加编排模块。
- 禁止 `block()`；流式禁止 `collectList`。
- 禁止写 Lua、持久化实体、解析 DeepSeek usage、解析 OpenAPI、实现工具 args、拼 `-326xx`。
- 结构验证：orchestration 依赖三者；不反向被依赖。

## 7. 验收标准

- 合法令牌 Chat 非流式 / 流式都能到上游；出站 `include_usage` 为 true；有 usage 才记账。
- 未授权模型 403；超额 429；Redis 挂 503。流式中途断开仍能记到 usage（或协议失败，不得静默 0）。
- MCP 满额仍 200；call 成功时 Agent 看到 JSON-RPC result，不是业务 HTTP 原状态；缺参文本含约定前缀。
- 编排源码不再出现 JSON-RPC 错误码拼装。Modulith：依赖边正确。
