# Agent 服务：做了什么、按功能、如何实现

> 生产级 Python Agent 服务，负责「音影寻光」导购智能。  
> 与 Java 业务单体（账号/商品/订单）HTTP 契约解耦。

---

## 一、做了什么

新建 `agent-service/` Python 服务，把导购从「一问一答调 LLM」升级为**可推理、可调工具、可恢复、可观测**的 Agent 系统。

```
Android ──► Java（账号/商品/订单）──► MySQL
              │ HTTP（鉴权 token 透传）
              ▼
         Python Agent（本服务）
         FastAPI + LangGraph + RAG + Langfuse
              │
              └──► Redis（checkpoint）
```

| 维度 | 能力 |
|---|---|
| 对话 | LangGraph 状态机：意图识别→工具路由→多轮追问→降级 |
| 工具 | 搜商品 / 查详情 / 加购 / 对比 / FAQ，带校验与熔断 |
| 记忆 | Redis checkpoint 断点恢复；用户画像（预算/偏好/购买历史）跨会话记忆，减少重复追问 |
| 检索 | 向量 + BM25 混合检索 + 重排，全链路降级 |
| 观测 | Langfuse trace + Prometheus 延迟百分位 + token 成本 |
| 质量 | 44 项测试（含降级链路）、结构化日志、健康探针 |

---

## 二、按功能：怎么实现的

### 1. 服务骨架（FastAPI）

**做了什么**：HTTP 入口、配置、日志、健康检查、优雅关闭。

**如何实现**：
- `app/main.py`：FastAPI + lifespan 管理 Redis 连接与 Agent 编排器，全局异常转 `{code,message,data}`
- `app/config.py`：pydantic-settings，`AGENT_` 前缀，prod 强校验密钥（`SecretStr`）
- `app/logging.py`：structlog，prod JSON / dev console；**请求 ID 贯穿**（`X-Request-ID`）
- `app/api/health.py`：`/health` 存活、`/ready` 探 Redis
- `Dockerfile`：多阶段构建、非 root 运行、pip 镜像加速、依赖分层缓存

### 2. LLM 客户端（DeepSeek）

**做了什么**：调用 LLM、超时重试、失败降级、token 统计、SSE 流式。

**如何实现**（`app/llm/deepseek.py`）：
- OpenAI 兼容协议，`httpx` 异步
- 超时 30s、指数退避重试 ≤2 次
- 失败返回**降级话术**（不是裸异常）
- `TokenUsage` 累计 prompt/completion tokens

### 3. 工具层（9 个业务工具）

**做了什么**：Agent 调用真实业务能力，不只会说话。

**如何实现**：
- `app/tools/base.py`：pydantic 参数校验、单工具超时、失败重试一次、**连续失败熔断**
- 9 个工具：`search_products` / `get_product` / `add_cart` / `compare_products` / `faq` / `query_orders` / `track_order` / `cancel_order` / `delete_order`（S2 订单工具，需用户 token；取消仅限待支付；删除任意状态、待支付先恢复库存）
- `app/clients/java_api.py`：httpx 调 Java 内部 API，内部 token + **用户 token 透传**；每次请求独立连接（跨事件循环安全）

### 4. LangGraph 状态机

**做了什么**：对话编排——什么意图、调什么工具、何时追问、何时降级。

**如何实现**（`app/agent/graph.py`）：

```
START → guard（输入消毒/防注入） → intent（LLM 意图+槽位）
          │
          ├─ 信息不足 → clarify（追问，≤2 轮）→ END
          ├─ 带工具意图 → tool_route → tool_execute（≤2 轮循环）→ respond
          └─ 闲聊 → respond
任一节点异常 → error → degrade（降级话术）→ END
```

- 意图 12 种：推荐/搜索/对比/加购/详情 + **查订单/查物流/取消订单/删除订单** + FAQ/闲聊/其他；`INTENT_TOOL_MAP` 1:1 路由
- 订单写操作硬规则：`track_order`/`cancel_order`/`delete_order` 缺 orderId 必追问
- 解析失败走**规则启发式**兜底
- 防 prompt 注入：system 角色锁定 + nonce 边界 + 控制字符消毒 + 输出过滤
- `AgentDeps` 依赖注入，LLMClient/ToolExecutor 窄协议解耦，可测

### 5. 编排服务（API ↔ 状态机）

**做了什么**：把 HTTP 层与 LangGraph 接起来，管理会话生命周期。

**如何实现**（`app/agent/service.py`）：
- `AgentOrchestrator`：创建/查询/发消息/关闭会话，调 `invoke_turn`
- **网关模式**：Java 网关转发的 sessionId 未在 Agent 建过会话时，按需建线程直接可用（sessionId 以 Java 为准，历史落库在 Java）；`close_session` 幂等（未知会话也返回 ENDED）
- `LLMAdapter`：把异步 `DeepSeekClient` 适配成同步 `LLMClient` 协议；**每次调用新建客户端**，避免 `httpx.AsyncClient` 绑定旧事件循环导致死锁
- `ToolExecutorAdapter`：把工具 dict 适配成 `ToolExecutor` 协议；透传用户 token 到 `ToolContext`

> **踩过的坑**：在 FastAPI 事件循环线程里用 `asyncio.run_coroutine_threadsafe` + `future.result()` 会死锁（阻塞自己要调度的 loop）。解法：独立线程 + `asyncio.run`。

### 6. 记忆层（Redis checkpoint）

**做了什么**：多轮对话上下文、断点恢复、并发安全。

