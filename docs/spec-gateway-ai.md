# Spec｜AI 协议（ai）

> 定位：ai 内 Chat 子域长期真相源。MCP 见 `docs/spec-gateway-mcp.md`。落地见 `docs/plan-gateway-ai.md`。表见 `docs/sql-gateway-user.md`。

## 0. 元信息

- 文档类型：Spec
- 文档状态：生效
- 业务分类：网关
- 业务主题：AI 协议
- 更新时间：2026-09-03
- 关联：`docs/plan-gateway-ai.md`、`docs/sql-gateway-user.md`、`docs/references/domains.md`、`docs/v1-plan.md`

## 1. 背景与目标

- 背景：聊天和 Agent 调模型本质都是发一轮大模型请求。协议、出站 Key、官方单价、从响应里读 usage 并算出金额，应集中在本领域。鉴权与额度在 access，由 orchestration 在调用前后显式使用。
- 目标：按模型组出站调用（第一版实现 DeepSeek，结构不写死供应商），透传调用方消息，读 usage，按官方单价算分；记下出站成败与首字/总耗时。

## 2. 范围

### 纳入范围

- 模型目录：模型名、供应商、绑定的出站 Key、输入/输出官方单价（分 / 百万 Token）、启用。
- 出站 Key（`llm_apikey_config`）维护。
- 根据 `model` + 调用方 JSON 组上游 URL、认证头、请求体、是否流式；流式时出站 **强制** `include_usage=true`。
- 从非流式 JSON 或 SSE 帧读 `usage`，按该模型单价计算金额（分）。
- 出站调用日志：按出站 Key 记下成败、首字耗时、总耗时与说明；可按 Key / 模型查询平均 TTFT 与故障率。
- 模型目录查询（给编排合并「可用模型」用，不含令牌权限）。

### 不纳入范围

- 进门鉴权、金额窗口、令牌–模型授权、Redis 计数（access）。
- 反向代理拷流、TTFT **计时**（orchestration 在发出到第一帧之间打点，把毫秒交给本领域落日志）。
- 调用方限额、用量明细、Redis 窗口（access）。
- 数据面 HTTP 入口（orchestration）。
- MCP（`docs/spec-gateway-mcp.md`：与 Chat 并列，不共享 QPM/限额）。
- 内容审核、多供应商实现（目录允许登记，第一版只实现 DeepSeek 适配）。
- 定时探活、熔断、按小时/日预聚合表。

### 依赖领域

- 无。

### 禁止依赖领域

- `core`、`access`、`orchestration`。不得返回 `core.Upstream`。

## 3. 公开能力

- **模型目录：** 可查询已启用模型及其单价；可维护模型与出站 Key。
- **组上游：** 给定模型名与调用方请求体，给出 URL、出站头、要转发的 body、是否流式。
- **读用量与计价：** 从上游响应或 SSE 帧得到 prompt/completion Token，并给出本次金额（分）。
- **出站日志：** 编排传入本笔成败、耗时与说明后落库；可按出站 Key 查询。

不公开：验访问令牌、查额度、写 Redis、通用拷流、自己用秒表打 TTFT。

## 4. 需求重点

- 调用方把 `messages` 等字段拼好（含 system）。业务字段原样进入上游请求体；本领域只补出站认证。
- **计费例外（对标 Sub2API）：** 只要 `stream=true`，出站必须把 `stream_options.include_usage` **写成 true**，即使调用方写了 `false`。否则 SSE 没有 `usage`，金额无法统计。
- **必须拿到上游 usage。** 非流式从 JSON 读；流式从末帧合并（重复出现用最新值，不累加）。调用方中途断开时，Chat 路径仍应读完上游直到出现 usage 或连接结束，不能为省流量提前停读。读不到 usage 不得当成「0 Token 正常成功」：非流式视为上游协议失败；流式已写出则记错误，本笔不能按 0 金额入账冒充免费。
- 本领域不看访问令牌。模型不在目录或未启用 → 本领域失败。有没有权限由 access 在编排里先挡。
- 单价按模型名、对齐官网；金额 = 输入 Token × 输入单价 + 输出 Token × 输出单价，四舍五入到分。
- 第一版供应商适配只做 DeepSeek（OpenAI 兼容出站）；新增供应商是加适配，不是改编排。
- 出站日志由编排在转发结束后把已测到的毫秒与成败交给本领域写入。本领域不算 TTFT。

## 5. 设计重点

- 模型绑定 `llm_apikey_config`，不绑访问令牌。编排把请求里的 `model` 交给本领域即可。
- 组上游产物含 url、headers、**body**、stream。body 以本领域返回值为准（流式已强制 `include_usage`）。编排必须转发这份 body。core 通用反代「客户端断开即取消上游」不适用于 Chat 计费路径：Chat 要 drain 到 usage。
- 流式：从**单帧**识别 usage、以及是否为首帧；不 `collectList`，不提供整段 SSE 入口。非流式才从完整 JSON 读 `usage`。
- 出站日志按出站 Key + 模型记，不含访问令牌、不含金额。金额在 access。第一帧前取消不写日志。失败原因写说明字段，不要再拆失败分类列或 HTTP 状态列。
- 平均 TTFT、故障率第一版扫日志表。管理面维护目录与 Key；无 UI。

## 6. 领域规约

- 不得 import `core` / `access` / `orchestration`。
- 不得把 secret 写入可提交配置或日志明文。
- 入站调用方 `Authorization` 不得当作模型 Key。
- 结构验证：ai 无出边。

## 7. 验收标准

- 已知 DeepSeek 模型可组出带官方 Key 的上游；除强制 `include_usage=true` 外业务字段与入站一致。
- `stream=true` 时出站一定含 `include_usage=true`（覆盖调用方的 false）。
- 未知或禁用模型被拒绝，且不访问 access。
- 非流式与 SSE 都能解析 usage 并算到分；缺少 usage 的成功响应按需求重点处理，不得静默当 0。
- 出站日志可按 Key 汇总平均首字耗时与故障率；说明字段不含密钥。
- Modulith：ai 无出边。
