# Spec｜网关核心（core）

> 定位：core 长期真相源。

## 0. 元信息

- 文档类型：Spec
- 文档状态：生效
- 业务分类：网关
- 业务主题：核心
- 更新时间：2026-09-04
- 关联：`docs/references/domains.md`、`docs/v1-plan.md`

## 1. 背景与目标

- 背景：第一版重心在 AI，但没有「收请求、过链、转到一个上游」，Chat 和额度会堆在入口里。core 是网关自己的能力，不是 Chat 领域。
- 目标：本地能把一次 HTTP 请求跑过过滤器链，并能转发到**一个写死的 URL**（整包或 SSE）。不做注册中心和负载均衡。

## 2. 范围

### 纳入范围

- WebFlux 收请求。
- 过滤器链：放行、短路、在不缓存完整响应的前提下观察流式帧。
- 反向代理：单个上游 URL、额外出站头、可选覆盖 method/body/timeout；整包或 SSE 逐帧；可把上游响应捕获给调用方而不写入入站响应。
- 健康探测。
- 网关级失败映射（框架 404/405 保持原状态；未预期 500；上游失败 502/504），不泄露堆栈。

### 不纳入范围

- 注册中心、LB、熔断、重试矩阵、动态路由表、路径改写 DSL、WebSocket、Spring Cloud Gateway。
- 数据面业务入口（在 `orchestration`）。
- API Key、额度、Token 计数、Redis、持久化。
- OpenAI 语义、TTFT、usage 解析、MCP、DeepSeek 专用逻辑。
- 内容审核与脱敏。

### 依赖领域

- 无。

### 禁止依赖领域

- `access`、`ai`、`orchestration`。

## 3. 公开能力

- **过滤器链：** 领域外可挂过滤器，按顺序执行；可放行或短路。
- **反向代理：** 领域外指定 URL、出站头，以及可选的 method/body/timeout。可写入入站响应（透传），或只捕获状态与正文交还调用方。流式可按帧观察；可声明客户端断开后仍读完上游。显式转发与过滤器链并列，不必经过链末隐式反代。
- **健康探测：** 进程存活可被探测。
- **失败映射：** 未处理异常与上游失败有稳定 JSON 错误形态（`error.message` 与 `error.type`）。

不公开：身份、额度、Chat/MCP 协议。编排不在本领域。

## 4. 需求重点

- 第一版「路由」= Web 框架路径映射，core 不维护路由表。
- 上游一次一个 URL，由 **orchestration** 传入（它从 ai 取得语义后再调 core）。core 不发现服务、不选实例。
- 流式必须边读边写，禁止先收齐再转发。
- 入站调用方凭证不得默认转发出站；出站认证头只来自本次上游附加头。
- 请求头：默认只放行 `Content-Type` / `Accept` / `Accept-Language`；其余出站头来自 `Upstream.extraHeaders`。
- 响应头：透传上游头，去掉 hop-by-hop（`Connection`、`Transfer-Encoding`、`Keep-Alive`、`Proxy-*` 等）。
- 调用方断开默认取消上游订阅。调用方可声明「读到结束」（Chat 计费 drain）；仍禁止为观察而 `collectList` 整段流。
- `Upstream` 可带出站 method、body、timeout。body 缺省则抄入站 body；method 缺省则抄入站 method。仍无 Chat/MCP 语义。
- 领域外可显式调用「写入入站响应」与「只捕获」两种转发。过滤器链末尾的隐式反代仍保留（缺省抄入站、断开即取消），给非 Chat/MCP 路径用。

## 5. 设计重点

- 运行时：Spring WebFlux + Reactor Netty。出站 WebClient 连接池可配（`gateway.proxy.max-connections`），长 SSE 不能吃默认 500 上限。
- 一次 AI 调用的**顺序编排在 `orchestration`**：调 access 额度、调 ai 协议、调 core 转发。core 只被调，不调另外两个。Chat/MCP 走显式转发，不把业务语义塞进过滤器。
- Security 只解决进门；额度是 access 的 API，由 orchestration 显式调用。
- 不预留空的动态路由 / LB 接口。

## 6. 领域规约

- `core` 不得 import `access` / `ai` / `orchestration`。
- 禁止 `block()`；禁止用收集完整响应体作为正常流式路径。
- 禁止 `spring-boot-starter-web`、Spring Cloud Gateway、注册中心与 LB 库。
- 过滤器合同必须是响应式的；短路不得再进入下一环。
- 无业务不变量则不要空的 domain 层。

## 7. 验收标准

- 仅 WebFlux 启动；Modulith 验证四模块依赖：core/access/ai 无出边，orchestration 依赖三者。
- `GET /health` 成功。
- 过滤器顺序与短路可测。
- 流式多帧分别到达，而不是一次拼好的整包。
- 假上游：非流式透传、SSE 透传、默认取消后上游停读；drain 模式下取消后仍读完；capture 拿得到状态与正文且不写入入站响应。
- 本领域测试不依赖 MySQL、Redis、真实 DeepSeek。
