# ShoppingGuide RESTful API 文档

本文档定义 `shoppingguide` 导购模块的后端接口约定。该模块用于商城中的“导购问答”能力：

- 接收用户自然语言问题
- 解析购买意图、预算、偏好
- 返回商品推荐与导购建议
- 复用现有商城交易接口完成加购、改数量、删除、结算、查订单

> 设计原则：**先不引入 RAG**，导购能力优先采用 **LLM + 工具调用** 或 **规则引擎 + 推荐策略** 的方式实现；所有对外接口保持 RESTful 风格。

---

## 1. 通用约定

### 1.1 统一响应格式

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

- `code == 0`：成功
- `code != 0`：业务失败
- `401`：未登录或 token 无效
- `500`：服务端异常

### 1.2 请求头

除登录态校验外，所有接口建议携带：

```http
Authorization: Bearer <accessToken>
Content-Type: application/json
```

### 1.3 导购模块目标

`shoppingguide` 只负责“对话编排与推荐决策”，不直接承担交易闭环。交易相关能力继续复用现有商城接口：

- `GET /api/shop/products`
- `GET /api/shop/cart`
- `POST /api/shop/cart/items`
- `PATCH /api/shop/cart/items/{itemId}`
- `DELETE /api/shop/cart/items/{itemId}`
- `POST /api/shop/orders`
- `GET /api/shop/orders`

---

## 2. 数据模型

### 2.1 导购会话 `ShoppingGuideSession`

```json
{
  "sessionId": "sg_202606010001",
  "userId": "u_10001",
  "status": "ACTIVE",
  "createdAt": "2026-06-01T09:00:00Z",
  "updatedAt": "2026-06-01T09:03:00Z",
  "lastUserMessage": "我想买一个 200 到 300 元、轻便一点的东西"
}
```

字段说明：
- `sessionId`：会话唯一 ID
- `status`：`ACTIVE` / `ENDED`
- `lastUserMessage`：最近一条用户消息摘要

### 2.2 消息 `ShoppingGuideMessage`

```json
{
  "messageId": "msg_9001",
  "sessionId": "sg_202606010001",
  "role": "assistant",
  "contentType": "text",
  "content": "我推荐你先看这 3 款轻便且预算合适的商品。",
  "createdAt": "2026-06-01T09:03:00Z"
}
```

`role` 取值：
- `user`
- `assistant`
- `system`

`contentType` 取值：
- `text`
- `product_list`
- `action`
- `error`

### 2.3 推荐商品 `ShoppingGuideRecommendation`

```json
{
  "productId": "p_1001",
  "name": "智能导盲耳机",
  "description": "低延迟骨传导，支持语音播报",
  "priceCent": 29900,
  "stock": 96,
  "coverUrl": "https://cdn.example.com/shop/p_1001.jpg",
  "score": 0.93,
  "reason": "预算匹配、轻便、支持语音播报"
}
```

### 2.4 推荐动作 `ShoppingGuideAction`

```json
{
  "type": "add_to_cart",
  "label": "加入购物车",
  "productId": "p_1001",
  "quantity": 1
}
```

`type` 取值建议：
- `add_to_cart`
- `update_cart`
- `remove_from_cart`
- `checkout`
- `refine_query`
- `show_more`
- `compare`
- `view_cart`

---

## 3. 会话管理

## 3.1 创建导购会话

- 方法：`POST`
- 路径：`/api/shoppingguide/sessions`
- 说明：创建一个新的导购问答会话

### 请求体

```json
{
  "entry": "shop",
  "source": "ShopScreen",
  "locale": "zh-CN"
}
```

字段说明：
- `entry`：入口类型，默认 `shop`
- `source`：来源页面或组件名
- `locale`：语言环境

### 成功示例

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "session": {
      "sessionId": "sg_202606010001",
      "status": "ACTIVE",
      "createdAt": "2026-06-01T09:00:00Z",
      "updatedAt": "2026-06-01T09:00:00Z"
    },
    "welcomeMessage": "你好，我是商城导购助手。你可以直接告诉我预算、用途或偏好，我来帮你推荐。"
  }
}
```

---

## 3.2 获取会话详情

- 方法：`GET`
- 路径：`/api/shoppingguide/sessions/{sessionId}`
- 说明：获取当前会话状态、摘要与最近推荐结果

### 成功示例

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "session": {
      "sessionId": "sg_202606010001",
      "status": "ACTIVE",
      "createdAt": "2026-06-01T09:00:00Z",
      "updatedAt": "2026-06-01T09:03:00Z",
      "summary": "用户关注轻便、预算 200-300 元的商品"
    },
    "latestRecommendations": [
      {
        "productId": "p_1001",
        "name": "智能导盲耳机",
        "description": "低延迟骨传导，支持语音播报",
        "priceCent": 29900,
        "stock": 96,
        "coverUrl": "https://cdn.example.com/shop/p_1001.jpg",
        "score": 0.93,
        "reason": "预算匹配、轻便、支持语音播报"
      }
    ]
  }
}
```

