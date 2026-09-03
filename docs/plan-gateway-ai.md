# Plan｜落地 ai Chat 第一版

> 编码会话执行。口径以 `docs/spec-gateway-ai.md` 为准。表以 `docs/sql-gateway-user.md` 为准。只实现 Chat 协议 API，不要写 MCP、不要写 orchestration 数据面、不要 import access/core。

## 文档信息

- 文档类型：Plan
- 文档状态：已完成
- 业务分类：网关
- 业务主题：AI 协议
- 更新时间：2026-09-03
- 上游依据：`docs/spec-gateway-ai.md`、`docs/sql-gateway-user.md`、`docs/references/domains.md`
- 前置：`docs/plan-gateway-access-billing.md`（金额与模型授权）应先落地或与本 Plan 分会话、但编排前必须两边都在
- 完成后：公开类型名若变，回流 Spec

## 1. 文档定位

- 回答：ai 如何提供组上游、读 usage、计价、模型目录。
- 不实现 `POST /v1/chat/completions` HTTP（orchestration）。
- 不实现 MCP。

## 2. 需求

- 做：`llm_model` 与 Key 的仓储；`llm_call_log` 仓储与写入/查询 API；DeepSeek 适配；`ChatApi`（组上游 / 读用量计价 / 列目录）；管理 HTTP 维护模型与 Key，并可按出站 Key 查日志汇总。
- 不做：access、core 反代、MCP、TTFT 计时（只收编排传入的毫秒落日志）、多供应商适配类、小时/日预聚合表。
- 待澄清：无。

## 3. 背景

- access / core 已可运行。ai 目前只有 `package-info`（`allowedDependencies = {}`）。
- 出站形态：DeepSeek OpenAI 兼容 `base_url` + `Authorization: Bearer <secret>`。

## 4. 目标

- `mvn test` 全绿。ai 不 import 另外三模块。

## 5. 技术方案

**公开面 `ChatApi`：**

- `Mono<List<ModelView>> listModels()`：启用中的目录（名、供应商、入/出单价「分/百万 Token」）。
- `Mono<ChatUpstream> prepare(String model, byte[] requestBody)`：查目录与 Key；解析 `stream`；出站 body 默认等于入站 JSON。**只要 `stream=true`，无条件写入 `stream_options.include_usage=true`**（对标 Sub2API `ensureOpenAIChatStreamUsage`，调用方写 false 也覆盖）。其余字段不动。URL = 规范化 `base_url` + `/chat/completions`；headers：`Authorization: Bearer <secret>`、`Content-Type: application/json`。
- 出站真相是返回的 `body`。编排必须转发这份 body。Chat 路径客户端断开后仍 drain 上游直到读到 usage 或结束（与 core 通用反代「断开即取消」不同；编排/core 扩展本 Plan 不改，须在 orchestration 落地时做）。
- `readUsage(model, jsonBody)`：只解析**非流式**根对象 `usage`。缺 usage 返回空结果并让调用方视为计量失败，**不要填 0 当成功账单**。金额：`(prompt * inputFenPerMillion + completion * outputFenPerMillion) / 1_000_000` 四舍五入到分。
- 流式不要把整段 SSE 交给 `readUsage`。逐帧 `readSseUsageFrame`，重复出现保留最新 `TokenCounts`（不累加），结束时 `priceUsage`。不要 `collectList`。
- `boolean isFirstContentFrame(byte[] sseFrame)`：供编排算 TTFT（发出请求到此帧）。
- 出站日志：`recordCallLog(llmApikeyCode, model, success, message, firstTokenMs, totalDurationMs)` 写 `llm_call_log`（插入时 `create_time=update_time`）。提供按 Key、时间范围、模型汇总：请求次数、成功数、平均 `first_token_ms`（忽略 NULL）、故障率。编排会话才真正调用 `recordCallLog`；本 Plan 把 API 与单测做完。

`ChatUpstream`：`url`、`extraHeaders`、`body`、`stream`。禁止引用 `core.Upstream`。

