# Liang-Gateway 可视化分析

分析对象：`D:\java\ideaProjects\Liang-Gateway`，源码提交 `24d7fe23f2db89b074d02c442ff4900fe53ab3e5`。分析日期：2026-09-08。

本次以 Java 源码、Maven 配置、SQL migration 和测试源码为依据，未读取项目已有的 docs 文档。五张图分别回答结构、流程、调用顺序、数据来源去向和运行状态五类问题；每张图都有独立 Typed JSON 与独立交互式 HTML。

## 图册入口

| 类型 | 独立交互式 HTML | Typed JSON | 阅读重点 |
| --- | --- | --- | --- |
| Architecture 架构图 | [模块与运行边界](01-system.architecture.html) | [源文件](01-system.architecture.json) | 四个模块、身份认证、代理、Redis 与 MySQL 的职责 |
| Workflow 工作流图 | [Chat 准入、出站与结算](02-chat.workflow.html) | [源文件](02-chat.workflow.json) | 模型授权、周期限额、QPM、上游转发与失败分支 |
| Sequence 时序图 | [MCP 工具调用](03-mcp.sequence.html) | [源文件](03-mcp.sequence.json) | tools/call、参数映射、HTTP 出站及 JSON-RPC 结果封装 |
| Dataflow 数据流图 | [配置与用量数据流](04-assets.dataflow.html) | [源文件](04-assets.dataflow.json) | OpenAPI 到工具资产，以及模型 usage 到费用与记录 |
| Lifecycle 生命周期图 | [SSE 连接、断连与结算](05-sse.lifecycle.html) | [源文件](05-sse.lifecycle.json) | 转发、断连 drain、用量缺失和结算失败 |

使用浏览器直接打开 HTML 即可，无需启动网关。页面自带浅色/深色、平移缩放、节点搜索与聚焦、关系探索、演示和导出入口。各张图均为独立文件，未拼接成混合大图。

## 项目功能与结构结论

Liang-Gateway 是一个面向模型调用与工具接入的 Java 网关。数据面提供 Chat Completions、模型列表与 MCP 接口；管理面维护用户、调用令牌、模型及上游凭据、MCP 服务与工具；用量接口提供额度、调用记录和统计查询。项目采用单 Maven 模块、Spring WebFlux/Reactor、Spring Modulith 领域边界、JPA/MySQL 和响应式 Redis。

`core` 承担通用 HTTP 出站、超时和 SSE 组帧。`access` 承担身份认证、模型权限、额度和用量。`ai` 承担模型适配、用量定价、调用日志以及 MCP 协议和工具映射。`orchestration` 在数据面组合这三个领域。领域管理 Controller 直接调用各自应用服务，因此架构图的数据面箭头没有表达成所有请求都必须经过 orchestration。两组 MySQL 节点表达领域表的逻辑归属，不表示两个独立数据库部署。

主要结构证据：[领域边界](../../src/main/java/com/liang/gateway/orchestration/package-info.java)、[认证配置](../../src/main/java/com/liang/gateway/access/internal/security/AccessSecurityConfig.java)、[代理实现](../../src/main/java/com/liang/gateway/core/internal/infrastructure/WebClientProxy.java)。

## Chat 与限额

Chat 按解析模型、校验授权、检查额度、准备上游、出站转发、解析用量、定价记账的顺序组织。QPM 使用按分钟划分的 Redis key，并设置过期时间。5 小时与 1 周窗口按照窗口起点惰性刷新；Lua 在 Redis 内执行窗口刷新和累加，避免这些步骤被其他命令穿插。周期金额检查发生在出站前，费用累加发生在获得真实 usage 后。两次操作之间没有预算预占，Redis 与 MySQL 写入也没有被表达为一个跨存储事务。

