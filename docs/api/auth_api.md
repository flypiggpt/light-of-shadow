# Auth API

登录 / 注册 / 令牌刷新。所有接口 `POST`,请求与响应均为 `application/json`。

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
- 常见错误码：`400` 参数错误、`401` 未授权、`403` 禁止、`404` 未找到、`500` 服务器错误

## 1. 登录

`POST /api/auth/login`

请求体：

```json
{ "account": "user@example.com", "password": "password123" }
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "userId": "u_10086",
    "nickname": "User",
    "avatarUrl": "",
    "accessToken": "ey...",
    "refreshToken": "def...",
    "expiresIn": 7200
  }
}
```

说明：`accessToken` 为 JWT（默认 2 小时）；`refreshToken` 为不透明随机串，以 SHA-256 哈希存储（默认 30 天）。

## 2. 注册

`POST /api/auth/register`

请求体：

```json
{ "account": "new@example.com", "password": "pwd123", "nickname": "New" }
```

成功响应：

```json
{ "code": 0, "message": "注册成功", "data": { "userId": "u_10087" } }
```

## 3. 刷新令牌

`POST /api/auth/refresh`

请求体：

```json
{ "refreshToken": "def50200..." }
```

成功响应：

```json
{ "code": 0, "message": "ok", "data": { "accessToken": "ey...", "expiresIn": 7200 } }
```

---

## 说明

- 密码重置 / 验证码接口尚未实现（见根 README Notes）。
- 除登录、注册、刷新外，其余业务接口需携带：

```http
Authorization: Bearer <accessToken>
```