**计价：** 只用目录上的 cache miss / 标准入出单价。第一版不考虑缓存命中价、峰时价。官网价变更靠改目录行，不写死在代码里。种子数据按录入时官网价填「分 / 百万 Token」。

**数据：** Entity 覆盖 `llm_apikey_config`、`llm_model`、`llm_call_log`。Flyway 增量见 SQL（V2 起）。JPA `boundedElastic`。secret 与 `message` 日志脱敏（只打 prefix，禁止写密钥）。

**管理 HTTP：** `/admin/llm/apikeys`、`/admin/llm/models`；出站统计挂在 Key 下（如 `/admin/llm/apikeys/{code}/stats`）。`X-Admin-Token`。不在 ai 里实现 Security 过滤器（复用 access 已对 `/admin/**` 的管理令牌）；若测试需独立切片，只测 API 层。

**测试：** 不打真实 DeepSeek。断言 URL/头；多余字段仍在；`stream=true` 时无论入站有无、是 true 还是 false，出站均为 `include_usage=true`。usage 样例含末帧空 choices；缺 usage 的响应不能被当成 0 成功。

## 6. 领域规划

- 只填 `ai`，依赖 `{}`。内部可分子包 `chat`，不拆 Modulith 模块。

## 7. 前置条件

- SQL 已含 `llm_model`、`llm_call_log` 与 V2 说明。
- 金额限额与令牌模型授权的 access 变更已有 Plan；本 Plan 不改 Redis 窗口。

## 8. 拆分原则

- 先表与目录，再 prepare，再 usage/计价，再管理 HTTP。
- 不顺手写 MCP、不写 orchestration Controller。

## 9. 实施计划

| 序号 | 任务 | 主要内容 | 前置 | 完成标准 |
| --- | --- | --- | --- | --- |
| 1 | 表与仓储 | `llm_model`、Key、`llm_call_log` Entity；Flyway 按 SQL | 无 | 按模型名能查到单价和 apikey；能插入出站日志 |
| 2 | 组上游 | `prepare`：透传 body、强制 `include_usage=true`、补 Bearer、拼 URL | 1 | 入站 false 出站仍为 true；其它字段不动；无模型则失败 |
| 3 | usage 与计价 | JSON / SSE 抽 usage（末帧、最新值）；分四舍五入 | 2 | 有 usage 金额正确；缺 usage 不伪装成 0 成功 |
| 4 | 目录、日志与管理 | `listModels`；`recordCallLog` 与按 Key 汇总；`/admin/llm/**` | 1 | 可登记 DeepSeek 模型与 Key；日志汇总与插入一致 |
| 5 | 停 | 不写 MCP、Chat HTTP 入口、access | 4 | ai 无 core/access import |

建议类型：`ChatApi`、`ChatUpstream`、`ChatUsage`、`ModelView`。

## 10. 门禁

- A（1–3）：prepare + usage 单测绿。
- B（4）：管理可配模型；verify 通过。未过不开始 orchestration。

## 11. 验证

- `mvn test`

## 12. 风险

- 把鉴权/额度写进 ai。
- body 被改写成「只剩 messages」。
- 尊重调用方 `include_usage=false`，导致无法统计。
- 假定入站 body 可直接给 core 拷走，丢掉强制补丁后的 `ChatUpstream.body`。
- 客户端断开就停上游，导致拿不到 usage。
- 单价写死在代码而不是目录。
- 引用 `core.Upstream`。
- 在 ai 里自己打 TTFT 或把访问令牌写入出站日志。

## 13. 后置吸收

- 公开类型名若变，改 Spec。
- 公开 `readUsage` 只吃非流式 JSON。流式只保留 `readSseUsageFrame` / `priceUsage` / `TokenCounts`。

## 14. 当前状态

- 状态：已完成
- 下一步：orchestration 串 `POST /v1/chat/completions`

## 15. 执行要求

- 禁止提交真实 Key。不要 Lombok。不要依赖 core/access。