---

## 4. 导购消息交互

## 4.1 发送用户消息并获取导购回复

- 方法：`POST`
- 路径：`/api/shoppingguide/sessions/{sessionId}/messages`
- 说明：提交用户自然语言问题，后端返回解析结果、导购文案、推荐商品和建议动作

### 请求体

```json
{
  "message": "我想买一个 200 到 300 元、轻便一点的东西",
  "clientContext": {
    "page": "ShopScreen",
    "selectedTab": "guide"
  }
}
```

字段说明：
- `message`：用户输入内容
- `clientContext`：客户端上下文，可选

### 成功示例

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "userMessage": {
      "messageId": "msg_9001",
      "role": "user",
      "contentType": "text",
      "content": "我想买一个 200 到 300 元、轻便一点的东西",
      "createdAt": "2026-06-01T09:03:00Z"
    },
    "assistantMessage": {
      "messageId": "msg_9002",
      "role": "assistant",
      "contentType": "text",
      "content": "我给你找了 3 款更符合预算和轻便需求的商品，你可以先看第一款。",
      "createdAt": "2026-06-01T09:03:01Z"
    },
    "intent": {
      "name": "recommend_product",
      "confidence": 0.96,
      "entities": {
        "minPriceCent": 20000,
        "maxPriceCent": 30000,
        "preference": ["lightweight"]
      }
    },
    "recommendations": [
      {
        "productId": "p_1001",
        "name": "智能导盲耳机",
        "description": "低延迟骨传导，支持语音播报",
        "priceCent": 29900,
        "stock": 96,
        "coverUrl": "https://cdn.example.com/shop/p_1001.jpg",
        "score": 0.93,
        "reason": "预算匹配、轻便、支持语音播报"
      },
      {
        "productId": "p_1002",
        "name": "轻量碳纤维手杖",
        "description": "轻量化设计，夜间反光",
        "priceCent": 18900,
        "stock": 120,
        "coverUrl": "https://cdn.example.com/shop/p_1002.jpg",
        "score": 0.89,
        "reason": "轻便、上手简单、适合基础出行辅助"
      }
    ],
    "actions": [
      {
        "type": "add_to_cart",
        "label": "加入第一款购物车",
        "productId": "p_1001",
        "quantity": 1
      },
      {
        "type": "show_more",
        "label": "换一批推荐"
      },
      {
        "type": "compare",
        "label": "对比这两款"
      }
    ]
  }
}
```

### 说明

该接口是 `shoppingguide` 的核心入口。后端应负责：
- 意图识别
- 条件提取
- 商品筛选 / 排序
- 推荐文案生成
- 动作建议生成

> 注意：价格、库存、商品名等实时信息必须以商品接口或商品中心数据为准，不应由大模型直接编造。

---

## 4.2 获取会话消息列表

- 方法：`GET`
- 路径：`/api/shoppingguide/sessions/{sessionId}/messages`
- 说明：获取导购会话历史消息

### Query 参数
- `page`（可选，默认 1）
- `size`（可选，默认 20）

### 成功示例

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "page": 1,
    "size": 20,
    "total": 2,
    "items": [
      {
        "messageId": "msg_9001",
        "role": "user",
        "contentType": "text",
        "content": "我想买一个 200 到 300 元、轻便一点的东西",
        "createdAt": "2026-06-01T09:03:00Z"
      },
      {
        "messageId": "msg_9002",
        "role": "assistant",
        "contentType": "text",
        "content": "我给你找了 3 款更符合预算和轻便需求的商品，你可以先看第一款。",
        "createdAt": "2026-06-01T09:03:01Z"
      }
    ]
  }
}
```

---

## 4.3 结束会话

- 方法：`POST`
- 路径：`/api/shoppingguide/sessions/{sessionId}/close`
- 说明：结束当前会话，便于清理上下文

