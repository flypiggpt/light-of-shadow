# 下一阶段计划（技术栈 + 功能）

原则：作品集质量优先；微服务只拆 Agent；一次做扎实一件。

---

## 1. 目标架构

```
Android ──► Java API 服务（Spring Boot）──► MySQL
                 │                          Redis
                 ▼
           Python Agent 服务（LangChain）
```

| 服务 | 技术栈 | 职责 |
|---|---|---|
| `light-of-shadow-backend` | Spring Boot 3 · Java 21 · MyBatis · JWT · Redis | 登录注册、商品、购物车、订单、商家、图片 |
| `agent-service`（新增） | Python 3.11+ · FastAPI · LangChain/LangGraph · Redis | 导购对话、意图识别、工具调用、推荐、对比 |
| 数据 | MySQL 8 · Redis 7 | 业务数据 · 会话/缓存/限流 |
| 部署 | 现有云服务器 · rsync/deploy 脚本 · （可选 Docker） | |

客户端仍只连 Java 的 `/api`；导购接口由 Java 转发到 Agent，或走网关，**token 只在 Java 校验一处**。

---

## 2. 技术栈 → 功能对照

### Java API 服务

| 技术 | 功能 |
|---|---|
| Spring Boot 3 + MyBatis | 现有 25 接口：auth / shop / merchant |
| JWT | 登录态；补 **refresh 轮换**、**logout**、统一 `AuthFilter` |
| **Redis** | ① refreshToken / 登出黑名单 ② 商品列表缓存 ③ 登录&导购限流 ④ 转发用的短期会话 |
| OpenAPI/Feign 或 WebClient | 调用 `agent-service` |

### Python Agent 服务

| 技术 | 功能 |
|---|---|
| FastAPI | `/api/shoppingguide/*`（接口名与现状兼容） |
| LangChain / LangGraph | Agent 编排、多轮对话、意图路由 |
| Tools（Function Calling） | `search_products` / `get_product` / `compare` / `add_to_cart` / `get_cart` —— 内部调 Java shop API |
| Redis Chat Memory | 会话窗口持久化（替代内存 Map） |
| DeepSeek | 模型（现有 key）；超时降级话术 |

### Android（对照，不阻塞后端）

| 技术 | 功能 |
|---|---|
| DataStore | Token 持久化 |
| 既有 ApiClient | URL 可配置；401 自动 refresh |
| TTS | 导盲语音播报（已有 `TtsAlertManager` 待接入） |

---

## 3. 服务边界

| 能力 | 归属 | 说明 |
|---|---|---|
| 账号 / JWT 签发与校验 | Java | Agent 不碰密码 |
| 商品 / 购物车 / 订单 | Java | Agent 只通过内部 API 读/写 |
| 对话 / 推荐 / 对比 / 意图 | Python | 唯一允许调 LLM 的服务 |
| 会话存储 | Redis | Agent 写，Java 可读（调试） |

**内部约定**：Python → Java 用 HTTP + 服务间 token（或内网直连 + JWT 验签同一 `JWT_SECRET`）。

---

## 4. 里程碑

| 阶段 | 内容 | 产出 |
|---|---|---|
| **P0 基线** | minSdk→26、API URL 可配置、文档/SQL 对齐 | 新人 clone 可跑 |
| **P1 Redis** | 会话/限流/缓存/refresh 轮换 + logout | 重启不丢会话；登出即时失效 |
| **P2 Agent** | Python 服务 + LangChain Tools + Redis Memory；Java 转发导购 | 「帮我找 200 内耳机」→ Agent 真调搜索 |
| **P3 客户端** | Token 持久化、401 重试、TTS 接入 GuideScreen | 杀进程免登录；导盲能说话 |
| **P4 治理** | 拆 ShopScreen、AuthFilter 统一、MockMvc 测试、deploy 加固 | 结构可展示、测试可跑 |

不做：支付/物流、拆 auth/shop 为独立微服务、重写 UI、自建推理。

---

## 5. 目录规划

```
light-of-shadow/
├── docs/plan.md                 ← 本文
├── light-of-shadow-backend/     # Java API（现有）
└── agent-service/               # Python Agent（P2 新建）
    ├── app/
    │   ├── main.py              # FastAPI
    │   ├── agent/               # LangChain tools / memory / prompts
    │   └── clients/             # 调 Java shop API
    ├── pyproject.toml
    └── Dockerfile
```

---

## 6. 接口契约（导购对外不变）

| 端点 | 现状 | 目标 |
|---|---|---|
| `POST /api/shoppingguide/sessions` | Java 内存 | Java → 转发 Agent |
| `POST .../messages` | Java 调 DeepSeek | Agent + Tools |
| `GET .../recommendations` | Java | Agent |
| `POST /api/shoppingguide/compare` | Java | Agent Tools |

`docs/api/shoppingguide_api.md` 保持对外契约；实现方改为 Agent 服务。

---

## 7. 验收

| 阶段 | 怎么验 |
|---|---|
| P1 | 杀 Java 进程重启，导购历史仍在；登出后旧 token 立刻 401 |
| P2 | 真机发「预算 200 的耳机」→ 返回库里真实商品；能继续「加入购物车」 |
| P3 | 杀 App 重开免登录；导盲页面语音播报障碍名 |
| P4 | `mvn test` 绿；商城四 Tab 回归无异常 |
