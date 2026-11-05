# Merchant Image API（预签名直传）

商家上传商品封面。密钥不下发客户端，采用 **预签名 URL 直传对象存储（MinIO / S3）**。

Base URL: `http://<host>:8080/api`

鉴权：`Authorization: Bearer <accessToken>`，需 `MERCHANT` 角色。

## 上传链路

```
1. POST /merchant/images/upload-request  → 拿 uploadUrl + objectKey
2. 浏览器/客户端 PUT uploadUrl（二进制直传 MinIO）
3. 商品创建/编辑时 coverUrl = publicUrl
```

## 1. 申请上传 URL

`POST /api/merchant/images/upload-request`

```json
{ "contentType": "image/jpeg" }
```

| 字段 | 说明 |
|---|---|
| contentType | `image/jpeg` / `image/png` / `image/webp` |

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "objectKey": "product/u_xxx/abc.jpg",
    "uploadUrl": "http://<minio>/product-images/...",
    "publicUrl": "http://<minio>/product-images/product/u_xxx/abc.jpg",
    "expiresIn": 900,
    "maxSizeBytes": 2097152
  }
}
```

## 2. 直传

`PUT <uploadUrl>`，body 为图片二进制，`Content-Type` 与申请时一致。

## 限制

- 单文件 ≤ 2MB
- 仅 jpg / png / webp
- objectKey 由服务端生成（UUID），防覆盖
- 删除商品时应删除对应对象（业务侧调用）
