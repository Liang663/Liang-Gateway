# Plan｜落地 core 第一版

> 给编码会话执行。长期口径以 `docs/spec-gateway-core.md` 为准。本 Plan **只实现 core**，不要写 access / ai / orchestration 的业务。

## 文档信息

- 文档类型：Plan
- 文档状态：执行中
- 业务分类：网关
- 业务主题：核心
- 更新时间：2026-09-02
- 上游依据：`docs/spec-gateway-core.md`、`docs/references/domains.md`、`docs/v1-plan.md`
- 完成后：公开能力若有变，回流 Spec

## 1. 文档定位

- 回答：core 分两刀怎么改仓库、怎么验证。
- 不重写产品边界和领域切分。
- 执行者：编码会话。

## 2. 需求

- 做：过滤器链、Health、单 URL 反代（整包 + SSE）。
- 不做：Chat、MCP、Security、额度、JPA、Redis、注册中心、LB、orchestration 编排。
- 待澄清：无。

## 3. 背景

- 现有：`com.liang.Main` 教程；四模块仅 `package-info`（`core` / `access` / `ai` / `orchestration`）。
- 目标根包：`com.liang.gateway`，启动类 `GatewayApplication`。
- 本 Plan 不读数据库、不读 DeepSeek Key。

## 4. 目标

- 本地 `mvn test` 全绿；`GET /health` 可用。
- 不变量：core 不 import 另外三模块；无 starter-web；流式无 `collectList` / `block`。

## 5. 技术方案

- WebFlux + Modulith。core 公开：`GatewayFilter`、`GatewayFilterChain`、`GatewayExchange`、`Upstream`、`PipelineApi`。
- `PipelineApi.execute(ServerWebExchange)`：建 Exchange，按 `@Order` 跑所有 `GatewayFilter` Bean；若仍未写响应且 Exchange 上有 `Upstream`，则内部反代。
- 第一版链末隐式反代不单独公开 ProxyApi。数据面需要的 method/body/timeout、drain、捕获由 `docs/plan-gateway-orchestration.md` 扩展（公开 `ProxyApi`，覆盖本句）。
- 反代：一个 WebClient。不转发入站 `Authorization`。请求默认放行 `Content-Type` / `Accept` / `Accept-Language`，再附加 `Upstream.extraHeaders`。响应透传去掉 hop-by-hop。`stream=false` 整包（`max-in-memory-size: 16MB`）；`stream=true` 逐帧 flush，取消则停上游。
- 超时 yml：`gateway.proxy.connect-timeout: 5s`，`gateway.proxy.response-timeout: 120s`，`gateway.proxy.max-in-memory-size: 16MB`。
- 错误：框架 404/405 保持原状态（`not_found` / `method_not_allowed`）；未捕获 500 `type=internal_error`；上游失败 502/504 `type=bad_gateway`。JSON：`{"error":{"message":"...","type":"..."}}`。
- 第一版路由 = Controller 映射。Health 在 core；Chat/MCP HTTP 以后在 orchestration。

## 6. 领域规划

- 本 Plan 只填 `core`。
- 依赖：`core/access/ai` 均为 `{}`；`orchestration` → core, access, ai。verify 必须过。
- 不实现 orchestration。

## 7. 前置条件

- 四个 `package-info` 已在仓库。
- Java 21 + Maven。不连 MySQL/Redis。

## 8. 拆分原则

- 先启动和链，再 WebClient 反代。
- 不加空 RouteDefinition / LB。
- 每刀测试绿再下一刀。

## 9. 实施计划

| 序号 | 任务 | 主要内容 | 前置 | 完成标准 |
| --- | --- | --- | --- | --- |
| 1 | 工程骨架 | parent Boot 4.1.x；依赖：webflux、validation、modulith-starter-core；测试：starter-test、modulith-starter-test、archunit-junit5。启动类 `com.liang.gateway.GatewayApplication`（`@Modulith`）。删除 `com.liang.Main`。`application.yml`：`spring.config.import: optional:file:./config/application-local.yml`，port 8080。禁止 starter-web | 无 | 能编译启动 |
| 2 | 模块测试 | `ApplicationModulesTest`：`ApplicationModules.of(GatewayApplication.class).verify()` | 1 | 四模块、依赖方向正确 |
| 3 | 过滤器链 | 公开合同 + `DefaultPipelineApi` + `DefaultGatewayFilterChain`。无 domain 包 | 2 | 顺序、短路单测绿 |
| 4 | Health 与错误 | `GET /health` → `{"status":"up"}`；`WebExceptionHandler` | 3 | WebTestClient 200 |
| 5 | 流式合同 | 测试包假 Filter 发三帧，按帧写出 | 3 | 断言非整包 collect |
| 6 | 写死反代 | `Upstream`、WebClient、内部 Proxy；链末有 Upstream 则转发。MockWebServer：非流式、SSE、取消订阅 | 4–5 | 反代测试绿 |
| 7 | 停 | 不写 Chat、Security、额度、orchestration | 6 | core 无 DeepSeek/MCP/access 类型 |

建议类（可微调，勿扩大）：

```
com.liang.gateway.core
  GatewayFilter / GatewayFilterChain / GatewayExchange / Upstream / PipelineApi
  internal.application.DefaultPipelineApi
  internal.application.DefaultGatewayFilterChain
  internal.infrastructure.WebClientConfig      # 任务 6
  internal.infrastructure.WebClientProxy       # 任务 6
  internal.web.HealthController
  internal.web.GatewayExceptionHandler
```

## 10. 阶段门禁

### 门禁 A（任务 1–5）

- verify、Health 200、链顺序/短路、三帧流式通过。
- 此时尚未加 WebClient 也可（任务 6 再加）。未过不开始任务 6。

### 门禁 B（任务 6）

- 假上游非流式透传、SSE 多帧透传、取消则上游停读。
- 未过不宣称 core 完成，不开始 access。

## 11. 验证路径

- `mvn test`
- 启动后 `GET /health`
- 证据：测试输出

## 12. 风险

- 引入 starter-web → 变成 MVC。
- 流式 `collectList` → 门禁必须按帧断言。
- 把 DeepSeek/编排写进 core → 禁止。

## 13. 后置吸收

- 公开合同若落地有变，改 Spec。
- 任务表完成后过程信息可删。

## 14. 当前状态

- 状态：执行中（待编码会话）
- 已完成：四模块 package-info（含 `orchestration`）、Spec、本 Plan
- 下一步：编码会话从任务 1 开始

## 15. 执行要求

- 行为变更先改 Spec。
- 禁止提交 `config/application-local.yml`。
- 不要 Lombok，不要空 domain 包，不要实现另外三个领域。
