# Plan｜配置库从 JPA 换成 R2DBC

> 编码会话执行。口径以已更新的 `docs/v1-plan.md`、各 Spec、`docs/references/domains.md` 为准。运行时 MySQL 走 R2DBC；Flyway 仍 JDBC。不改表结构，不改公开 API。

## 文档信息

- 文档类型：Plan
- 文档状态：执行中
- 业务分类：网关
- 业务主题：配置库
- 更新时间：2026-09-08
- 上游依据：`docs/v1-plan.md`、`docs/sql-gateway-user.md`、`docs/sql-gateway-mcp.md`
- 前置：数据面已在 master；MCP JSON-RPC 已下沉
- 完成后：长期结论已写入 v1-plan / Spec；本 Plan 可删

## 1. 文档定位

- 回答：如何把运行时 MySQL 从 JPA/JDBC 换成 R2DBC，Flyway 留在启动期 JDBC。
- 不重写额度算法、Chat drain、MCP JSON-RPC、工具映射。

## 2. 需求

- 做：`io.asyncer:r2dbc-mysql` + Spring Data R2DBC；access / ai 行映射与 repository 改为 `Mono`/`Flux`；同步事务改 `TransactionalOperator`；热路径不再 `boundedElastic` 包 JPA。
- 不做：改 Flyway 表结构、R2DBC 跑迁移、Hibernate Reactive、Vert.x、独立 persistence 模块、改 Redis、改公开 API。
- 待澄清：无。

已采纳：Flyway 与测试 `JdbcTemplate` 继续 JDBC；TINYINT 不改 DDL，用 converter 或应用层转换。

## 3. 背景

第一版用 JPA + `JpaExecutor`/`boundedElastic` 避开事件循环。Redis 已是非阻塞。要让请求路径上的 MySQL 也非阻塞，必须换栈，不能只把 `JpaRepository` 包进 `Mono`。

## 4. 目标

- `mvn test` 全绿。
- 主代码无 `JpaRepository`、`jakarta.persistence`、`JpaExecutor`、用 `boundedElastic` 跑库。
- Flyway 启动仍能建表。

## 5. 技术方案

驱动：`io.asyncer:r2dbc-mysql`（Boot BOM）。访问：`ReactiveCrudRepository` + 聚合用 native SQL。事务：`R2dbcTransactionManager` + `TransactionalOperator`。

配置两套 URL：`spring.r2dbc` 运行时；`spring.datasource` / `spring.flyway` 给 Flyway 和测试断言。Hikari 池缩小。

行对象改 Spring Data Relational 注解；包 `jpa` → `persistence`。`user` / `usage` 靠 MySQL 方言引用。自增：`id == null` 为 insert。

令牌图写入、MCP OpenAPI 导入必须整批事务。

## 6. 领域规划

模块与依赖方向不变。持久化仍在 access / ai 的 `internal`。core / orchestration 无表。

## 7. 前置条件

- compose MySQL 3307、Redis 6379；测试库 `liang_gateway_test`。
- 文档已改「要做 R2DBC」。

## 8. 拆分原则

按域切，每刀测试绿。先探针锁定 TINYINT 与保留字，再迁 access，再 ai Chat，再 MCP，最后拆 JPA。

## 9. 实施计划

| 序号 | 任务 | 主要内容 | 前置 | 完成标准 |
| --- | --- | --- | --- | --- |
| 1 | 文档与探针 | 文档口径；加 R2DBC 依赖与 URL；读写 `user` | 无 | 探针绿；现有测试仍绿 |
| 2 | access | 五行对象与 repository；删 `JpaExecutor`；令牌事务 | 1 | access 测绿；access 无 JPA |
| 3 | ai Chat | 模型 / Key / 出站日志 | 2 | Chat 与 LLM 管理测绿 |
| 4 | ai MCP | server/tool；导入事务；删 `AiJpaExecutor` | 3 | MCP 测绿；ai 无 JPA |
| 5 | 拆 JPA | 去 starter-data-jpa 与 `spring.jpa` | 4 | `mvn test`；无 JPA 引用 |

## 10. 门禁

- A（任务 1）：探针证明 `user` 可引用、`enabled` 可读写。未过不改编排业务。
- B（任务 2–4）：该域 HTTP/API 测绿，请求路径无 `block`。
- C（任务 5）：全绿；主代码无 JPA。

## 11. 验证

- `mvn test`
- 主代码：无 `jakarta.persistence`、`JpaRepository`、`JpaExecutor`
- Flyway 启动建表

## 12. 风险

- TINYINT 读成 `Byte` 而非 `boolean`：converter，不改表。
- 保留字未引用。
- 双连接池抢连接：Hikari 调小。
- 令牌/导入事务不完整。

## 13. 后置吸收

- 长期口径已在 v1-plan / Spec。过程任务表完成后可删本 Plan；从 AGENTS 导航拿掉。

## 14. 当前状态

- 状态：执行中
- 已完成：文档口径、依赖、access/ai 行映射与 repository、去掉 JPA、`ApplicationModules.verify`
- 阻塞项：本机 Docker 引擎未起来，`mvn test` 连不上 `127.0.0.1:3307`
- 下一步：compose 起来后跑全量 `mvn test`，按失败修 TINYINT/保留字/事务

## 15. 执行要求

- 不要 Lombok。不要拆模块。禁止提交真实 Key。
- 不要顺手删 `PipelineApi`、改 Chat drain、改 Redis。
