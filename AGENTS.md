# AGENTS.md｜项目协作约定

开发者和 Agent 沿 `AGENTS.md -> Spec / Plan -> 代码` 取上下文。产品边界以 `docs/v1-plan.md` 为准；领域切分以 `docs/references/domains.md` 为准。

## 1. 文档导航

| 文档 | 职责 |
| --- | --- |
| `docs/v1-plan.md` | 第一版产品做/不做、技术栈、简历口径、实现顺序 |
| `docs/references/domains.md` | 领域：`core` / `access` / `ai`，编排入口 `orchestration` |
| `docs/spec-gateway-core.md` | core 长期真相源 |
| `docs/plan-gateway-core.md` | core 第一版落地计划 |
| `docs/spec-gateway-access.md` | access 长期真相源 |
| `docs/plan-gateway-access.md` | access 第一版落地计划（已完成） |
| `docs/plan-gateway-access-billing.md` | access 金额限额与模型授权（已完成） |
| `docs/spec-gateway-ai.md` | ai Chat 长期真相源 |
| `docs/spec-gateway-mcp.md` | ai MCP 协议转换长期真相源 |
| `docs/plan-gateway-mcp.md` | ai MCP 第一版落地计划（已完成） |
| `docs/plan-gateway-ai.md` | ai Chat 第一版落地计划（已完成） |
| `docs/sql-gateway-user.md` | 用户、令牌、限额、模型、用量、出站日志表结构 |
| `docs/sql-gateway-mcp.md` | MCP 服务器与工具表结构 |
| `docs/research-higress-token-limit.md` | Higress Token 限制调研（已吸收，口径以 Spec / SQL 为准） |
| `config/application-local.yml.example` | 本地机密模板；真文件 gitignore |

尚未撰写：orchestration 的 spec/plan。

## 2. 项目约定

- 冲突时：可运行代码 > 已生效 Spec > 进行中的 Plan > 聊天口述。
- 改设计必须同步改对应文档。本仓库设计会话与编码会话分开：编码会话按 Plan 写代码，不自行扩大范围。
- 单 Maven 模块；根包 `com.liang.gateway`；Modulith 子包即领域。
- 仅 Spring WebFlux，禁止 `spring-boot-starter-web` 与 Spring Cloud Gateway。
- `core` / `access` / `ai` 互不依赖；只有 `orchestration` 依赖这三者，且只做编排。
- Security 只解决能否进网关；额度与记账在 access，由 orchestration 在调用 ai 前后显式调用。ai 不碰 access。
- 流式路径禁止 `collectList` / `block`。
- 禁止提交 `config/application-local.yml`、真实 API Key、数据库密码。
- 不要 Lombok。不要独立 `llm` / `mcp` / `metering` / `admin` / `infrastructure` 模块。
- 不要做内容审核/脱敏、注册中心、负载均衡（第一版）。

## 3. 经验避坑

- 本地 MySQL 用开发机实例；Redis 用 `docs/dev-ops/docker-compose.yml` 官方镜像。
- Docker / MySQL 的 Skill 或 MCP 只用 GitHub 官方发布。
- 入站调用方 `Authorization` 不得默认转到模型上游。