### 成功示例

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "sessionId": "sg_202606010001",
    "status": "ENDED"
  }
}
```

---

## 5. 推荐相关能力

## 5.1 获取更多推荐

- 方法：`GET`
- 路径：`/api/shoppingguide/sessions/{sessionId}/recommendations`
- 说明：在当前用户偏好条件下，继续分页获取推荐商品

### Query 参数
- `page`（可选，默认 1）
- `size`（可选，默认 10）

### 成功示例

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "page": 1,
    "size": 10,
    "total": 6,
    "items": [
      {
        "productId": "p_1003",
        "name": "折叠式助行拐杖",
        "description": "便携折叠，适合短途出行",
        "priceCent": 22900,
        "stock": 40,
        "coverUrl": "https://cdn.example.com/shop/p_1003.jpg",
        "score": 0.87,
        "reason": "便携、轻量、价格匹配"
      }
    ]
  }
}
```

---

## 5.2 商品对比

- 方法：`POST`
- 路径：`/api/shoppingguide/compare`
- 说明：对多个商品做导购式对比，返回结构化对比结果

### 请求体

```json
{
  "productIds": ["p_1001", "p_1002"],
  "sessionId": "sg_202606010001"
}
```

### 成功示例

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "summary": "第一款更偏智能语音播报，第二款更偏传统轻便。",
    "items": [
      {
        "productId": "p_1001",
        "highlights": ["语音播报", "低延迟", "轻便"]
      },
      {
        "productId": "p_1002",
        "highlights": ["轻量化", "夜间反光", "更低价格"]
      }
    ]
  }
}
```

---

## 6. 用户反馈

## 6.1 提交导购反馈

- 方法：`POST`
- 路径：`/api/shoppingguide/feedback`
- 说明：提交用户对导购结果的反馈，用于优化推荐排序与话术

### 请求体

```json
{
  "sessionId": "sg_202606010001",
  "messageId": "msg_9002",
  "rating": 5,
  "feedback": "推荐很符合预算，解释清楚"
}
```

字段说明：
- `rating`：1~5 分
- `feedback`：可选文本意见

### 成功示例

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "saved": true
  }
}
```

---

## 7. 与现有商城交易接口的配合

`shoppingguide` 模块**不重复实现购物车和订单**，只负责“导购决策 + 动作建议”。

### 7.1 加购物车

- 仍然使用：`POST /api/shop/cart/items`
- 导购接口只返回：
  - `productId`
  - `quantity`
  - `label`

### 7.2 更新购物车数量

- 仍然使用：`PATCH /api/shop/cart/items/{itemId}`

### 7.3 删除购物车项

- 仍然使用：`DELETE /api/shop/cart/items/{itemId}`

### 7.4 结算下单

- 仍然使用：`POST /api/shop/orders`

### 7.5 订单查询

- 仍然使用：`GET /api/shop/orders`

---

## 8. 错误码建议

- `0`：成功
- `4001`：参数错误
- `4002`：会话不存在
- `4003`：会话已结束
- `4004`：商品不存在
- `4006`：库存不足
- `4010`：未登录或 token 无效
- `5000`：服务端异常

---

## 9. 客户端实现对照

当前 Android 客户端（MVP）已接入：

- `createShoppingGuideSession()`
- `sendShoppingGuideMessage(sessionId, message, clientContext)`

建议后续按迭代接入：

- `getShoppingGuideSession(sessionId)`
- `getShoppingGuideMessages(sessionId, page, size)`
- `closeShoppingGuideSession(sessionId)`
- `getShoppingGuideRecommendations(sessionId, page, size)`
- `compareShoppingGuideProducts(sessionId, productIds)`
- `submitShoppingGuideFeedback(sessionId, messageId, rating, feedback)`

UI 入口建议：主页 `商店` 卡片 -> `ShopScreen` -> `导购` 页签。

---

## 10. 后端实现建议（无 RAG 版）

后端可采用以下编排：

1. 接收用户消息
2. 识别意图（推荐 / 对比 / 加购 / 结算 / 查订单）
3. 调用商品或购物车相关服务
4. 组织 `assistantMessage`
5. 返回推荐商品与动作列表

> 说明：导购不使用 RAG 时，商品实时数据优先来自商品服务，LLM 主要负责理解与表达。

---

## 11. 示例时序

### 场景：推荐并加购

1. 前端 `POST /api/shoppingguide/sessions`
2. 用户输入："我想买一个 200 到 300 元、轻便一点的东西"
3. 前端 `POST /api/shoppingguide/sessions/{sessionId}/messages`
4. 后端返回推荐商品和动作
5. 用户点击“加入购物车”
6. 前端调用 `POST /api/shop/cart/items`
7. 用户继续点击“去结算”
8. 前端调用 `POST /api/shop/orders`

---

## 12. 备注

- 本文档面向 `shoppingguide` 后端联调
- 若后续要接入大模型，建议仍然保持 **RESTful + 工具调用** 的架构
- 若后续要引入知识问答，再单独扩展检索层，不建议一开始就做完整 RAG


