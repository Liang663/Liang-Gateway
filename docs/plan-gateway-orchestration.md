# Plan｜落地 orchestration 数据面

> 编码会话执行。口径以 `docs/spec-gateway-orchestration.md` 为准。先扩展 core 反代合同，再接 Chat，再接 MCP。不要把配额、usage 解析、OpenAPI、工具映射写进本模块。

## 文档信息

- 文档类型：Plan
- 文档状态：已实现
- 业务分类：网关
- 业务主题：编排
- 更新时间：2026-09-04
- 上游依据：`docs/spec-gateway-orchestration.md`、`docs/spec-gateway-core.md`、`docs/spec-gateway-ai.md`、`docs/spec-gateway-mcp.md`、`docs/spec-gateway-access.md`
- 前置：core / access billing / Chat API / MCP API 已在 master
- 完成后：core `Upstream` 字段与 `ProxyApi` 若落地有变，回流 core Spec；`ChatUpstream.apikeyCode` 回流 ai Spec（本轮已先改 Spec）

## 1. 文档定位

- 回答：数据面两条链如何排序调用已有 API；core 要补哪些无协议语义的转发能力。
- 不重写额度算法、DeepSeek 协议、MCP `args` 映射。
- 执行者：编码会话。

## 2. 需求

- 做：core `Upstream` 可覆盖 method / body / timeout；流式观察帧且可 drain；捕获上游状态 + 正文；`POST /v1/chat/completions`；`GET /v1/models`；`POST /mcp/{path}`。
- 不做：管理面、Health 改动、legacy MCP、MCP 限额、独立 WebClient、在 orchestration 写 Entity。
- 待澄清：无。

已采纳：MCP 入口用 `/mcp/{path}` 避免与 `/admin` 冲突；MCP 不查 QPM / 金额；Chat 出站 body 以 `prepare` 为准；`ChatUpstream` 增加只读 `apikeyCode`。

已拒绝：把 Chat / MCP 挂进 core 过滤器链；MCP 与 Chat 共用一条应用服务。

## 3. 背景

- 现有 `PipelineApi.execute` 链末反代：抄入站 method / body，客户端断开即停上游。Chat 需要转发 `ChatUpstream.body` 并 drain usage；MCP 需要换 method / body，且把上游 HTTP **捕获**后再写成 JSON-RPC，不能透传到 Agent。
- `ChatApi.recordCallLog` 需要 `llmApikeyCode`。当前 `ChatUpstream` 只有 url / headers / body / stream，编排拿不到。本 Plan **必须**给 `ChatUpstream` 加 `apikeyCode`（`prepare` 已查到该 Key）。
- `checkQuota` 会打 QPM。只在 Chat completions 调；`GET /v1/models` 与 MCP 不调。

## 4. 目标

- 本地用假上游打通 Chat 非流式 / 流式与 MCP discover / list / call。`mvn test` 绿。
- orchestration 不出现 JPA Entity；不 import 另外三模块的 `internal`。
- 不变量：入站 `Authorization` 不出站；流式无 `collectList` / `block`；MCP 不调额度。

## 5. 技术方案

**core（本 Plan 内小步，无 Chat / MCP 类型）：**

- `Upstream` 增加可选字段：出站 `method`（空 = 抄入站）、`body`（空 = 抄入站）、`timeout`（空 = `gateway.proxy.response-timeout`）。**保留现有三参形态或给新字段默认空**，不打断现有 Pipeline 测试。
- 新公开 `ProxyApi`（与过滤器链并列；`PipelineApi.execute` 仍走内部反代，行为保持「抄入站 + 断开即取消」）：
  - `forward(exchange, upstream, observe, drainAfterCancel)`：按 `Upstream` 出站；边写入站响应边回调每帧（`byte[]`）；`drainAfterCancel=true` 时客户端取消仍读完上游（Chat 流式）。`observe` 可空。
  - `exchange(upstream)`：返回状态码 + 正文，**不**写入入站响应（MCP；Chat 非流式）。超时抛现有 `ProxyFailureException`（504）。
- 入站 `Authorization` 仍默认不转发。出站头规则不变：放行 `Content-Type` / `Accept` / `Accept-Language`，再叠加 `extraHeaders`。
- 编排 **不** 把 `Upstream` 塞进 `GatewayExchange` 再 `PipelineApi.execute`。

**ai 最小回流（本 Plan 允许且必须）：**

- `ChatUpstream` 增加 `String apikeyCode`。`prepare` 填模型绑定的出站 Key code。`toString` 仍脱敏 secret，code 可出现。
- 改 `ChatApiPrepareTest` 断言 code 非空。不改 usage / 计价 / 管理面。

**Chat 应用服务（`AccessPrincipal.tokenCode()`）：**

