# Agent 服务规格（生产级）

## 定位

Python 服务，负责导购智能。Java 单体负责账号/商品/订单，两者 HTTP 契约解耦。

```
Android ──► Java ──► MySQL
              │
              ▼
         Python Agent ──► Redis（checkpoint）
                       ──► 向量库（RAG）
                       ──► Langfuse（观测）
```

## 技术栈

| 组件 | 选型 |
|---|---|
| Web | FastAPI + uvicorn |
| Agent 编排 | LangGraph |
| LLM | DeepSeek（OpenAI 兼容） |
| 记忆/checkpoint | Redis |
| RAG | 向量检索 + 关键词混合（RRF）+ rerank |
| 观测 | Langfuse + Prometheus + structlog |
| 测试 | pytest + golden set 回归 |
| 部署 | Docker Compose + healthcheck |

## 目录结构

```
agent-service/
├── app/
│   ├── main.py              # FastAPI 入口、lifespan、路由注册
│   ├── config.py            # 分环境配置（pydantic-settings）
│   ├── logging.py           # structlog 结构化日志
│   ├── api/                 # HTTP 层：/api/shoppingguide/*
│   ├── agent/               # LangGraph 状态机、节点、工具
│   ├── llm/                 # DeepSeek 客户端（超时/重试/降级）
│   ├── tools/               # 工具：search_products/get_product/add_cart/compare/faq
│   ├── rag/                 # 检索管线：分块/embedding/混合检索/rerank
│   ├── memory/              # Redis checkpoint、会话窗口
│   ├── obs/                 # Langfuse、Prometheus 指标
│   └── clients/             # 调 Java 内部 API
├── tests/
│   ├── unit/
│   ├── integration/
│   └── golden/              # 评测样例
├── scripts/
├── Dockerfile
├── docker-compose.yml
└── pyproject.toml
```

## 生产级要求（不妥协）

### 1. 容错
- LLM 调用：超时（默认 30s）、指数退避重试（≤2 次）、失败降级话术
- 工具调用：参数校验（pydantic）、单工具超时、失败重试一次、熔断（连续 N 次失败暂停）
- 服务：全局异常捕获、请求 ID 贯穿、优雅关闭

### 2. 可观测
- structlog JSON 日志（含 request_id / session_id / latency）
- Prometheus：QPS、延迟 P50/P95/P99、LLM token 用量、工具成功率
- Langfuse trace：每次模型调用、工具输入输出、token 成本
- /health（存活）/ /ready（就绪）

### 3. 安全
- 服务间调用带内部 token
- 限流（Redis 令牌桶）
- 输入校验、长度限制
- Prompt 注入防护：工具参数白名单、输出过滤

### 4. 测试
- 工具单测（mock Java API）
- 集成测试（testcontainers 或本地 Redis）
- golden set：30–50 条，测工具调用成功率/检索命中率/回答正确率

## 接口契约（与 Java 对齐）

`POST /api/shoppingguide/sessions` 等，请求/响应格式与 `docs/api/shoppingguide_api.md` 一致。

## 简化项（如实声明）

- 单机部署，不做多活/灰度
- 不做模型微调
- rerank 可用轻量交叉编码器或 LLM 打分