**如何实现**（`app/memory/`）：
- `checkpoint.py`：`ResilientCheckpointSaver`——不可变键 + ZSET 推导 latest + HSETNX 幂等；**吞异常回退内存，失败不阻断对话**
- `window.py`：消息窗口裁剪，防上下文爆长

### 7. RAG 检索管线

**做了什么**：商品/FAQ 检索有据可查，不靠模型瞎编。

**如何实现**（`app/rag/`）：
- `chunker.py`：边界感知分块（512 token / 64 重叠）
- `embedding.py`：OpenAI 兼容 embedding，**失败降级 HashEmbedder**
- `store.py`：向量余弦 + BM25 关键词双路召回
- `retriever.py`：**RRF 融合** + 词法/LLM 重排
- `indexer.py`：批量入库、内容哈希幂等跳过
- **全链路降级**：任一环节失败 → 关键词单路 → 融合序兜底 → 空结果，绝不抛异常

### 8. 可观测性

**做了什么**：出了问题能定位、性能能度量、成本能核算。

**如何实现**（`app/obs/`）：
- `metrics.py`：Prometheus 文本格式——QPS、**延迟 P50/P95/P99**（桶内插值）、工具成功率、LLM token/USD 成本、检索降级计数
- `langfuse.py`：trace/generation/采样/评分；SDK 缺失时 NoOp 无损降级

### 9. 测试与评测

**做了什么**：质量可量化、回归可自动化。

**如何实现**：
- `tests/golden/`：40 条评测样例（检索/工具/回答）
- `tests/test_profile.py`：用户画像学习/注入/追问抑制的单测与回归
- 44 项测试覆盖：降级链路自检、并发 checkpoint、跨轮恢复、RRF 融合、指标百分位、token 成本、画像闭环

### 10. 用户画像（记忆偏好）

**做了什么**：Agent 记住用户预算、偏好、品类、购买历史——重复使用时不再重复追问，搜索自动带历史预算。

**如何实现**（`app/memory/profile.py` + 编排/节点接线）：
- **画像存储**：`ProfileStore`——Redis hash `agent:profile:{userId}`，**读写异常回退进程内内存，失败不阻断对话**；90 天 TTL
- **偏好学习**：每轮结束后从 entities（预算/偏好/品牌/品类）与文本抽取（“预算 200”“不超过 500 块”“2k 左右”等），新值覆盖旧标量、列表按最近优先去重合并
- **购买历史**：首次对话时经 Java `GET /api/shop/orders` 拉最近 2 笔订单 + 条目名入画像（24h 缓存，尽力而为，失败不影响对话）
- **注入**：画像渲染成随机 nonce 数据块（与用户消息同等按“数据非指令”处理），送入意图与回复节点；意图 prompt 声明“画像已有的预算/偏好不要追问”
- **少追问（硬保证）**：LLM 返回的 `missing_slots` 中画像已覆盖的槽位（预算/偏好/品牌）程序化剔除；全被覆盖则不进入追问节点
- **个性化**：搜索工具参数缺预算时兜底用画像预算；老用户欢迎语带画像摘要

```
消息 → [画像加载(存储/订单)] → 状态机(意图/回复带画像块) → [轮后学习] → 存储
```

---

## 三、如何启动

### 环境要求

| 项 | 说明 |
|---|---|
| Docker + Docker Compose | 服务运行 |
| Redis | 由 Compose 自动启动 |
| DeepSeek API Key | LLM 调用 |
| Java 后端 | 工具调用目标（默认 `http://host:8080`） |

### 启动步骤

```bash
cd agent-service

# 1. 准备配置
cp .env.example .env
# 编辑 .env，填写：
#   AGENT_LLM_API_KEY=sk-xxx          # DeepSeek key（必须）
#   AGENT_JAVA_API_BASE_URL=http://<Java地址>:8080
#   AGENT_INTERNAL_TOKEN=xxx          # 服务间 token（可选）

# 2. 构建并启动（app + redis）
docker compose up -d --build

# 3. 验证
curl http://localhost:8000/health   # {"status":"healthy"}
curl http://localhost:8000/ready    # {"status":"ready","checks":{"redis":"ok"}}
```

### 本地开发（不用 Docker）

```bash
# 需要 Python 3.12+ 与本地 Redis
pip install -e .
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

### 常用运维命令

```bash
docker compose logs -f app        # 看日志
docker compose restart app        # 重启
docker compose down               # 停止
docker compose up -d --build      # 代码更新后重建
```

### 与 Java 联调

Java 侧导购接口转发到本服务（或直接由客户端调用），鉴权 token 通过 `Authorization: Bearer` 透传：

```bash
# 创建会话
curl -X POST http://localhost:8000/api/shoppingguide/sessions \
  -H "Content-Type: application/json" -d '{}'

# 发消息（带用户 token，工具才能调 Java）
curl -X POST http://localhost:8000/api/shoppingguide/sessions/<sessionId>/messages \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <accessToken>" \
  -d '{"message":"预算 200 以内有什么好耳机？"}'
```

---

## 四、生产级特性汇总

| 维度 | 实现 |
|---|---|
| **容错** | 超时、指数退避重试、熔断、降级话术、优雅关闭 |
| **可观测** | 请求 ID、结构化日志、P95 延迟、token 成本、Langfuse |
| **安全** | 防 prompt 注入、内部 token、输入校验、日志脱敏 |
| **可测** | 16 项测试、golden set、依赖注入解耦 |
| **可运维** | 健康探针、Docker Compose、分环境配置、配置校验 |

## 五、简化项（如实声明）

- 单机部署，无多活/灰度
- 不做模型微调/自建推理
- rerank 用轻量词法实现，LLM 重排可选
