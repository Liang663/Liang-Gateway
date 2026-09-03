# Plan｜落地 access 第一版

> 编码会话执行。长期口径以 `docs/spec-gateway-access.md` 为准。表以 `docs/sql-gateway-user.md` 为准。**只实现 access**，不要写 Chat / MCP / orchestration / core 反代。

## 文档信息

- 文档类型：Plan
- 文档状态：已完成
- 业务分类：网关
- 业务主题：调用方
- 更新时间：2026-09-03
- 上游依据：`docs/spec-gateway-access.md`、`docs/sql-gateway-user.md`、`docs/references/domains.md`、`docs/v1-plan.md`
- 完成后：公开类型名若变，回流 Spec；表已以 SQL 为准

## 1. 文档定位

- 回答：access 怎么接到现有 WebFlux 应用上，公开 API / HTTP / Redis 窗口怎么测。
- 不重写 core Spec、不改 SQL 字段名、不实现数据面 Chat。
- 执行者：编码会话。按任务顺序写，不自行扩大范围。

## 2. 需求

- 做：Security 进门；`user` / `user_access_token` / `usage_record`；创建令牌时打开套餐窗口；懒刷新；手动重置；`AccessApi`；用户与令牌管理 HTTP；自己的用量查询 HTTP。
- 不做：Chat、MCP、orchestration 编排、core Filter 挂额度、Caffeine、字符粗估、`llm_apikey_config` 管理面、预付费钱包 SET 剩余。
- 待澄清：无。

已采纳（第一版）：QPM 保留；当时限额列在令牌上（Token 计数）。金额拆到 `usage_limit`、单位改分，见后续 `docs/plan-gateway-access-billing.md`。Redis 挂了 503；到期刷新 `start += n * 时长`；只有手动重置 `start=now`；查询额度前都刷新。

## 3. 背景

- core 已实现链 / Health / 反代。access 目前只有 `package-info`（`allowedDependencies = {}`）。
- 本机 MySQL：`127.0.0.1:3306` 库 `liang_gateway`。Redis：`docs/dev-ops/docker-compose.yml` `6379`。

## 4. 目标

- `mvn test` 全绿（含现有 core）。
- 不变量：access 不 import `core` / `ai` / `orchestration`；无 starter-web；无 Lombok。

## 5. 技术方案

**公开面（`com.liang.gateway.access`，给以后 orchestration 与本 Plan 测试用）：**

- `Mono<Void> checkQuota(String tokenCode)`
- `Mono<Void> recordUsage(String tokenCode, long promptTokens, long completionTokens, UsageMeta meta)`
- `Mono<QuotaView> getQuota(String tokenCode)`
- `Mono<Void> resetQuota(String tokenCode, QuotaLayer layer)`
- 明细与统计可由内部服务提供，HTTP 调用即可；若测试方便也可挂到 `AccessApi`。
- 不提供 `estimateTokens`。
- 超限：`QuotaExceededException` → 429，`type=quota_exceeded`。
- Redis 不可用：`QuotaStoreUnavailableException` → 503，`type=service_unavailable`。
- 比较规则：`used > limit` 才拒。两 Token 皆 0 则 `recordUsage` 不写 Redis、不写明细。

core 的 `GatewayExceptionHandler` 会把 429 写成 `request_error`。access 自备 `WebExceptionHandler`（`@Order(-3)`），只处理上述两个异常，信封与 core 一致：`{"error":{"message","type"}}`。

**`checkQuota`：** 五小时、七天各执行「到期则刷新」再读 `used`；然后当前自然分钟 QPM `INCR`（`Asia/Shanghai`，键 TTL 120s）。任一层超限 429。任一步 Redis 失败 503。

**窗口 Redis**（前缀可配，默认 `gw:quota`；hash tag `{tokenCode}`）：

| 层 | 键 | 结构 |
| --- | --- | --- |
| 五小时 | `{prefix}:{tokenCode}:5h` | HASH `start`（unix 秒）、`used`；时长 18000 |
| 七天 | `{prefix}:{tokenCode}:week` | 同上；时长 604800 |
| QPM | `{prefix}:{tokenCode}:qpm:{yyyyMMddHHmm}` | STRING INCR |

限额读表（第一版）：`hourly_token_limit` / `weekly_token_limit` / `qpm_limit`。后续金额限额改 `usage_limit`，见 `docs/plan-gateway-access-billing.md`。

**刷新 Lua（检查、记账、getQuota 都走）：**

```
n = floor((now - start) / duration)
if n >= 1 then start = start + n * duration; used = 0; HSET
```

记账：同一条 Lua 先刷新再 `HINCRBY used`。  
手动重置：`HSET start=now used=0`（只有这条用 now）。  
创建令牌成功后立刻 `HSET start=now used=0` 两层；此步 Redis 失败则创建失败（503），不要留下「库有令牌、窗没打开」。

禁止：用 TTL 当时钟；每次记账改 `start`；只 `used=0` 不改 `start`；从 `create_time` 预切段号键。

**Security：** Bearer / `api-key` / query `api_key`。按 `access_token` 查令牌，用户与令牌均启用且未过期。principal：`userCode`、`tokenCode`、`apikeyCode`。`/health` 放行；`/admin/**` 比较 `X-Admin-Token` 与 `gateway.admin-token`；其余默认要访问令牌。无 Session、关 CSRF、无 formLogin。401 `type=unauthorized`。

**HTTP（无 UI）：**

