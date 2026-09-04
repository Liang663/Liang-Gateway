# Liang-Gateway 第一版

产品边界与实现的**索引**。细节按专题拆到 `docs/references/`。

**改设计必须同步改文档。** 冲突时：做 / 不做以本文为准；领域切分以 [domains.md](references/domains.md) 为准；某一刀怎么写代码以对应 reference 为准。

---

## 文档地图

| 文档 | 内容 | 状态 |
|---|---|---|
| [v1-plan.md](v1-plan.md) | 目标、非目标、技术栈、简历、实现顺序 | 现行 |
| [references/domains.md](references/domains.md) | `core` / `access` / `ai` + 编排入口 `orchestration` | 现行 |
| [spec-gateway-core.md](spec-gateway-core.md) | core 长期 Spec | 生效 |
| [plan-gateway-core.md](plan-gateway-core.md) | core 第一版编码计划 | 已实现 |
| [spec-gateway-access.md](spec-gateway-access.md) | access 长期 Spec | 生效 |
| [plan-gateway-access.md](plan-gateway-access.md) | access 第一版编码计划 | 已实现 |
| [plan-gateway-access-billing.md](plan-gateway-access-billing.md) | 金额限额与模型授权 | 已实现 |
| [sql-gateway-user.md](sql-gateway-user.md) | 用户、令牌、限额、模型、用量、出站日志 | 生效 |
| [sql-gateway-mcp.md](sql-gateway-mcp.md) | MCP 服务器与工具 | 生效 |
| [spec-gateway-ai.md](spec-gateway-ai.md) | ai Chat Spec | 生效 |
| [spec-gateway-mcp.md](spec-gateway-mcp.md) | MCP 协议转换 Spec | 生效 |
| [plan-gateway-mcp.md](plan-gateway-mcp.md) | MCP 编码计划 | 已实现 |
| [plan-gateway-ai.md](plan-gateway-ai.md) | ai Chat 编码计划 | 已实现 |
| [spec-gateway-orchestration.md](spec-gateway-orchestration.md) | 编排数据面 Spec | 生效 |
| [plan-gateway-orchestration.md](plan-gateway-orchestration.md) | 编排数据面编码计划 | 已实现 |
| [research-higress-token-limit.md](research-higress-token-limit.md) | Higress Token 限制调研（已吸收） | 参考 |

已取消：独立 `llm` / `mcp` / `metering` / `admin` 模块文档。MCP 与 Chat 同属 `ai`。

---

## 0. 一句话

Java AI 网关：路由 + 过滤器链 + 反向代理（Chat 转到 DeepSeek）+ HTTP→MCP；调用方用 API Key 鉴权并做 Token 计量/限流。不审内容。运行时 Spring WebFlux。

---

## 1. 做 / 不做

**要验收的**

1. `POST /v1/chat/completions`：调用方带 `model` 与拼好的 `messages`（及其余字段原样转发），非流式与 SSE 真流式打到所配供应商（第一版 DeepSeek），chunk 随到随转。
2. 流式记录 TTFT（发出上游请求到第一帧）与总耗时，写入出站日志；结束后按 `usage` 记 Token 与金额（分），按模型名分组。
3. 无合法 API Key → 401；超额 → 429；额度 Redis 不可用 → 503。
4. HTTP API 可配成 MCP Tool；协议 `2026-07-28`：`server/discover` / `tools/list` / `tools/call`；`POST /mcp/{path}`。不做 QPM/限额。工具失败返回具体原因。
5. 热路径不在 Netty 事件循环上跑 JPA / 同步 JDBC。

**不做**

| 不做 | 原因 |
|---|---|
| RAG / 语义缓存 / 意图 / 网关侧历史会话 | 推迟 |
| 内容安全、黄赌毒政、脱敏 | 下放到业务 |
| 多厂商适配实现 | 第一版只实现 DeepSeek，目录与接口不写死 |
| 网关内 Agent | 网关不循环调工具 |
| 远程 MCP 反代、市场工具、stdio、WebSocket | 只做 HTTP→MCP |
| 谎称 resources/prompts | initialize 只声明 tools |
| Spring Cloud / SCG / Nacos / Sentinel | 传统网关后置 |
| Servlet / 虚拟线程当请求处理模型 | 已定为 WebFlux |
| R2DBC | 配置仍 JPA |
| Spring AI / LangChain4j | 协议转发 |
| Wasm / xDS / 对外 SPI | 改源码扩展 |
| 管理台 UI | 少量管理 HTTP |
| 实例 LB、熔断、跨模型 fallback | 传统网关后置 |

---

## 2. 技术栈

| 项 | 选择 |
|---|---|
| Java | 21 |
| 应用 | Spring Boot 4.1.x + Spring Modulith 2.1.x，单 Maven 模块 |
| 运行时 | **仅** Spring WebFlux。禁止 `spring-boot-starter-web` |
| 出站 | `WebClient`，归 `core` 的反代；mcp 出站也可使用 |
| 鉴权 | Spring Security WebFlux，归 `access` |
| 过滤器 / 路由 / 反代 | `core`，见 [spec-gateway-core.md](spec-gateway-core.md) |
| 配置库 | MySQL 8 + JPA + Flyway（access / mcp） |
| 计数 | Reactive Redis，归 `access` |
| 测试 | JUnit 5、AssertJ、WebTestClient、Modulith verify |
| 禁止 | Spring AI、LangChain4j、Spring Cloud*、SCG、Sentinel、Lombok |

