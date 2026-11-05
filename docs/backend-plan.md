# 后端计划（短期冻结前收尾）

原则：**后端只补闭环，不加新业务**。补完「4 个洞」后冻结，时间让给端侧感知管线与 Python Agent。

架构定调：**Java 模块化单体 + Python Agent = 2 个服务**。不拆 Spring Cloud 微服务（体量不需要、实习已覆盖）。

---

## 一、4 个洞（约 2–3 天）

### 1. logout + refreshToken 轮换（半天）

**现状**：refresh 只换 accessToken；旧 refreshToken 不作废；无 logout 接口。

**做**：
- `POST /api/auth/logout`：revoke 当前 refreshToken
- refresh 时轮换：发新 refreshToken、旧的置 `revoked=1`
- 客户端登出调 logout 并清本地 token

**文件**：
- `AuthController` / `AuthServiceImpl` / `RefreshTokenMapper`
- 客户端 `TokenStore.clear()`、`ApiClient.logout()`
- `docs/api/auth_api.md`

### 2. 导购会话持久化（1 天）

**现状**：`ConcurrentHashMap` 内存存会话，重启丢。后面 LangGraph checkpoint 也需要落库，这是前置。

**做**：
- 新表 `sg_session` / `sg_message` / `sg_feedback`
- `ShoppingGuideServiceImpl` 内存 Map → Mapper 读写
- 重启后历史仍在

**文件**：
- `docs/db/mysql_shoppingguide.sql`（或并入 schema）
- `shoppingguide/` entity / mapper / service
- `docs/api/shoppingguide_api.md` 说明持久化

### 3. 商品搜索 + 分页（半天）

**现状**：API 支持 page/size，UI 只取第一页；搜索是前端过滤当前页。

**做**：
- `GET /api/shop/products?keyword=&page=&size=` 模糊搜索 + 分页
- 客户端接真实分页/搜索

**文件**：
- `ShopProductController` / `ProductMapper.xml`
- 客户端 `ShopScreen` 商品 Tab（可顺手拆一部分）

### 4. 订单详情 / 取消（半天）

**现状**：只有创建 + 列表。

**做**：
- `GET /api/shop/orders/{id}` 详情（含条目）
- `POST /api/shop/orders/{id}/cancel` 取消（仅待支付/待发货状态）

**文件**：
- `ShopOrderController` / `ShopOrderMapper`
- `docs/api/shop_api.md`

---

## 二、可选（有空再做）

| 项 | 说明 |
|---|---|
| 商品详情页客户端 | 后端 `GET /products/{id}` 已有，UI 接上 |
| 地址（或写死） | `checkout(addressId)` 已支持 |
| 商家端最小管理页 | 后端 Merchant CRUD 已有，客户端没入口 |
| `application.yaml` 本地启动说明 | README 补一段 docker-compose 起 MySQL |

---

## 三、明确不做

- ❌ Spring Cloud / 拆 Java 微服务
- ❌ Redis 当卖点上（可当基础设施，不写进简历叙事）
- ❌ 支付/物流/退款
- ❌ 短信验证码（邮箱已够）

---

## 四、双服务边界（给后面 Agent 让路）

```
Android ──► Java 单体 ──► MySQL
                │
                │ HTTP（OpenAPI 契约）
                ▼
          Python FastAPI + LangGraph ──► Redis（checkpoint）/ 向量库
```

| 服务 | 负责 | 语言 |
|---|---|---|
| Java 单体 | 账号、商品、订单、验证码 | Spring Boot 3 |
| Python Agent | 导购对话、工具调用、RAG | FastAPI + LangGraph |

Java 调 Python：`POST /api/shoppingguide/*` 由 Java 转发到 Agent（或网关），token 只在 Java 校验。

---

## 五、验收

| 洞 | 怎么验 |
|---|---|
| logout/轮换 | 登出后旧 refresh token 401；refresh 后旧 token 不可用 |
| 导购持久化 | 杀 Java 进程重启，会话历史还在 |
| 搜索分页 | 搜关键词、翻页，结果正确 |
| 订单详情/取消 | 详情含条目；取消后状态变 |

流程：开分支 → 实现 → 自测 → PR → **用户测过下令后合并**。