- 管理：`/admin/users`、`/admin/users/{userCode}`、`/admin/users/{userCode}/tokens`、`/admin/users/{userCode}/tokens/{tokenCode}`；`POST .../tokens/{tokenCode}/quota/reset`。
- 调用方自己：`GET /v1/usage/quota`、`GET /v1/usage/stats?range=hour|day|week|month|total`、`GET /v1/usage/records`。只返回当前令牌。
- quota：两档 `used`、`limit`、`windowStart`、`windowEnd`。
- stats：该日历范围合计（上海时区）；第一版不做直方图。
- 不管理大模型 Key。测令牌时允许插入一行占位 `llm_apikey_config`。

**数据：** Flyway 按 SQL 一次建四张表（建议 `V1__user_apikey_usage.sql`）。access Entity：用户、令牌、明细。JPA 一律 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`。Redis 响应式客户端。无 Caffeine。

**配置：** `application-local.yml` 已有 datasource、redis、admin-token。可加 `gateway.quota.key-prefix`。测试：Redis **必须** Testcontainers（或等价真实 Redis）；MySQL 用 Testcontainers 或本机。禁止内存 Map 冒充窗口。

## 6. 领域规划

- 只填 `access`。依赖仍 `{}`。`ApplicationModules.verify()` 必须绿。
- 不把额度做成 `GatewayFilter`。

## 7. 前置条件

- core 可编译，`mvn test` 在动手前应已绿。
- Docker 可用于 Redis Testcontainers。
- Spec / SQL 已按 2026-09-03 口径生效。

## 8. 拆分原则

- 先表与仓储，再窗口 Lua 与 `AccessApi`，再明细统计，再 Security，再 HTTP。
- 每步有测试，绿了再下一刀。
- 不顺手写 Chat。

## 9. 实施计划

| 序号 | 任务 | 主要内容 | 前置 | 完成标准 |
| --- | --- | --- | --- | --- |
| 1 | 依赖 | `starter-data-jpa`、MySQL 驱动、Flyway、`starter-data-redis`（reactive）、`starter-security`（WebFlux）。禁止 starter-web、Lombok | 无 | 编译过 |
| 2 | 表与仓储 | Flyway 按 SQL；用户/令牌/明细 Entity、Repository | 1 | 按 `access_token` 能查；禁用/过期可测 |
| 3 | 套餐窗口 | 创建令牌打开 HASH；刷新 Lua；`checkQuota` / `recordUsage` / `getQuota` / `resetQuota` | 2 | 未调用可查条；7–12 用完 14 点再来 `start=12:00`；重置 `start=now`；超限 429；Redis 挂 503 |
| 4 | 明细与统计 | `recordUsage` 写 `usage_record`；stats / records | 3 | 时日周月总量与明细合计一致 |
| 5 | Security | 转换器 + 认证；401；principal 三字段 | 2 | 无/错/禁用 → 401；`GET /health` 仍 200 |
| 6 | HTTP | 用户令牌 CRUD；`/v1/usage/*`；admin 重置 | 4–5 | 自己的令牌能看自己的条和明细；无管理令牌 401 |
| 7 | 停 | 不写 Chat、orchestration、Filter 额度、Key 管理 | 6 | access 源码无 `com.liang.gateway.core` / `.ai` import |

建议类型（可微调，勿扩大）：

```
com.liang.gateway.access
  AccessApi
  QuotaView / WindowView / UsageMeta / QuotaLayer
  QuotaExceededException / QuotaStoreUnavailableException
  internal.application.DefaultAccessApi
  internal.infrastructure.jpa（Entity / Repository）
  internal.infrastructure.redis（Lua 窗口）
  internal.security（Bearer / api-key / Admin）
  internal.web（用户令牌、用量、AccessExceptionHandler）
```

## 10. 阶段门禁

### 门禁 A（任务 1–4）

- 窗口与明细单测绿，不依赖 Chat。
- 含：懒刷新接上一扇结束点、手动重置用 now、Redis 不可用拒绝、额度条无需先调用。
- 未过不开始 Security / HTTP。

### 门禁 B（任务 5–6）

- 401 / 429 / 503 语义正确；`/health` 不被误伤。
- admin CRUD 与用量查询可用。
- `ApplicationModules.verify()` 通过；access 无出边。
- 未过不开始 ai。

## 11. 验证路径

- `mvn test`
- 本地可选：compose 起 Redis，连本机 MySQL，无 Key 打 `/health` 以外路径应得 401。
- 证据：测试输出。

## 12. 风险

- Security 误伤 `/health`。
- 在事件循环上调 JPA。
- 用 Guava RateLimiter 或内存 Map 冒充 Redis。
- 把 Key 打进日志。
- 到期刷新误写成 `start=now`（与手动重置混淆）。
- 刷新与 `HINCRBY` 拆成两条命令。
- 统计分桶和套餐窗口混用一把尺。

## 13. 后置吸收

- 公开类型名若与本 Plan 建议不一致，改 Spec 公开能力表述（不必抄类名）。
- Redis 键前缀若写入配置，以后可补 Ops；不要把键格式写进 SQL。
- 过程取舍不必进 Spec。

## 14. 当前状态

- 状态：已完成（master `3a15481`）
- 金额限额与模型授权：见 `docs/plan-gateway-access-billing.md`

## 15. 执行要求

- 禁止提交 `config/application-local.yml`。
- 不要 Lombok。不要依赖 core / ai。
- 编码按本 Plan 任务表推进，绿一刀再下一刀。
