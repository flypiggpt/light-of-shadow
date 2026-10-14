# Shop API（买家侧）

商城买家功能：商品浏览、购物车、订单、图片上传。除商品详情外均需登录。

Base URL: `http://<host>:8080/api`

## 统一响应格式

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

- `code == 0` 表示成功
- 常见错误码：`400` 参数错误、`401` 未授权（未登录或 token 无效）、`404` 未找到、`500` 服务器错误

除登录 / 注册外，所有接口需携带：

```http
Authorization: Bearer <accessToken>
Content-Type: application/json
```

## 1. 商品列表

`GET /api/shop/products`

Query 参数：
- `page`（可选，默认 1）
- `size`（可选，默认 20，上限 100）
- `keyword`（可选，按名称模糊搜索）

成功响应：

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
        "id": "p_1001",
        "name": "智能导盲耳机",
        "description": "低延迟骨传导，支持语音播报",
        "priceCent": 29900,
        "stock": 96,
        "coverUrl": "https://cdn.example.com/shop/p_1001.jpg"
      }
    ]
  }
}
```

## 2. 商品详情

`GET /api/shop/products/{id}`

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "p_1001",
    "name": "智能导盲耳机",
    "description": "低延迟骨传导，支持语音播报",
    "priceCent": 29900,
    "stock": 96,
    "coverUrl": "https://cdn.example.com/shop/p_1001.jpg"
  }
}
```

商品不存在时返回 `404`。

## 3. 购物车详情

`GET /api/shop/cart`

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "itemId": 9001,
        "productId": "p_1001",
        "productName": "智能导盲耳机",
        "quantity": 1,
        "unitPriceCent": 29900
      }
    ],
    "totalCent": 29900
  }
}
```

## 4. 添加购物车项

`POST /api/shop/cart/items`

请求体：

```json
{ "productId": "p_1001", "quantity": 1 }
```

成功响应：

```json
{ "code": 0, "message": "ok", "data": { "itemId": 9001 } }
```

## 5. 更新购物车项数量

`PATCH /api/shop/cart/items/{itemId}`

请求体：

```json
{ "quantity": 2 }
```

成功响应：

```json
{ "code": 0, "message": "ok", "data": { "itemId": 9001, "quantity": 2 } }
```

## 6. 删除购物车项

`DELETE /api/shop/cart/items/{itemId}`

成功响应：

```json
{ "code": 0, "message": "ok", "data": { "itemId": "9001" } }
```

## 7. 创建订单（结算）

`POST /api/shop/orders`

请求体（可选，可传 `{}` 或空）：

```json
{ "addressId": "addr_001", "remark": "工作日白天配送" }
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "orderId": "o_20260526001",
    "orderNo": "202605260001",
    "status": "PENDING_PAYMENT",
    "totalCent": 29900,
    "createdAt": "2026-05-26T10:20:30Z"
  }
}
```

## 8. 订单列表

`GET /api/shop/orders`

Query 参数：
- `page`（可选，默认 1）
- `size`（可选，默认 20）
- `status`（可选，如 `PENDING_PAYMENT`、`PAID`、`SHIPPED`、`COMPLETED`、`CANCELED`）

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "page": 1,
    "size": 20,
    "total": 1,
    "items": [
      {
        "orderId": "o_20260526001",
        "orderNo": "202605260001",
        "status": "PENDING_PAYMENT",
        "totalCent": 29900,
        "createdAt": "2026-05-26T10:20:30Z"
      }
    ]
  }
}
```

## 9. 图片上传（商家）

`POST /api/shop/images`（`multipart/form-data`）

表单字段：
- `file`（必填，图片文件）
- `purpose`（可选，用途标记）

需要商家权限。成功响应：

```json
{ "code": 0, "message": "ok", "data": { "url": "/media/xxx.jpg", "path": "/media/xxx.jpg" } }
```

`url` 为相对路径，可直接作为商品 `coverUrl` 使用；通过 `/media/**` 访问（见根 README Image storage）。
