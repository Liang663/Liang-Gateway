# Liang-Gateway 管理后台

Vue 3 + TypeScript + Vite 桌面端管理后台，沿用 `frontend-demo/` 的深色侧栏、浅色工作区和青绿色强调色。正式页面调用网关真实接口；原 HTML Demo 保留为视觉参考。

管理后台通过 `/console/` 访问，使用 Hash 路由。构建产物可以随网关 JAR 一起部署，运行时无需 Node 服务。界面面向 1280、1440、1920 像素桌面宽度，不提供手机端布局。

## 环境与本地开发

需要 Java 21、Maven、Node.js 22.12+ 或 Node.js 24，以及对应的 npm。后端依赖 MySQL 和 Redis；启动前在仓库根目录的 `config/application-local.yml` 配置连接信息与 `gateway.admin-token`，可参考同目录的 `.example` 文件。真实配置已被 Git 忽略，不要提交密钥或密码。

在仓库根目录启动网关：

```pwsh
mvn spring-boot:run
```

在另一个 PowerShell 7 终端启动前端：

```pwsh
Set-Location D:/java/ideaProjects/Liang-Gateway/frontend
npm ci
$env:GATEWAY_URL = 'http://127.0.0.1:8080'
npm run dev
```

访问 `http://127.0.0.1:5173/console/`，输入后端配置的管理密钥。

`GATEWAY_URL` 可省略，默认值为 `http://127.0.0.1:8080`。Vite 将 `/admin`、`/v1`、`/mcp` 和 `/health` 代理到该地址。修改代理目标后需要重新启动 Vite。前端开发服务器只绑定本机 `127.0.0.1`。

Windows 下再次执行 `npm ci` 或 Maven 的 `frontend` 构建配置前，请先停止正在运行的 Vite / 预览服务器，避免其占用 `node_modules` 中的可执行文件。

### MCP 浏览器来源配置

MCP 数据面会校验浏览器发送的 `Origin`。在本地配置文件已有的 `gateway` 节点下合并以下配置，然后重启网关：

```yaml
gateway:
  mcp:
    allowed-origins:
      - http://127.0.0.1:5173
      - http://127.0.0.1:8080
```

第一项用于 Vite 开发页面，第二项用于同机 JAR 部署页面。来源包含协议、主机和端口，不含 `/console/` 路径；`localhost` 与 `127.0.0.1` 属于不同来源。部署到其他域名时改为实际的精确来源，例如 `https://gateway.example.com`，无需通配符。默认空列表会拒绝带未授权 Origin 的 MCP 浏览器请求。即使前端使用开发代理，也需要配置页面的实际来源。

## 页面与使用流程

| 页面 | 能力 |
| --- | --- |
| 网关概览 | 最近 24 小时 / 7 天的上游调用、成功率、平均 TTFT、计费金额、每小时趋势、模型分布与最近调用 |
| 模型管理 | 新建、编辑、启停模型，关联上游密钥与输入 / 输出价格，跳转调试 |
| 上游密钥 | 管理供应商、Base URL、密钥、有效期与启停状态；列表只显示密钥前缀 |
| 用户与令牌 | 管理用户，配置令牌 QPM、模型白名单、周期额度和有效期，查看实时额度并重置指定周期 |
| MCP 管理 | 管理服务及 HTTP 工具映射，上传或粘贴 OpenAPI JSON、选择路径导入工具 |
| 日志与用量 | 上游调用日志与用量账单两个标签页；筛选、服务端分页、详情和当前页 CSV 导出 |
| 在线调试 | 使用访问令牌发起真实 Chat SSE 调用，发现并调用 MCP 工具，显示响应及错误 |

建议按“上游密钥 → 模型 → 用户 → 访问令牌 → 在线调试 → 日志与用量”完成首次验证。Chat 令牌需要配置相应的模型白名单。**空模型列表表示未授权任何 Chat 模型**，可用于只调用 MCP 的令牌。

模型价格单位为“分 / 百万 Token”；周期额度、账单金额在接口中使用整数分。表单写明输入单位，金额展示换算为人民币。周期额度留空表示不配置该周期，两项均留空表示不限周期金额。QPM 为每分钟请求上限，填写 `0` 会阻止通过 QPM 检查的调用。

