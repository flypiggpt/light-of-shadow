# 音影寻光 · Light of Shadow

**面向视障用户的辅助应用** —— 端侧障碍检测与语音播报、无障碍优先的商城，以及一位记得你预算的 AI 购物助手。

[English](README.md) | **简体中文**

> 📦 **下载 APK**：[Release v0.1.0](https://github.com/flypiggpt/light-of-shadow/releases/tag/v0.1.0)

## 项目亮点

- **距离优先的端侧感知** —— 本地 TFLite 检测器识别前方是什么（行人/车辆/家具…）并语音播报；在研的深度管线进一步补上米制距离，且保证"漏识别 ≠ 漏报"
- **有真实工程含量的 AI 购物助手** —— LangGraph 状态机（意图 → 工具路由 → 多轮追问 → 降级）、混合 RAG 检索、跨会话用户画像记忆、订单工具——不是裸调一个 LLM 聊天接口
- **质量门禁是一等公民** —— 129 项自动化测试、40 条 golden 评测样例接入 CI（工具调用准确率 / 检索命中率 / 回答正确率），质量跌破基线直接挡合并
- **无障碍贯穿始终** —— 导盲播报、助手回复、下单结果全部 TTS 朗读；交互控件带 TalkBack 语义

## 架构

三个可独立部署的部分——刻意这样拆分，让 AI 工程化的故事不被淹没在 Web 后端里：

```
┌────────────────────┐        ┌──────────────────────┐        ┌──────────────────────┐
│     Android 客户端  │  HTTP  │     Java 后端         │  HTTP  │    Python Agent       │
│ Kotlin + Compose   │ ─────► │ Spring Boot 模块化单体 │ ─────► │ FastAPI + LangGraph   │
│ CameraX + TFLite   │        │ 鉴权 · 商城 · 商家    │        │ 工具 · RAG · 评测     │
└────────────────────┘        └──────────┬───────────┘        └──────────┬───────────┘
        │  纯端侧推理，不依赖云端           │ MySQL · MinIO                 │ Redis
        ▼                                 ▼                               ▼
   障碍检测 + 语音播报                商品/订单数据                   checkpoint · 用户画像
```

- **客户端**感知完全在端侧完成；商城部分对接 Java 后端
- **Java 后端**是模块化单体（不上 Spring Cloud——这个体量不需要），同时充当 Agent 网关：注入用户身份、持久化对话历史
- **Python Agent** 承载全部智能：LangGraph 状态机 + 9 个业务工具、混合检索（向量 + BM25 + RRF 重排）、跨会话用户记忆、评测与可观测（Langfuse trace、Prometheus 指标、golden 回归）

## 功能清单

### 导盲（客户端）
- 本地 TFLite 实时障碍检测（COCO 类别 + 中文标签）
- 语音播报带方向、冷却与限流——不会每帧轰炸
- 助手回复与操作结果 TTS 朗读（可在设置中关闭）

### 商城（客户端 + 后端）
- 商品浏览与关键词搜索 · 购物车 · 下单 · 模拟支付 · 取消 · 删除 · 地址簿
- 商家后台（`light-of-shadow-merchant/`，Vue3 + Element Plus）：商品增删改 + 图片上传（MinIO 预签名直传）

### AI 购物助手（Agent）
- **意图状态机**（LangGraph）：12 种意图、工具路由、有界追问循环、优雅降级
- **记忆偏好**：跨会话记住预算/偏好/品类/最近购买，重复的问题不再重复问
- **订单工具**：自然语言查单/看物流/取消/删除订单（写操作必须有单号——没有就追问）
- **RAG 问答**：混合检索知识库；事实来自检索结果，不是模型凭记忆编
- **质量**：40 条 golden 样例对真实路由评测（温度 0），CI 基线门禁

## 目录结构

```
light-of-shadow/
├── light-of-shadow-client/     # Android 客户端（Kotlin + Compose + CameraX + TFLite）
├── light-of-shadow-backend/    # Java 21 · Spring Boot 3 · MyBatis
├── light-of-shadow-merchant/   # 商家后台（Vue3 + Element Plus）
├── agent-service/              # Python Agent（FastAPI + LangGraph + RAG + 评测）
├── tools/                      # 模型转换脚本（Depth Anything → TFLite）
├── docs/                       # API 契约 · 数据库结构 · Git 与 UI 规范
└── .github/workflows/          # 质量门禁：agent-quality · backend-quality
```

## 快速开始

### Android 客户端

```bash
cd light-of-shadow-client
./gradlew :app:assembleDebug          # Windows 用 .\gradlew.bat
bash install-debug.sh                 # 构建 + 安装 + 启动（adb）
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。默认直连演示服务器；本地联调在 `local.properties` 里配 `api.base.url=...`。

### Java 后端

```bash
cd light-of-shadow-backend
# 1. 复制 src/main/resources/application-example.yaml → application.yaml 并填环境变量
# 2. 建库：docs/db/mysql_*.sql 导入 MySQL 8
mvn test                              # 33 项测试，H2 内存库——零外部依赖
mvn package && java -jar target/light-of-shadow-backend-*.jar
```

部署脚本：`./deploy-remote.sh deploy`（rsync + 远程打包重启）。

### Python Agent

```bash
cd agent-service
cp .env.example .env                  # 填 AGENT_LLM_API_KEY（DeepSeek）
docker compose up -d --build          # app + redis，端口 8000
```

本地开发：`pip install -e ".[dev]"` 后 `uvicorn app.main:app`。

### 商家后台

```bash
cd light-of-shadow-merchant
npm install && npm run dev
```

## 演示与测试账号

Android 客户端默认直连公开演示服务器（`132.232.75.62`）。

| 角色 | 账号 | 密码 |
|---|---|---|
| 商家 | `shopowner` | `shop123456` |
| 用户 | `yan` | `yan` |

## 测试与质量

| 层 | 套件 | 在哪跑 |
|---|---|---|
| Agent 单测 + HTTP 层 | 95 项 + 1 项 LLM 门禁用例 | `pytest tests/` |
| Golden 评测 | 40 条：检索 / 工具调用 / 回答 | `python tests/golden/run_eval.py` |
| Java | 33 项（H2，离线） | `mvn test` |
| CI 门禁 | `agent-quality` · `backend-quality` | 每个 PR |

golden 评测走**真实意图路由**（温度 0）并受基线约束——质量跌破底线 CI 变红、PR 无法合并；评测报告自动挂 CI artifact。

## API 文档

契约全部在 [`docs/api/`](docs/api/)：

- [auth_api.md](docs/api/auth_api.md) —— 登录 / 注册 / 令牌刷新轮换
- [shop_api.md](docs/api/shop_api.md) —— 商品 / 购物车 / 订单 / 地址
- [shoppingguide_api.md](docs/api/shoppingguide_api.md) —— AI 助手（会话 / 消息 / 画像）
- [merchant_api.md](docs/api/merchant_api.md) —— 商家商品管理

## 已知限制

如实声明——这是作品集项目，不是生产服务：

- **模拟支付**——无真实支付与物流
- **debug 签名 APK**——安装需允许"未知来源"；release 签名在计划中
- **APK 约 105MB**——3 个 ABI 的 TFLite 原生库 + 未混淆的 debug dex；ABI 拆分 / R8 压缩在计划中
- **minSdk 36**（Android 16+）——目前仅在开发机型验证
- 验证码走开发模式（日志回显、不真发邮件），除非配好 SMTP

## 路线图

- **端侧米制测距**（在研分支，暂搁置）：深度模型发现障碍并给米制距离、检测器只负责命名——漏识别永不漏报；不上桌面原型直接端侧落地，分支上 51 项单测已全绿
- release 签名 + APK 瘦身
- 无障碍 S4：结构化语音摘要

## 协议与贡献

Apache 2.0 · [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md)
Git 流程与 UI 规范：[docs/git.md](docs/git.md) · [docs/style.md](docs/style.md)
