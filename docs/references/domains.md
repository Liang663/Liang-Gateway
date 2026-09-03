# 第一版领域划分

被 [v1-plan.md](../v1-plan.md) 引用。写任何代码前先读本文。包名、依赖、表归谁以本文为准。

**现行切法：** 三个业务域 + 一个编排入口。业务域互不依赖；入口依赖全部业务域，**只编排、不实现业务**。

```
core     网关转发：过滤器链、单 URL 反代、Health
access   调用方：鉴权、金额窗口、模型授权、记账（Security 只解决进不进得来）
ai       Chat 协议（组上游、usage、计价）；MCP 后置
orchestration    编排：一次调用按序调上面三个，自己不写 Redis / 不写拷流 / 不写模型协议
```

根包 `com.liang.gateway`。没有独立 `llm` / `mcp` / `metering` / `admin` / `infrastructure`。

---

## 1. 为什么要有 orchestration

Security 只能回答「这个 Key 能不能进网关」。额度够不够、用了多少 Token，仍是 **access 的业务**，必须有人在调用过程中显式去问 access。

若把这段顺序写进 core（`access.查额度(); ai.处理(); core.转发()`），core 就要依赖 access 和 ai；而 ai 还要用 core 的反代 → 成环。

若写进 ai，ai 就要知道配额，调用方和协议缠在一起。

所以单独放 **orchestration**：HTTP 数据面的统一入口，依赖 `core`、`access`、`ai`，只做顺序调用和 DTO 转换。

---

## 2. 依赖（星形，无环）

```
                    ┌─────────┐
                    │  orchestration  │  依赖：core, access, ai
                    └───┬─────┘
            ┌───────────┼───────────┐
            ▼           ▼           ▼
       ┌────────┐  ┌────────┐  ┌────────┐
       │  core  │  │ access │  │   ai   │
       │  无依赖 │  │  无依赖 │  │  无依赖 │
       └────────┘  └────────┘  └────────┘
```

```
core     {}
access   {}
ai       {}
orchestration    core, access, ai
```

三个业务域 **禁止** 互相 import。orchestration **禁止** 实现配额算法、反代拷流、MCP JSON-RPC、DeepSeek 协议细节。

---

## 3. 一次 Chat 怎么走（领域粒度）

1. **access（Security）**：凭证无效则到不了后面。只解决进门。
2. **orchestration**：数据面入口。读请求里的 `model` 与 body。
3. **access**：该令牌是否授权此模型；金额窗口是否够。不够 429，未授权 403，Redis 挂 503。
4. **ai**：按模型组出站 URL/头/body（透传），不管访问令牌。
5. **core**：反代（可 SSE）。编排在发出时打点，第一帧算 TTFT。Chat 为拿到 usage，客户端断开后仍 drain 上游直到 usage 或结束（与通用反代「断开即取消」不同）。
6. **ai**：读 usage，按目录官方单价算分。流式强制 `include_usage=true`。编排把本笔成败与耗时交给 ai 写入出站日志。
7. **access**：按真实 usage 记 Token 与金额（一次加上本笔分，并把 Redis 新已用回写限额行）。

MCP 同理：orchestration 调 access 额度（至少 QPM）→ 调 ai 处理 JSON-RPC / Tool HTTP → 必要时再调 core 转发或由 ai 自己出站。MCP 无模型 usage 则不走 Chat 记账。orchestration 仍然不写映射和 session。

**管理接口不是数据面：** 用户与令牌管理在 access，`/admin` 下 MCP 配置仍在 ai。Health 在 core。

---

## 4. 各块拥有什么

### core

链、单 URL 反代、Health、502/500。第一版路由对业务来说就是 orchestration 的 Controller 映射；core 不造注册中心和 LB。  
不拥有：额度、Chat/MCP 语义。  
落地：`spec-gateway-core.md`、`plan-gateway-core.md`。access：`spec-gateway-access.md`、`plan-gateway-access.md`。

### access

`user` / `user_access_token` / `user_access_token_model` / `usage_limit` / `usage_record`、金额窗口 Redis、模型名授权、记账 API、Security。  
不拥有：单价、出站 Key、出站日志、反代、模型协议。

### ai

模型目录与官方单价、出站 Key、组上游、读 usage 并计价、出站调用日志。MCP 后置。  
不拥有：验访问令牌、金额窗口、通用拷流、TTFT 计时（只收编排算好的毫秒）。  
内部可分子包 `chat` / `mcp`，暂不拆 Modulith 模块。

### orchestration

数据面 Controller 与应用服务：**只排序调用**。把 ai 的「去哪」转成 core 的 `Upstream`，打 TTFT，把出站结果交给 ai 记日志，把 ai 读出的用量交给 access。  
不拥有：表、Redis、WebClient 拷流实现、JSON-RPC 状态机。

---

## 5. 数据所有权

| 存储 | 所有者 |
|---|---|
| `user`、`user_access_token`、`user_access_token_model`、`usage_limit`、`usage_record`、限额 Redis | access |
| `llm_apikey_config`、`llm_model`、`llm_call_log` | ai |
| `gw_mcp_*` | ai |
| 无 | orchestration、core（core 可有代理超时 yml） |

表结构真相源：`docs/sql-gateway-user.md`。令牌授权模型名，不对 `llm_model` 建外键。出站 Key 挂在模型上。对外凭证是 `access_token`。

---

## 6. 以后再拆

- `ai` 里 Chat 与 MCP 互相污染时，拆 `llm` + `mcp`；orchestration 多依赖一个模块即可。
- `core` 要上注册中心 / 多种 LB 时再拆。
- 基础设施模块：WebClient/JPA/Redis 封装出现多处复制再抽。

---

## 7. 实现顺序

按依赖从底向上：被依赖的先做，编排最后接。三个业务域互不依赖，但 **orchestration 要调它们的 API**，所以不能先做 orchestration。

1. 四个 `package-info` + verify（`orchestration` 空壳）
2. **core**（`plan-gateway-core.md`）：应用能起、Health、链、单 URL 反代。后面所有流量都要过这里。
3. **access**：Security + 用户/令牌 + 额度/计数 API。不依赖 core/ai，但 orchestration 和本地联调需要「进门 + 额度」。
4. **ai**：先 Chat 协议 API（组上游、读 usage），再 MCP。仍无数据面 HTTP。
5. **orchestration**：接上 `POST /v1/chat/completions`，串通一次 Chat；再接 MCP。
6. 种子数据、README、补测试

Chat 通了再做 MCP，避免 orchestration 和 ai 同时摊开两套协议。core 的 Plan 不要实现 orchestration。