### 凭据与调试

- 管理入口通过 `/admin/users` 校验密钥；管理请求使用 `X-Admin-Token`。管理密钥只保存在 JavaScript 内存中，刷新页面需要重新输入，退出会清除。
- 调试页单独输入用户访问令牌，调用数据面时使用 `Authorization: Bearer …`。管理密钥不会进入调试请求。
- 上游密钥编辑时将 API Key 留空可保留原值；新建时需要填写。令牌明文可在用户与令牌页面按需查看，不写入浏览器持久化存储。
- Chat 使用 Fetch 读取 SSE，支持跨网络分块的事件和停止接收。停止接收后的实际账单以服务端记录为准。
- MCP 调试使用网关代码支持的 **`2026-07-28` 无状态协议**：先执行 `server/discover` 和 `tools/list`，再执行 `tools/call`，并携带相应协议版本及方法请求头。
- 在线调试会调用真实上游；Chat 可能产生计费，MCP 工具可能执行真实业务操作。工具调用前会要求确认。请使用受控模型、测试令牌与测试工具验证。

### 概览统计口径

调用次数、成功率、趋势、模型分布和 TTFT 来自已记录的 `llm_call_log`；金额和 Token 用量来自 `usage_record`。调用日志的成功标志沿用后端记录语义，包含上游调用结果及可记账 usage 的判定。鉴权失败或在调用上游前被限额拦截的请求，不计入上游调用成功率。没有有效 TTFT 样本时页面显示“—”。最近调用列表默认查询最近 24 小时的最新 5 条记录。

## 查询 API

以下接口均要求 `X-Admin-Token`。配置管理继续复用 `/admin/llm/models`、`/admin/llm/apikeys`、`/admin/users`、用户下的 `/tokens` 和 `/admin/mcp/servers` 及其工具接口。

### 统计

`GET /admin/llm/stats?range=24h`

- `range` 可选 `24h`、`7d`，默认 `24h`。
- 返回 `{ total, successCount, successRate, averageFirstTokenMs, trend, models }`。
- `successRate` 为 0～1 的比值；无记录时为 0，页面显示“—”。`averageFirstTokenMs` 可为 `null`。
- `trend` 为 `{ time, count }[]`，按上海时间小时分组并补齐空桶；`models` 为 `{ model, count }[]`。

`GET /admin/usage/stats?range=24h&userCode=…&tokenCode=…&model=…`

- `range` 规则同上，其他筛选字段可省略。
- 返回 `{ totalRecords, promptTokens, completionTokens, totalTokens, amountFen }`。
- 空查询结果的统计字段均为 0。

### 分页明细

`GET /admin/llm/call-logs`

- 可选参数：`page`、`pageSize`、`from`、`to`、`model`、`apikeyCode`、`success`。
- `success` 为 `true` / `false`，省略表示全部状态。
- 记录字段：`code`、`llmApikeyCode`、`model`、`success`、`message`、`firstTokenMs`、`totalDurationMs`、`createTime`。

`GET /admin/usage/records`

- 可选参数：`page`、`pageSize`、`from`、`to`、`userCode`、`tokenCode`、`model`。
- 记录字段：`code`、`userCode`、`tokenCode`、`promptTokens`、`completionTokens`、`totalTokens`、`amountFen`、`model`、`requestId`、`createTime`。

两类明细统一返回 `{ items, total, page, pageSize }`。页码从 1 开始，默认每页 20 条，上限 100 条；超过 100 会按 100 执行，非正页码或页大小返回 400。数据由数据库筛选、计数和分页，按时间与内部 ID 倒序稳定排序；页面超出范围时返回空 `items`，保留实际 `total`。

时间区间使用左闭右开 `[from,to)`。`from/to` 接收带偏移量的 ISO 时间，例如 `2026-09-18T12:00:00+08:00`；URL 中的加号需编码为 `%2B`，前端通过 `URLSearchParams` 处理。省略 `to` 时取服务端当前时间，省略 `from` 时取 `to` 前 24 小时；起点大于或等于终点时返回 400。响应中的日志时间、账单时间与趋势时间为上海本地时间，前端筛选器同样按上海时间输入。

