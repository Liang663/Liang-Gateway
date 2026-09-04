# Plan｜MCP JSON-RPC 下沉到 McpApi

> 编码会话执行。口径以已更新的 `docs/spec-gateway-mcp.md`、`docs/spec-gateway-orchestration.md`、`docs/references/domains.md` 为准。先补 `McpApi.handle` / `completeCall`，再削薄编排。不要改 Chat 收口，不要删 PipelineApi。

## 文档信息

- 文档类型：Plan
- 文档状态：已实现
- 业务分类：网关
- 业务主题：MCP 协议
- 更新时间：2026-09-04
- 上游依据：`docs/spec-gateway-mcp.md`、`docs/spec-gateway-orchestration.md`、`docs/references/domains.md`
- 前置：数据面 HTTP 已在 master
- 完成后：公开类型名若变，回流 mcp Spec；本 Plan 可删

## 1. 文档定位

- 回答：JSON-RPC 如何从 `McpGatewayService` 迁到 `McpApi`；数据面留下什么。
- 不重写额度、Chat drain、OpenAPI 导入、工具 args 映射算法。

## 2. 需求

- 做：`McpApi.handle` / `completeCall`；编排只做 Origin/Accept/Content-Type、`ProxyApi.exchange`、Upstream 翻译；文档已先行。
- 不做：拆 ai 模块、改 `orchestration` 包名、膨胀 ChatApi、引进 AccessApi 到 ai、删除过滤器链、搬管理面。
- 待澄清：无。

已采纳：协议头用纯字符串；Outcome 用 `int` httpStatus 不含 Spring 类型；`NeedsOutbound` 带入站 `id`；discover/list/prepareCall/wrapCall 保留给单测。

## 3. 背景

- `McpGatewayService` 现含 JSON-RPC 解析、错误码、method 分发。与 domains「不写 JSON-RPC」冲突。
- Chat 的 TTFT/drain 是数据面生命周期，本 Plan 不改行为。

## 4. 目标

- `mvn test` 全绿。编排源码不再拼 `-326xx` / `jsonrpc` 信封。ai 无出边。

## 5. 技术方案

**公开面（包 `com.liang.gateway.ai`，无 Web 类型）：**

```
record Transport(String protocolVersion, String methodHeader, String nameHeader)

sealed Outcome
  record Reply(int httpStatus, Map<String,Object> jsonRpc)
  record NeedsOutbound(Object id, McpUpstream upstream)

Mono<Outcome> handle(String serverPath, Transport transport, byte[] jsonRpcBody)
Mono<Outcome.Reply> completeCall(Object id, Integer httpStatus, String body, boolean timedOut)
```

`completeCall` 内部调现有 `wrapCall`，再封 `jsonrpc/id/result`。超时：`completeCall(id, null, null, true)`。

HTTP int 与 JSON-RPC code 对照见 mcp Spec 表格。数据面把 `Reply.httpStatus` 原样写成 HTTP 状态。

**数据面 `McpGatewayService`：**

1. Origin 非法 → 403（无 JSON-RPC）
2. Accept 不合 → 406
3. Content-Type 非 JSON → 400（本域先挡，不进 handle）
4. `handle(path, Transport(头), body)`
5. `Reply` → `ResponseEntity.status(httpStatus).json(jsonRpc)`
6. `NeedsOutbound` → `proxy.exchange(toUpstream)` → `completeCall` → 写出 Reply
7. 禁止再解析 method、禁止拼 error.code

**Chat：** 只审视。无 JSON-RPC 泄漏则不改。

## 6. 领域规划

- 模块与依赖方向不变。`handle` / `completeCall` 实现在 `ai.internal.mcp`。
- 能力归属：JSON-RPC → ai；HTTP 传输与出站捕获 → orchestration。

## 7. 前置条件

- 阶段 0 文档已合入（本文件与对应 Spec 同时存在）。
- 测试连 compose 持久化 MySQL/Redis。

## 8. 拆分原则

- 先 ai 协议入口与单测，再削薄编排，最后回归 Chat/verify。
- 每刀测试绿。不要先改 Controller 再补 McpApi。

## 9. 实施计划

| 序号 | 任务 | 主要内容 | 前置 | 完成标准 |
| --- | --- | --- | --- | --- |
| 1 | McpApi 协议入口 | Transport/Outcome；handle 迁现有校验与分发；completeCall | 无 | `McpApiHandleTest`：非法 JSON、-32601、版本错+supported、头不一致、params._meta 错、discover 信封、缺参 Completed、NeedsOutbound |
| 2 | 削薄编排 | Origin/Accept/CT → handle → exchange → completeCall | 1 | `McpGatewayHttpTest` 绿；orchestration 无 `-32601`/`"jsonrpc"` 拼装 |
| 3 | Chat 审视 | 不删 drain/TTFT/settled | 2 | Chat HTTP 测绿；无行为则不改 |
| 4 | 结构与回归 | verify；AiDependencyTest；access/core | 3 | `mvn test` 绿；编排不 import jpa/redis/internal |

## 10. 门禁

- A（任务 1）：ai 单测覆盖信封；公开类型无 Spring Web。未过不改编排。
- B（任务 2–4）：HTTP 行为与现网一致；编排无协议拼装；全绿。未过不宣称完成。

## 11. 验证

- `mvn test`
- `rg` orchestration：`-32`、`jsonrpc`、`Mcp-Method` 业务分支（头读取除外）
- 证据：测试输出

## 12. 风险

- 把 `HttpStatus` 带进 ai。
- `id` 在编排再解析。
- 只改文档不迁代码。
- 顺手删 PipelineApi。

## 13. 后置吸收

- 公开类型名若落地有变，改 mcp Spec。
- 过程任务表完成后可删本 Plan；从 AGENTS 导航拿掉。

## 14. 当前状态

- 状态：已实现
- 下一步：无（公开类型嵌套在 `McpApi`：`Transport` / `Outcome.Reply` / `Outcome.NeedsOutbound`）

## 15. 执行要求

- 不要 Lombok。不要拆 ai。禁止提交真实 Key。
- 行为变更已写在 Spec；编码不要另发明错误码。