启动类 `com.liang.gateway.GatewayApplication`。密钥只在 gitignore 的 `config/application-local.yml`。MySQL 用本机；Redis 用 [dev-ops/docker-compose.yml](dev-ops/docker-compose.yml) 官方镜像。

---

## 3. 领域

四个模块，详见 [domains.md](references/domains.md)。

```
core     {}                         网关转发：链、单 URL 反代、Health
access   {}                         调用方：Security + 金额窗口 + 模型授权 + 记账
ai       {}                         Chat 协议 + MCP 协议转换（子包并列，不共享限额）
orchestration    core, access, ai           编排：数据面入口，只排序调用
```

三个业务域互不依赖。额度由 orchestration 调 access，不是 Security 顺便做掉，也不是 core 去调。没有独立 `llm` / `mcp` / `metering` / `admin`。

---

## 4. 尚未拆出的专题（摘要）

**core：** 过滤器链、Health、通用 WebClient 反代（单 URL，无注册中心/LB）。第一版路由就是 Controller 映射。详见 [spec-gateway-core.md](spec-gateway-core.md)、[plan-gateway-core.md](plan-gateway-core.md)。

**access：** Security 只验进门。金额限额在 `usage_limit`（分；可不限额或五小时/七天窗）、QPM、令牌授权模型名。判超限读 Redis，写时回写已用。明细记 Token 与金额，按模型名分组。不读单价、不做出站。

**ai：** Chat：模型目录与官方单价、出站 Key、组上游、读 usage 并算分、出站日志。MCP：`mcp_server` / `mcp_tool`、2026 JSON-RPC、组工具 HTTP、失败原因包装。两套子包并列。**没有数据面 HTTP。**

**orchestration：** Chat：`POST /v1/chat/completions`，额度 → 组上游 → 转发 → 日志与记账。MCP：`POST /mcp/{path}`，进门后不查额度，组工具请求 → 捕获上游 → 包装结果。不写业务算法。

**表：** Chat/调用方见 [sql-gateway-user.md](sql-gateway-user.md)；MCP 见 [sql-gateway-mcp.md](sql-gateway-mcp.md)。归 ai。

**线程：** 事件循环只做非阻塞。JPA 必须 `boundedElastic`。

---

## 5. 实现顺序

1. 领域划分（本文 + [domains.md](references/domains.md)）
2. 四个 `package-info` + `ApplicationModules.verify()`
3. **core**（应用能起、链、反代）见 [plan-gateway-core.md](plan-gateway-core.md)
4. **access**（进门 + 额度 API）
5. **access 金额/授权**（`plan-gateway-access-billing.md`）与 **ai Chat**（`plan-gateway-ai.md`）
6. **orchestration**：先扩 core 转发合同，再串 Chat，再接 MCP 数据面（见 `plan-gateway-orchestration.md`）
7. 种子、README、补测试

---

## 6. 简历口径（实现后才能用）

未做的不要写。不要写客户端、入账、内容安全。不要写「我负责的部分」。模块名不必写进简历。

**Liang-Gateway**

项目描述：自研 AI 网关，作为大模型调用与 Agent 工具调用的统一接入层，用于管理和控制大模型调用请求。基于 Spring WebFlux 搭建网关核心，使用 WebClient 实现全异步流式全链路处理。实现模型转发、MCP 协议转换、流量控制等多种能力，保证 SSE 长连接场景下可计量、可转发。

- 针对模型调用构建 OpenAI 兼容接入，支持非流式响应与 SSE 流式透传，记录 TTFT 与端到端耗时。
- 针对请求构建响应式过滤器链，实现鉴权、额度控制与上游转发。
- 实现调用方套餐额度：五小时与七天按金额（分）限额；请求前检查授权模型与已用金额，请求后按实际上游 Token 与金额记账，并按模型名分组统计；支持 QPM 防刷与用量明细；Redis 不可用则拒绝服务。
- 实现 HTTP 到 MCP 的协议转换（2026-07-28）：server/discover、tools/list、tools/call；path/query/header/body 映射；OpenAPI 导入生成 Tool；工具失败返回具体原因。
- 使用 Spring Modulith 治理项目代码，按模块化单体划分领域并约束模块依赖，便于后续重构与按能力拆分为微服务。

---

## 7. 硬约束

1. 先读本文和 [domains.md](references/domains.md)。
2. 不要 Servlet 数据面、SCG、多厂商、内容审核、Spring AI。
3. 不要提交 `config/application-local.yml`。
4. 不要独立 `llm` / `mcp` / `metering` / `admin` / `infrastructure` 包。业务域不要互相依赖。
5. `core` / `access` / `ai` 不得互相 import；只有 `orchestration` 依赖这三者。`orchestration` 不得实现配额、拷流、JSON-RPC。
6. 流式禁止 `collectList` / `block`。热路径禁止同步 JPA。
