# 调研｜Higress Token 限制

> 定位：对照 Higress 源码，解释 Token 限制怎么做。非正式真相源。本项目现行口径以 Spec / SQL 为准。
>
> 源码：`d:\java\higress`。套餐形态对照 Claude Code Pro / Max 公开说明（五小时 + 周限额）。

## 0. 元信息

- 文档类型：调研
- 文档状态：已吸收
- 更新时间：2026-09-03
- 关联：`docs/spec-gateway-access.md`、`docs/sql-gateway-user.md`

## 1. 先看结论

Higress 没有一个叫「Token 限制」的插件。和用量有关的是三件不同的事：

| 插件 | 管什么 | 单位 | 本项目 |
| --- | --- | --- | --- |
| `ai-token-ratelimit` | 时间窗口内累计 Token，超了拒下一次 | 首次 INCRBY 才 EXPIRE | 只借鉴「先读后加」；**不用**它的时钟（没调用就没有窗口） |
| `cluster-key-rate-limit` | 时间窗口内请求次数 | 请求阶段 check+INCR | QPM 对标这个 |
| `ai-quota` | 预付费剩余额度，用完 403 | Redis 剩余池，无 TTL | 不做 |
| `ai-context-limit` | 请求进模型前估上下文长度 | 本地 BPE | 不做 |

Higress 限流插件 Redis 挂了会放行。本项目不是插件旁路，**计不了量就拒绝服务**。

## 2. Higress 各插件

### 2.1 `ai-token-ratelimit`

路径：`plugins/wasm-go/extensions/ai-token-ratelimit/`。

- 窗口只允许四档：秒/分/时/日（TTL 1 / 60 / 3600 / 86400）。一条规则一档。
- 计数对象是 **input + output Token**，不是请求次数。
- 维度可按 header / query / cookie / consumer / IP。

一次请求（`main.go`）：

```
请求头 → Lua 只 GET，current > threshold 则 429
上游生成 → 不中途检查、不掐流
响应结束 → 抽 usage；有则 INCRBY(prompt+completion)，键第一次出现时 EXPIRE
```

请求阶段绝不预扣。无 `usage` 不记账。并发允许短暂超打。窗口锚在首次 INCRBY，是固定窗口，不是滑动窗口。

Redis 调用发不出去时 `ActionContinue`：**fail-open**（插件挂了不影响主链路）。本项目不采用。

### 2.2 `cluster-key-rate-limit`

请求次数在请求阶段就能确定是 1，所以 Lua 里 check 和 `INCR` 一次做完。Token 不能套这套预扣，因为用量要等模型返回。

### 2.3 `ai-quota`（Higress 里叫「刷新用量」的就是这个）

Redis 存**还剩多少**，无时间窗口。检查 `GET`，`<=0` / 空 / Redis 错 → **403**。响应结束 `DECRBY`。

管理面三条路径（`main.go` `refreshQuota` / `queryQuota` / `deltaQuota`）：

- `…/quota/refresh`：`SET chat_quota:{consumer} = quota`，把剩余额度写成管理员给的整数。
- `…/quota`：`GET` 剩余。
- `…/quota/delta`：`INCRBY` / `DECRBY` 加减剩余。

这是预付费钱包充值，不是套餐时钟到期。本项目限额在表字段上，已用在当前段 Redis 键上，**不提供这种 SET 剩余**。

`ai-token-ratelimit` 没有 refresh 接口。所谓到期就是键 TTL 没了；TTL 还是第一次记账才挂上的，所以没打过模型就看不到窗口。

### 2.4 `ai-context-limit`

请求前 BPE 估输入，超了 400。和调用方套餐无关。

### 2.5 谁读 usage

限流 / 配额插件自己在流式响应里调 `tokenusage.GetTokenUsage`。本项目拆领域：**读 usage 归 ai，累加归 access**。

## 3. 编码套餐两层

Claude Code：五小时管突发，周限额管一周总量，两层同时生效。不是 Higress 那种秒/分/时/日规则引擎。

## 4. 已吸收到本项目（勿在此改口径）

详见 Spec / SQL / Plan。摘要：

- 表字段保留 `qpm_limit`、`hourly_token_limit`；`daily_token_limit` 改为 `weekly_token_limit`。
- QPM：自然分钟请求次数，检查时 INCR。
- Token 套餐：每层只存当前一扇窗口（HASH：`start`+`used`）。创建时打开。到期刷新：`start += n*时长`、`used=0`（接上一扇结束点）。手动重置才 `start=now`。查询/检查/记账前都先刷新。
- 展示：当前窗口已用/限额；明细；日历时/日/周/月/总量。
- Redis 在 `checkQuota` 失败 → 503，不放行。
- 不预估、不预扣、不中途掐流。主体 `user_access_token.code`。