周期金额判断使用 `used > limit`；QPM 计数递增后比较阈值。模型权限失败返回 403，限额失败返回 429，Redis 不可用走 503。各类拒绝分支在工作流中单独标出。完整机制应结合 [DefaultAccessApi](../../src/main/java/com/liang/gateway/access/internal/application/DefaultAccessApi.java)、[RedisQuotaWindowStore](../../src/main/java/com/liang/gateway/access/internal/infrastructure/redis/RedisQuotaWindowStore.java)、[QuotaKeys](../../src/main/java/com/liang/gateway/access/internal/infrastructure/redis/QuotaKeys.java) 阅读。

## 响应式 SSE 与记账

WebClient 承接上游 I/O，Reactor 组合异步处理，JPA 调用通过 boundedElastic 隔离。SSE 按事件边界组帧后，一条分支写入客户端，另一条分支继续消费上游。客户端断连时保留 drain 与后续结算链，减少因取消而遗漏最终 usage 的情况。首个非空内容用于记录 TTFT，用量采用最后收到的有效 usage；缺少用量不会伪造费用。生命周期图中的状态名称是对代码行为的分析抽象，项目未声明使用独立状态机框架。

主要证据：[ChatCompletionService](../../src/main/java/com/liang/gateway/orchestration/internal/application/ChatCompletionService.java)、[DefaultChatApi](../../src/main/java/com/liang/gateway/ai/internal/application/DefaultChatApi.java)、[SseEventFramer](../../src/main/java/com/liang/gateway/core/internal/infrastructure/SseEventFramer.java)。图中没有声明实测并发连接数、吞吐量或延迟收益。

## OpenAPI 与 MCP

OpenAPI 导入按选中接口读取 HTTP 方法、路径、参数和 JSON 请求体，支持本地 `$ref`，生成工具草稿后保存。服务发布时根据工具定义生成 `name` 与 `inputSchema`，供客户端发现与选择。调用时校验参数并映射到 path/query/header/body，交给 core 统一 HTTP 出站，再封装为 MCP 工具结果。工具执行失败与 JSON-RPC 协议错误分开表达。

MCP 入口使用请求级协议处理，代码固定协议版本 `2026-07-28`，处理版本、方法、工具名等信息；`server/discover` 与 `tools/list` 无需业务 HTTP 出站。图中未声称已通过完整协议一致性认证，也没有把 Chat 的配额记账路径套用到 MCP。源码导入器支持的 HTTP 方法为 GET、POST、PUT、DELETE，不能从图示推导出对 OpenAPI 全部能力的支持。

主要证据：[OpenApiToolImporter](../../src/main/java/com/liang/gateway/ai/internal/mcp/domain/OpenApiToolImporter.java)、[McpToolAdminService](../../src/main/java/com/liang/gateway/ai/internal/mcp/application/McpToolAdminService.java)、[DefaultMcpApi](../../src/main/java/com/liang/gateway/ai/internal/mcp/application/DefaultMcpApi.java)、[McpGatewayService](../../src/main/java/com/liang/gateway/orchestration/internal/application/McpGatewayService.java)。

## 验证与产物说明

五张图均通过 Archify Showcase 的 9/9 项交付检查，构图结果为 0 错误、0 警告。浏览器检查覆盖 1440×900、1600×1000、1920×1080、2048×1320，并保存两端尺寸的浅色/深色截图。每张图的 `.deliver.json` 包含源文件及 HTML 的 SHA-256 和字节数；`.visual-check.json` 记录自动浏览器证据；`.visual-check.html` 是截图对照页。独立的视觉复核结论与文件绑定汇总见 [handoff.json](handoff.json)。自动浏览器回执保留 `visualReview: pending`，图像复核结果单独记录，两个结论不混用。

本次验证针对图源、生成产物和浏览器显示；没有运行 Maven 测试、压力测试或生产环境验证。业务代码未修改。新增文件均位于本目录。

需要修改图时，编辑对应 Typed JSON，然后使用 Archify 依次执行 `validate`、`deliver`，交付成功后再执行 `visual-check`。`run-check.mjs` 是本机批量检查辅助脚本，依赖其中配置的 Archify 安装路径；换机时请调整该路径。
