# Liang-Gateway

Liang-Gateway 是一个面向 AI 应用的统一网关，提供 OpenAI 兼容的对话转发、HTTP API 到 MCP 工具的转换、调用方鉴权、模型访问控制、额度管理与用量记录。

它可以统一接入和管理模型服务，也可以将已有 HTTP API 快速提供给 Agent 使用。

## 快速开始

### 环境要求

- Java 21、Maven、Docker
- MySQL 8、Redis

### 最小启动

```bash
docker compose -f docs/dev-ops/docker-compose.yml up -d
cp config/application-local.yml.example config/application-local.yml
```

根据实际环境修改 `config/application-local.yml`，配置数据库、Redis、管理令牌和模型服务凭据。该文件包含本地敏感信息，请勿提交。

```bash
mvn spring-boot:run
```

### 最小验证

```bash
mvn test
curl http://127.0.0.1:8080/health
```

数据面（需合法访问令牌）：`POST /v1/chat/completions`、`GET /v1/models`、`POST /mcp/{path}`。

本地联用可在 Flyway 建表后执行 [docs/dev-ops/seed-local.sql](./docs/dev-ops/seed-local.sql)（只打 `liang_gateway`）。演示 MCP：`POST /mcp/demo`，令牌 `lgw_demo_mcp_token`。

## 模块概览

- `core`：过滤器链、单 URL 反代、Health
- `access`：进门鉴权、金额窗口、模型授权、记账
- `ai`：Chat 协议与 MCP 协议转换（子包并列）
- `orchestration`：数据面流程：组合公开 API，Chat 收口，MCP 只做 HTTP 适配

## 文档入口

- 协作约定与文档路由：[AGENTS.md](./AGENTS.md)
- 第一版做 / 不做：[docs/v1-plan.md](./docs/v1-plan.md)
- 领域切分：[docs/references/domains.md](./docs/references/domains.md)

## License

Copyright 2026 Liang663.

Licensed under the [Apache License 2.0](./LICENSE).