1. 读 body 得到 JSON 字段 `model`；缺 model 或非法 JSON → 400。
2. `assertModelAllowed` → `checkQuota` → `chatApi.prepare(model, rawBody)`。
3. 转 `Upstream`：url、headers=`ChatUpstream.extraHeaders`、body=`ChatUpstream.body`、stream、method=POST。不得抄入站 body。
4. 非流式：`proxy.exchange` → 2xx 且 `readUsage` 有值：把上游状态 + 正文写入入站响应，`recordUsage` + 成功日志。2xx 无 usage：502，失败日志，**不**把无 usage 的 body 当成功。4xx/5xx：透传状态 + 正文，失败日志，不记账。超时 / 坏网关走 core 已有 504 / 502。
5. 流式：`forward` + observe：`isFirstContentFrame` 记 TTFT；`readSseUsageFrame` 留最新；结束 `priceUsage`；有 usage 才 `recordUsage`。客户端断开仍 `drainAfterCancel=true`。已写出内容但无 usage：失败日志，不记 0。第一帧前取消且无 usage：不写日志、不记账。
6. `recordCallLog(apikeyCode, model, success, message, firstTokenMs, totalDurationMs)`。`UsageMeta.requestId` 用本笔 UUID。

**GET /v1/models：** `listAllowedModels` 与 `listModels` 按 name 求交，按 id 排序。响应 OpenAI 列表形：`{object:"list", data:[{id, object:"model"}]}`。无单价、无 secret、无 provider。不调 `checkQuota`。

**MCP 应用服务：**

1. 校验：
   - Origin：配置 `gateway.mcp.allowed-origins`。无 Origin（典型 Agent）放行；有 Origin 必须在列表中，否则 403。列表默认空 = 拒绝所有带 Origin 的请求。
   - `Content-Type` 为 JSON；`Accept` 同时含 `application/json` 与 `text/event-stream`，否则 406。
   - `MCP-Protocol-Version` = `2026-07-28`；JSON-RPC `_meta.protocolVersion` 若出现也须同值（传给 `McpApi` 即可，ai 已校验）。
   - `Mcp-Method` 等于 JSON-RPC `method`。
   - 一次一条 JSON-RPC 对象（数组 / 缺 `jsonrpc:"2.0"` → 400 + `-32600`）。
2. `tools/call` 还要 `Mcp-Name` == `params.name`。
3. method：`server/discover` → `mcpApi.discover`；`tools/list` → `listTools`；`tools/call` → `prepareCall`；其它（含 `initialize`）→ HTTP 404 + `-32601`。
4. `prepareCall` 若 `Completed`：直接写 JSON-RPC `result`（isError 文本已在 ai）。
5. 若 `Ready`：`proxy.exchange(upstream)`（timeout 用 `timeoutMs`）→ `wrapCall` → 写 JSON-RPC。捕获到超时 → `wrapCall(null, null, true)`。不要把上游状态码当 HTTP 响应给 Agent；工具层失败也是 HTTP 200 + `result`。
6. 不调 `checkQuota` / `recordUsage`。信封补 `jsonrpc:"2.0"` 与入站 `id`。第一版响应 `Content-Type: application/json`。

**MCP HTTP 映射（编排组装，不写进 ai）：**

| 情况 | HTTP | JSON-RPC |
| --- | --- | --- |
| 非法 JSON | 400 | `-32700` |
| 非单条请求 / 缺 jsonrpc | 400 | `-32600` |
| 头 `Mcp-Method` / `Mcp-Name` 不一致 | 400 | `-32600` |
| 版本不是 `2026-07-28` | 400 | error，带 `supported: ["2026-07-28"]` |
| Origin 非法 | 403 | 可无 JSON-RPC |
| Accept 不合 | 406 | 可无 JSON-RPC |
| 方法未实现（含 `initialize`） | 404 | `-32601` |
| server path 不存在或未启用 | 404 | error（`McpServerNotFoundException`） |
| discover / list / call 成功或工具层 isError | 200 | `result` |

Chat 的 401 / 403 / 429 / 503 沿用 access 已有处理器，不要另做一套信封。MCP 数据面不要让 `McpWebAdvice` 的网关信封抢走 JSON-RPC。

**配置：**

```yaml
gateway:
  mcp:
    allowed-origins: []
```

**测试：** MockWebServer / 现有假上游。Chat：出站 body `include_usage=true`；有 usage 才记账；未授权 403；超额 429；流式断开仍记账（或明确失败非 0）。MCP：满额令牌仍 discover 200；call 缺参 content 含 `missing_argument`；上游 404 的 JSON-RPC 为 200 且 isError 含 body；`initialize` 为 404 + `-32601`。verify 依赖边。core 单测：改 body、捕获状态、取消后 drain 仍能读完。

## 6. 领域规划

