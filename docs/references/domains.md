# 第一版领域划分

被 [v1-plan.md](../v1-plan.md) 引用。写任何代码前先读本文。包名、依赖、表归谁以本文为准。

**现行切法：** 三个业务域 + 一个数据面流程域。业务域互不依赖；数据面依赖全部业务域。星形是破环手段，**禁止再增加第二个编排模块**。

```
core     网关转发：过滤器链、单 URL 反代、Health
access   调用方：鉴权、金额窗口、模型授权、记账（Security 只解决进不进得来）
ai       Chat 协议（组上游、usage、计价）；MCP 协议（JSON-RPC、工具映射）；子包并列，不共享限额
orchestration    数据面流程：调用顺序、反腐翻译、Chat 收口（TTFT/drain/记账）、数据面 HTTP
```

根包 `com.liang.gateway`。没有独立 `llm` / `mcp` / `metering` / `admin` / `infrastructure`。不要为改名而改 `orchestration` 包名。

---

## 1. 为什么要有 orchestration

Security 只能回答「这个 Key 能不能进网关」。额度够不够、用了多少 Token，仍是 **access 的业务**，必须有人在调用过程中显式去问 access。

若把这段顺序写进 core，core 就要依赖 access 和 ai；而 ai 还要用 core 的反代 → 成环。

若写进 ai，ai 就要知道配额，调用方和协议缠在一起。

所以单独放 **orchestration**：数据面流程域，不是技术胶水，也不是全局 application。它组合三个业务域的公开 API，并拥有 Chat 路径自己的生命周期（TTFT、客户端断开后 drain、缺 usage 不得记 0）。

MCP JSON-RPC 语义不在本域，在 `ai` 的 `McpApi`。

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

三个业务域 **禁止** 互相 import。以后若 access 需要 ai 的展示名、或探活需要上游，优先单向依赖或事件，禁止再开第二个编排域。

orchestration **禁止** 实现配额算法、反代拷流、MCP JSON-RPC、工具 args 映射、DeepSeek usage 解析与单价。

---

## 3. 一次 Chat 怎么走（领域粒度）

1. **access（Security）**：凭证无效则到不了后面。只解决进门。
2. **orchestration**：数据面入口。读请求里的 `model` 与 body。
3. **access**：该令牌是否授权此模型；金额窗口是否够。不够 429，未授权 403，Redis 挂 503。
4. **ai**：按模型组出站 URL/头/body（透传），不管访问令牌。
5. **core**：反代（可 SSE）。编排在发出时打点，第一帧算 TTFT。Chat 为拿到 usage，客户端断开后仍 drain 上游直到 usage 或结束（与通用反代「断开即取消」不同）。
6. **ai**：读 usage，按目录官方单价算分。流式强制 `include_usage=true`。编排把本笔成败与耗时交给 ai 写入出站日志。
7. **access**：按真实 usage 记 Token 与金额（一次加上本笔分，并把 Redis 新已用回写限额行）。

MCP **不是** Chat 的简化版。QPM / 金额窗只服务模型调用。MCP 链：

1. **access（Security）**：要进数据面仍须合法访问令牌。不做 `checkQuota`、不打 QPM、不记金额。
2. **orchestration**：`POST /mcp/{path}`。Origin / Accept / Content-Type 在本域；协议头抽成字符串后交给 `McpApi.handle`。
3. **ai（mcp 子包）**：JSON-RPC 信封、method 分发、版本、头与 body 一致性；discover/list 当场给出信封；call 要么 `Completed`，要么 `NeedsOutbound`。
4. **core**：仅 `NeedsOutbound` 时 `ProxyApi.exchange`（捕获，不写入入站）。
5. **ai**：`completeCall` 把上游状态/正文/超时收成 JSON-RPC result（工具层 isError 文本在本域）。

MCP 与 Chat 各走各的数据面服务，互不调用。

**管理接口不是数据面：** 用户与令牌管理在 access，`/admin` 下模型与 MCP 配置仍在 ai。Health 在 core。

---

## 4. 各块拥有什么

### core

链、单 URL 反代、Health、502/500。第一版路由对业务来说就是 orchestration 的 Controller 映射；core 不造注册中心和 LB。生产 Chat/MCP 走显式 `ProxyApi`，不要把业务挂回过滤器链。  
不拥有：额度、Chat/MCP 语义。  
落地：`spec-gateway-core.md`。access：`spec-gateway-access.md`。

### access

`user` / `user_access_token` / `user_access_token_model` / `usage_limit` / `usage_record`、金额窗口 Redis、模型名授权、记账 API、Security。  
不拥有：单价、出站 Key、出站日志、反代、模型协议。

### ai

内部两个并列子包，暂不拆 Modulith 模块：`chat`（模型目录、出站 Key、组上游、usage、计价、出站日志）；`mcp`（服务器与工具目录、2026 JSON-RPC **协议入口**、组工具 HTTP、包装失败原因）。  
不拥有：验访问令牌、金额窗口、QPM、通用拷流、TTFT 计时。`chat` 与 `mcp` 互不 import。不得返回 `core.Upstream`。

### orchestration

数据面流程域。拥有：调用顺序、`ChatUpstream`/`McpUpstream` → `Upstream`、Chat 的 TTFT/drain/记账收口、数据面 HTTP。  
不拥有：表、Redis、WebClient 拷流实现、MCP JSON-RPC 状态机、工具参数映射、单价。

---

## 5. 数据所有权

| 存储 | 所有者 |
|---|---|
| `user`、`user_access_token`、`user_access_token_model`、`usage_limit`、`usage_record`、限额 Redis | access |
| `llm_apikey_config`、`llm_model`、`llm_call_log` | ai |
| `mcp_server`、`mcp_tool` | ai |
| 无 | orchestration、core（core 可有代理超时 yml） |

表结构真相源：`docs/sql-gateway-user.md`、`docs/sql-gateway-mcp.md`。令牌授权模型名，不对 `llm_model` 建外键。出站 Key 挂在模型上。对外凭证是 `access_token`。MCP 路径亮 `mcp_server.path`。

---

## 6. 以后再拆

- `ai` 里 `chat` 与 `mcp` 互相污染时，拆成两个 Modulith 模块；orchestration 多依赖一个即可。不要为此先拆。
- `core` 要上注册中心 / 多种 LB 时再拆。
- 基础设施模块：WebClient/JPA/Redis 封装出现多处复制再抽。

---

## 7. 实现顺序

按依赖从底向上：被依赖的先做，数据面最后接。三个业务域互不依赖，但 **orchestration 要调它们的 API**。

数据面已接上。进行中：MCP JSON-RPC 从编排下沉到 `McpApi`（见 `docs/plan-gateway-mcp-protocol.md`）。剩余产品项：种子数据与补测试。
