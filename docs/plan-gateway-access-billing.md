# Plan｜access 金额限额与模型授权

> 编码会话执行。在已落地的 access 第一版上改。口径以更新后的 `docs/spec-gateway-access.md`、`docs/sql-gateway-user.md` 为准。不要实现 Chat/MCP/orchestration。

## 文档信息

- 文档类型：Plan
- 文档状态：已完成
- 业务分类：网关
- 业务主题：调用方
- 更新时间：2026-09-03
- 上游依据：`docs/spec-gateway-access.md`、`docs/sql-gateway-user.md`
- 完成后：行为变更已写入 Spec / SQL

## 1. 文档定位

- 回答：限额从令牌列拆到 `usage_limit`；单位分；令牌授权模型名；明细记金额；Redis 判超限、写时回写 `used`。
- 不重写进门鉴权与窗口时钟算法（仍懒刷新、`start += n * 时长`、重置才 `start=now`）。
- 不做出站日志（`llm_call_log` 归 ai Plan）。

## 2. 需求

- 做：Flyway V2（access 相关部分）；去掉令牌上的 `apikey_code` 与 Token 限额列；建 `usage_limit`、`user_access_token_model`；`usage_record.amount_fen` 且 `model` 必填；`AccessApi` 增加模型授权检查与列表；Redis `used` 按分一次 `HINCRBY` 本笔金额；写后再把 Redis 新值写入 `usage_limit.used`；管理 HTTP 维护授权模型与限额行。
- 不做：ai 目录、单价计算、Chat、`llm_call_log`。
- 待澄清：无。

已有窗口 `used` 若已有 Token 计数，V2 上线无生产数据则可清空或按 0 迁移。

## 3. 背景

- access v1 已提交：窗口按 Token 累加，令牌 N:1 绑 Key，限额列在令牌上。
- Chat 需要：限金额、按模型授权、双维度记账。Key 改挂模型，不挂令牌。限额改独立行，支持不限额。

## 4. 目标

- 现有 access 测试改绿；新增授权与金额用例。
- 不变量：仍不 import core/ai。

## 5. 技术方案

**API：**

- `checkQuota(tokenCode)`：只读 Redis。有 `limit_type=1/2` 则比较窗口已用分与对应行的 `usage`；仅有 `0` 则跳过金额窗。QPM 不变。
- `assertModelAllowed(tokenCode, model)`：不在授权表 → **403** `type=forbidden`，与金额超额 429 区分。
- `listAllowedModels(tokenCode)`：只返回模型名。
- `recordUsage(tokenCode, prompt, completion, amountFen, model, meta)`：对仍生效的金额窗各做一次 `HINCRBY used amountFen`（本笔分，不是循环）；用 Redis 返回值回写各行 `usage_limit.used`；明细写 Token + `amount_fen` + model。prompt+completion+amount 皆 0 则跳过。

**Principal：** 去掉 `apikeyCode`，保留 `userCode`、`tokenCode`。

**创建令牌：** 不再写 `apikey_code`；可同时写入授权模型名列表；按入参写 `usage_limit`（`0` 一行，或 `1`/`2`），并打开对应 Redis 窗。

**统计：** 现有时/日/周/月/总量增加按 `model` 分组的 Token 与分；扫 `usage_record`，不新建汇总表。

**错误：** 未授权模型不要调 ai。access 自备 403 映射。

## 6. 领域规划

- 仍只 access。`llm_model`、`llm_call_log` 由 ai Plan 建 Entity；Flyway 可同 V2 建表以免编排前缺表；本 Plan **不写** ai Entity。

## 7. 前置条件

- SQL 已写 V2 变更。access v1 代码在 master。

## 8. 拆分原则

- 先迁移与仓储，再窗口按分与限额行，再授权 API，再 HTTP/测试。

## 9. 实施计划

| 序号 | 任务 | 主要内容 | 前置 | 完成标准 |
| --- | --- | --- | --- | --- |
| 1 | Flyway V2 | 删令牌 apikey 与旧限额列；建 `usage_limit`、授权表；明细加分 | 无 | 空库可从 V1+V2 起出 |
| 2 | 窗口按分 | Redis `used` 为分；`checkQuota` 读 Redis；`recordUsage` 一次 HINCRBY 并回写 `usage_limit.used` | 1 | 超额按分 429；不限额不走金额 429 |
| 3 | 模型授权 | assert/list；管理可配模型名 | 1 | 未授权 403；不查 ai 表 |
| 4 | 记账与统计 | recordUsage 带分与模型；stats 按模型扫明细 | 2–3 | 明细合计与分组一致 |
| 5 | Principal/HTTP | 去掉 apikeyCode；额度条展示分；维护限额行 | 4 | 旧测试改绿 |

## 10. 门禁

- A（1–4）：金额窗 + 授权单测绿。
- B（5）：HTTP 与 verify。未过不开始编排。

## 11. 验证

- `mvn test`

## 12. 风险

- 把单价或模型目录引进 access。
- 判超限改去读 MySQL `used`，与 Redis 双源。
- 未授权与超额都用 429，排查不清。
- V2 与仍按 Token 写入、仍读令牌限额列的旧测试打架。

## 13. 后置吸收

- 已写入 Spec/SQL。公开方法名若变，改 Spec。

## 14. 当前状态

- 状态：已完成
- 下一步：orchestration 串 Chat 数据面

## 15. 执行要求

- 不要 Lombok。不要 import ai。禁止提交真实 Key。
