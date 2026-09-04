# Liang-Gateway

Java AI 网关：OpenAI 兼容 Chat 转发、HTTP→MCP（2026-07-28）、调用方鉴权与金额限额。不审内容。运行时仅 Spring WebFlux。

主要使用者：带访问令牌的调用方，以及把已有 HTTP API 暴露成 MCP 工具的 Agent。

## 快速开始

### 环境要求

- Java 21、Maven、Docker
- MySQL 8 与 Redis：`docs/dev-ops/docker-compose.yml` 官方镜像（数据卷持久化）
- 本机 MySQL 可选，不参与 `mvn test`

### 最小启动

```bash
docker compose -f docs/dev-ops/docker-compose.yml up -d
copy config\application-local.yml.example config\application-local.yml
```

在 `config/application-local.yml` 填入数据源。连 compose 里的 MySQL 时用 `127.0.0.1:3307`、用户 `root`、密码 `liang`、库 `liang_gateway`。不要提交该文件。

```bash
mvn spring-boot:run
```

### 最小验证

先起 compose，再测。测试写进 **`liang_gateway_test`（端口 3307）**，测完数据还在，不会进本机 3306。

```bash
mvn test
docker exec liang-gateway-mysql mysql -uroot -pliang -e "USE liang_gateway_test; SHOW TABLES; SELECT COUNT(*) FROM user;"
curl http://127.0.0.1:8080/health
```

数据面（需合法访问令牌）：`POST /v1/chat/completions`、`GET /v1/models`、`POST /mcp/{path}`。

## 模块概览

- `core`：过滤器链、单 URL 反代、Health
- `access`：进门鉴权、金额窗口、模型授权、记账
- `ai`：Chat 协议与 MCP 协议转换（子包并列）
- `orchestration`：数据面入口，只排序调用上面三者

## 文档入口

- 协作约定与文档路由：[AGENTS.md](./AGENTS.md)
- 第一版做 / 不做：[docs/v1-plan.md](./docs/v1-plan.md)
- 领域切分：[docs/references/domains.md](./docs/references/domains.md)
