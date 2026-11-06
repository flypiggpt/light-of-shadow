# Agent 服务：做了什么、按功能、如何实现

> 生产级 Python Agent 服务，负责「音影寻光」导购智能。  
> 与 Java 业务单体（账号/商品/订单）HTTP 契约解耦。

---

## 一、做了什么

新建 `agent-service/` Python 服务，把导购从「一问一答调 LLM」升级为**可推理、可调工具、可恢复、可观测**的 Agent 系统。

```
Android ──► Java（账号/商品/订单）──► MySQL
              │ HTTP
              ▼
         Python Agent（本服务）
         FastAPI + LangGraph + RAG + Langfuse
```

| 维度 | 能力 |
|---|---|
| 对话 | LangGraph 状态机，意图识别→工具路由→多轮追问→降级 |
| 工具 | 搜商品 / 查详情 / 加购 / 对比 / FAQ，带校验与熔断 |
| 记忆 | Redis checkpoint，断点恢复、并发安全 |
| 检索 | 向量 + BM25 混合检索 + 重排，全链路降级 |
| 观测 | Langfuse trace + Prometheus 延迟百分位 + token 成本 |
| 质量 | 16 项测试（含降级链路）、结构化日志、健康探针 |

---

## 二、按功能：怎么实现的

### 1. 服务骨架（FastAPI）

**做了什么**：HTTP 入口、配置、日志、健康检查、优雅关闭。

**如何实现**：
- `app/main.py`：FastAPI + lifespan 管理 Redis 连接，全局异常转 `{code,message,data}`
- `app/config.py`：pydantic-settings，`AGENT_` 前缀，prod 强校验密钥
- `app/logging.py`：structlog，prod JSON / dev console；**请求 ID 贯穿**（`X-Request-ID` 生成/透传，防日志注入白名单）
- `app/api/health.py`：`/health` 存活、`/ready` 探 Redis
- Docker 多阶段构建、非 root 运行

### 2. LLM 客户端（DeepSeek）

**做了什么**：调用 LLM、超时重试、失败降级、token 统计、SSE 流式。

**如何实现**（`app/llm/deepseek.py`）：
- OpenAI 兼容协议，`httpx` 异步
- 超时 30s、指数退避重试 ≤2 次
- 失败返回**降级话术**（不是裸异常）
- `TokenUsage` 累计 prompt/completion tokens，供成本核算

### 3. 工具层（5 个业务工具）

**做了什么**：Agent 调用真实业务能力，不只会说话。

**如何实现**：
- `app/tools/base.py`：pydantic 参数校验、单工具超时、失败重试一次、**连续失败熔断**
- 5 个工具：`search_products` / `get_product` / `add_cart` / `compare_products` / `faq`
- `app/clients/java_api.py`：httpx 调 Java 内部 API，内部 token、超时重试

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

- 解析失败走**规则启发式**兜底
- 防 prompt 注入：system 角色锁定 + nonce 边界 + 控制字符消毒 + 输出过滤
- `AgentDeps` 依赖注入，LLMClient/ToolExecutor 窄协议解耦，可测

### 5. 记忆层（Redis checkpoint）

**做了什么**：多轮对话上下文、断点恢复、并发安全。

**如何实现**（`app/memory/`）：
- `checkpoint.py`：`ResilientCheckpointSaver`——不可变键 + ZSET 推导 latest + HSETNX 幂等；**吞异常回退内存，失败不阻断对话**
- `window.py`：消息窗口裁剪，防上下文爆长

### 6. RAG 检索管线

**做了什么**：商品/FAQ 检索有据可查，不靠模型瞎编。

**如何实现**（`app/rag/`）：
- `chunker.py`：边界感知分块（512 token / 64 重叠）
- `embedding.py`：OpenAI 兼容 embedding，**失败降级 HashEmbedder**
- `store.py`：向量余弦 + BM25 关键词双路召回
- `retriever.py`：**RRF 融合** + 词法/LLM 重排
- `indexer.py`：批量入库、内容哈希幂等跳过
- **全链路降级**：任一环节失败 → 关键词单路 → 融合序兜底 → 空结果，绝不抛异常

### 7. 可观测性

**做了什么**：出了问题能定位、性能能度量、成本能核算。

**如何实现**（`app/obs/`）：
- `metrics.py`：Prometheus 文本格式——QPS、**延迟 P50/P95/P99**（桶内插值）、工具成功率、LLM token/USD 成本、检索降级计数
- `langfuse.py`：trace/generation/采样/评分；SDK 缺失时 NoOp 无损降级

### 8. 测试与评测

**做了什么**：质量可量化、回归可自动化。

**如何实现**：
- `tests/golden/`：40 条评测样例（检索/工具/回答）
- 16 项测试覆盖：降级链路自检、并发 checkpoint、跨轮恢复、RRF 融合、指标百分位、token 成本

---

## 三、生产级特性汇总

| 维度 | 实现 |
|---|---|
| **容错** | 超时、指数退避重试、熔断、降级话术、优雅关闭 |
| **可观测** | 请求 ID、结构化日志、P95 延迟、token 成本、Langfuse |
| **安全** | 防 prompt 注入、内部 token、输入校验、日志脱敏 |
| **可测** | 16 项测试、golden set、依赖注入解耦 |
| **可运维** | 健康探针、Docker、分环境配置、配置校验 |

## 四、简化项（如实声明）

- 单机部署，无多活/灰度
- 不做模型微调/自建推理
- rerank 用轻量词法实现，LLM 重排可选
