# 花木商城：电商多智能体客服平台

这是根据四份需求资料与八张流程图实现的完整示例，包含 PC 电商前端、Spring Boot 后端、Spring AI Alibaba Graph 主流程、售前/售中/售后/投诉四个业务 Agent、全链路风控、混合 RAG、会话记忆、检查点、业务任务确认与 Best-of-3 Judge。

## 一键启动

要求：Node.js 22+、JDK 21+、阿里云百炼 DashScope API Key。项目自带 Maven Wrapper，不需要额外安装 Maven。本版本不会回退到固定模板，未配置模型密钥时后端会明确拒绝启动。

### 1. 配置模型密钥

1. 在[阿里云百炼控制台](https://help.aliyun.com/zh/model-studio/get-api-key)创建 API Key。
2. 将根目录 `.env.example` 复制为 `.env`。
3. 只修改 `.env` 中这一行：

```dotenv
AI_DASHSCOPE_API_KEY=your-dashscope-key
```

`.env` 已加入 `.gitignore`，禁止把真实密钥写入 `application.yml`、Java/TypeScript 源码或提交到 Git。

### 2. 启动项目

```bash
# 终端 1：后端（Windows，会自动读取根目录 .env）
.\start-backend.ps1

# macOS / Linux：
# set -a && source .env && set +a
# cd server && ./mvnw spring-boot:run

# 终端 2：前端
pnpm install
pnpm dev
```

访问 `http://localhost:3000`。后端健康检查：`http://localhost:8080/api/v1/health`。

也可以在安装 Docker Desktop 后，于项目根目录执行：

```bash
docker compose up --build
```

## 演示身份与接口

- `tenantId / X-Tenant-Id`: `hanaki-demo`
- `userId / X-User-Id`: `user-1001`
- 商品：`GET /api/v1/products`
- 订单：`GET /api/v1/orders`
- 客服：`POST /api/v1/chat`
- 确认写操作：`POST /api/v1/tasks/confirm`
- 运营指标：`GET /api/v1/admin/metrics`
- Prometheus：`GET /actuator/prometheus`

## 关键实现决策

- 主流程与五个业务 SubGraph 使用官方 `spring-ai-alibaba-graph-core 1.1.2.2`；固定条件边和枚举白名单阻止模型创造节点。
- `spring-ai-alibaba-starter-dashscope` 自动装配真实 `ChatModel` 与 `EmbeddingModel`。
- 意图路由、Query Rewrite、售前/售中/售后/投诉 Agent、三候选生成及 Judge 均通过 Spring AI 调用模型，不包含固定客服答案模板。
- H2 文件数据库默认持久化到 `server/data/`；生产可把 `DATABASE_URL` 切换为 PostgreSQL。
- RAG 使用 BM25 + DashScope Embedding 真实语义向量 + RRF；文档向量按版本缓存，接口仍可替换为 Elasticsearch。
- Spring AI Tool Calling 只暴露绑定可信租户和用户的只读工具；模型参数中不存在 tenantId/userId。
- 退款等写操作只生成 15 分钟签名确认令牌，用户确认后才以乐观状态迁移提交；重复确认不会重复产生副作用。

## 模型参数

以下参数可以在 `.env` 中调整，不修改代码即可生效：

- `AI_CHAT_MODEL`：默认 `qwen-plus`
- `AI_EMBEDDING_MODEL`：默认 `text-embedding-v3`
- `AI_CHAT_TEMPERATURE`：默认 `0.2`
- `AI_CHAT_MAX_TOKENS`：默认 `1200`
- `AI_EMBEDDING_DIMENSIONS`：默认 `512`

配置完成后，不需要把密钥值发给任何人。使用 `POST /api/v1/chat` 发起一次请求即可同时验证 ChatModel、EmbeddingModel、工具调用和 Graph。

## 目录

- `app/`：完整 PC 商城、订单中心、智能客服与运营台。
- `server/`：电商 API、多 Agent Graph、RAG、风控、工具网关、数据与测试。
- `public/og.png`：与成品视觉一致的社交分享封面。
- `docker-compose.yml`：前后端容器化启动。

生产上线前请替换 `CONFIRM_SECRET`、接入正式认证、把 H2 切换为生产数据库，并按租户配置真实商品/订单/知识服务。