- orchestration 填 Controller 与应用服务。公开面不增加给其它模块调用的 API。
- core 扩转发合同（`Upstream` 可选字段 + `ProxyApi`）。依赖仍 `{}`。
- ai 仅 `ChatUpstream` 加 `apikeyCode`。access 不改。
- 依赖：orchestration → core, access, ai。结构验证必须过。

建议包（可微调，勿扩大）：

```
com.liang.gateway.core
  ProxyApi
  Upstream          # 增 method / body / timeout
com.liang.gateway.ai
  ChatUpstream      # 增 apikeyCode
com.liang.gateway.orchestration
  internal.application.ChatCompletionService
  internal.application.McpGatewayService
  internal.web.ChatCompletionsController   # POST /v1/chat/completions、GET /v1/models
  internal.web.McpController               # POST /mcp/{path}
```

## 7. 前置条件

- master 已有 `ChatApi`、`McpApi`、`AccessApi`、`PipelineApi`。
- 假上游用 MockWebServer，不打真实 DeepSeek / 真实业务 HTTP。

## 8. 拆分原则

- 先 core 转发合同与单测，再 Chat 通非流式，再 Chat 流式 drain，再 models，再 MCP。
- 每刀测试绿再下一刀。不要先写两个 Controller 再补 core。

## 9. 实施计划

| 序号 | 任务 | 主要内容 | 前置 | 完成标准 |
| --- | --- | --- | --- | --- |
| 1 | core 转发扩展 | `Upstream` 可选 method/body/timeout；`ProxyApi.forward` observe+drain；`exchange` 捕获 | 无 | 假上游：改 body、捕获状态、取消后仍能读完；旧 Pipeline 测试仍绿 |
| 2 | ChatUpstream.apikeyCode | `prepare` 带出站 Key code；回归 prepare 单测 | 无 | `recordCallLog` 能从 prepare 结果取 code |
| 3 | Chat 非流式 | 授权 + 额度 + prepare.body + exchange + 记账 | 1, 2 | 出站 include_usage；有 usage 才 recordUsage；403 / 429 |
| 4 | Chat 流式 | TTFT；逐帧 usage；断开 drain | 3 | 无 collectList；断开仍处理 usage，不记 0 |
| 5 | GET /v1/models | 授权 ∩ 目录 | 2 | 不含 secret / 单价；不打 QPM |
| 6 | MCP POST | 头校验；discover / list / call；capture + wrap | 1 | 不查额度；不透传上游 HTTP；失败前缀可见 |
| 7 | verify | 模块边；回归 access / ai / core 测试 | 6 | `mvn test` 绿 |

## 10. 门禁

### 门禁 A（任务 1–4）

- core 新合同单测绿；Chat 非流式 + 流式绿。
- 未过不开始 MCP 入口。

### 门禁 B（任务 5–7）

- models 无 secret；MCP 绿；满额令牌 MCP 非 429；`mvn test` 全绿。
- 未过不宣称数据面完成。

## 11. 验证

- `mvn test`
- 证据：测试输出。不打真实上游。

## 12. 风险

- 继续用抄入站 body 的旧 `PipelineApi` 转发，丢掉 `include_usage`。
- MCP 把业务 HTTP 状态直接回给 Agent。
- Chat 客户端断开用默认 cancel，usage 丢失却记 0。
- MCP 链误调 `checkQuota`（会打 QPM，满额还会 429）。
- 在 orchestration 里解析 SSE usage（应调 `readSseUsageFrame`）。
- `ChatUpstream` 不加 `apikeyCode`，日志无法按出站 Key 落。
- MCP 数据面被 `McpWebAdvice` 写成网关 `{error:{type}}` 信封。

## 13. 后置吸收

- core `Upstream` 新字段与 `ProxyApi` 行为已先写入 core Spec；落地若改名，改 Spec。
- `ChatUpstream.apikeyCode` 已先写入 ai Spec。
- 过程任务表完成后可删；两条链顺序留在 orchestration Spec。

## 14. 当前状态

- 状态：已实现
- 已完成：core `ProxyApi` / `Upstream` 可选 method·body·timeout、observe+drain、exchange 捕获；`ChatUpstream.apikeyCode`；`POST /v1/chat/completions` 非流式与流式；`GET /v1/models`；`POST /mcp/{path}`；Advice 收口到 admin；`ApplicationModules.verify` 与本 Plan 任务表
- 公开类型未改名，Spec 无需回流
- 阻塞项：无
- 下一步：种子数据、README、补测试（见 `docs/v1-plan.md` §5.7）

## 15. 执行要求

- 不要 Lombok。不要在 orchestration 写 Entity。禁止提交真实 Key。
- 不要实现 `initialize` / session / GET SSE「图个兼容」。
- 行为变更先改对应 Spec。