### 令牌实时额度

`GET /admin/users/{userCode}/tokens/{tokenCode}/quota`

- 先校验用户与令牌归属，匹配后读取真实周期额度。
- 返回 `{ fiveHour, week }`；每个窗口含 `used`、`limit`、`windowStart`、`windowEnd`。
- `used/limit` 单位为分，窗口时间为 ISO Instant；未配置的周期时间为 `null`。页面结合令牌限额配置区分“未设置”和金额为零的额度。
- 重置复用 `POST /admin/users/{userCode}/tokens/{tokenCode}/quota/reset`，请求体为 `{ "layer": "FIVE_HOUR" }` 或 `{ "layer": "WEEK" }`，成功后重新读取额度。

## 构建与 JAR 部署

仅检查及构建前端，在 `frontend/` 目录执行：

```pwsh
npm ci
npm run build
```

`build` 先运行 `vue-tsc --noEmit`，再生成 `frontend/dist/`。正式部署从仓库根目录执行：

```pwsh
mvn -Pfrontend package
java -jar target/Liang-Gateway-1.0-SNAPSHOT.jar
```

`frontend` Maven 配置依次运行 `npm ci`、`npm run build`，将静态资源打包进 JAR 的 `static/console/`。访问 `http://127.0.0.1:8080/console/`。部署运行仍需后端数据库、Redis 和本地配置；管理 API 继续鉴权，仅控制台静态资源允许匿名加载。

主配置为 Flyway 单独指定 JDBC URL、用户名与密码，默认引用 `spring.datasource.*`，只在启动阶段执行迁移；运行时查询继续使用 R2DBC。新增 `V4__admin_query_indexes.sql` 为管理统计与分页补充索引。部署前备份数据库并检查迁移记录，迁移过程中可能产生建索引负载。

普通 `mvn test` 不依赖 Node。若本机暂时没有测试数据库或 Redis，只想生成可供其他环境验证的包，可使用 `mvn -Pfrontend -DskipTests package`；该命令跳过 Java 测试，不构成集成验证通过的证据。

## 测试

在 `frontend/` 目录运行：

```pwsh
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

Vitest 覆盖请求封装、SSE 分块解析和工具函数。Playwright 使用模拟管理接口及受控响应，检查桌面端配置、额度、日志、Chat 与 MCP 调试流程，不默认消耗真实模型额度；它会按配置启动 Vite，默认视口为 1440 × 1000，测试包含其他桌面宽度。

已经安装 Chrome 的机器可以选择使用系统 Chrome，无需安装 Playwright Chromium：

```pwsh
$env:PLAYWRIGHT_CHANNEL = 'chrome'
npm run test:e2e
Remove-Item Env:PLAYWRIGHT_CHANNEL
```

从仓库根目录执行后端测试：

```pwsh
mvn test
```

后端集成测试使用 `src/test/resources/application.yml` 配置的本地 `liang_gateway_test` MySQL 数据库（默认端口 3307）和 Redis（默认端口 6379），不使用一次性 Testcontainers。测试数据仅应写入测试数据库，不要将测试连接指向生产数据库。

如果现有 MySQL 容器映射为 13307，可只为当前 PowerShell 测试进程覆盖连接地址，不修改本地配置或测试配置：

```pwsh
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:13307/liang_gateway_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:SPRING_R2DBC_URL = 'r2dbc:mysql://127.0.0.1:13307/liang_gateway_test?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true'
mvn test
Remove-Item Env:SPRING_DATASOURCE_URL, Env:SPRING_R2DBC_URL
```

Windows 的 `mvn.cmd` 会再次经过命令行解析。包含 `&` 的连接串用上面的环境变量传递，避免直接拼接成 Maven 命令参数。

新增查询测试包含数据库聚合与分页、时间边界、空结果、非法参数、管理员鉴权、令牌归属与真实窗口读取；纯服务单测可以在外部依赖暂时不可用时独立检查参数与应用层逻辑。完整验收仍需运行真实 MySQL / Redis 集成测试，并使用受控上游完成端到端验证。
