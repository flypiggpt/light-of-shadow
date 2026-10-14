# Merchant API（商家侧）

商家商品管理。所有接口均需商家权限（`Authorization: Bearer <accessToken>`，token 对应角色为 `MERCHANT`）。

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
- 常见错误码：`400` 参数错误、`401` 未授权、`404` 未找到、`500` 服务器错误

## 1. 创建商品

`POST /api/merchant/products`

请求体：

```json
{
  "name": "智能导盲耳机",
  "description": "低延迟骨传导，支持语音播报",
  "priceCent": 29900,
  "stock": 96,
  "coverUrl": "/media/xxx.jpg",
  "categoryId": "c_001"
}
```

校验规则：
- `name`、`coverUrl` 必填
- `priceCent`、`stock` 必须 >= 0
- `coverUrl` 必须以 `/media/`、`http://` 或 `https://` 开头

成功响应：`data` 为创建后的商品对象（含 `id`、`merchantId`、`createdAt`）。

## 2. 商品列表（商家视角）

`GET /api/merchant/products`

Query 参数：
- `page`（可选，默认 1）
- `size`（可选，默认 20，上限 100）
- `keyword`（可选）

只返回当前商家自己的商品。成功响应同买家侧列表格式（`page`/`size`/`total`/`items`）。

## 3. 商品详情

`GET /api/merchant/products/{id}`

只能查看当前商家自己的商品，否则 `404`。

## 4. 更新商品

`PATCH /api/merchant/products/{id}`

请求体（任意字段可选，只更新提供的字段）：

```json
{
  "name": "智能导盲耳机 Pro",
  "priceCent": 32900,
  "stock": 50
}
```

## 5. 删除商品

`DELETE /api/merchant/products/{id}`

成功响应：

```json
{ "code": 0, "message": "ok", "data": { "id": "p_1001" } }
```
