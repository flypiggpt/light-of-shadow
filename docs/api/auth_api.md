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
- 常见错误码：`400` 参数错误、`401` 未授权、`403` 禁止、`404` 未找到、`429` 请求过于频繁、`4011` 验证码无效、`500` 服务器错误

## 1. 登录（密码 / 验证码 二选一）

`POST /api/auth/login`

**方式 A：密码登录**（`account` 可为用户名或邮箱）

```json
{ "account": "alice", "password": "password123" }
{ "account": "alice@example.com", "password": "password123" }
```

**方式 B：验证码登录**（仅邮箱）

```json
{ "account": "alice@example.com", "verificationCode": "482913" }
```

说明：
- `account` 可为**用户名**或**邮箱**（含 `@` 按邮箱）
- `password` 与 `verificationCode` 只能填一个；验证码 scene 为 `login`，**仅邮箱账号**可用
- 用户名仅限字母/数字

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

## 2. 注册（需邮箱验证码）

`POST /api/auth/register`

请求体：

```json
{
  "username": "alice",
  "email": "alice@example.com",
  "password": "pwd12345",
  "nickname": "Alice",
  "verificationCode": "482913"
}
```

说明：
- `username`：仅字母/数字，全局唯一
- `email`：合法邮箱，全局唯一；**必须** `verificationCode`（scene=`register`）
- 密码不限制最少 8 位

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

## 4. 发送验证码

`POST /api/auth/code`

请求体：

```json
{ "channel": "email", "target": "user@example.com", "scene": "reset" }
```

| 字段 | 说明 |
|---|---|
| channel | 目前仅 `email`（预留 `sms`） |
| target | 收件邮箱 |
| scene | `login`（登录）/ `register`（注册）/ `reset`（重置密码） |

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "expiresIn": 300, "resendAfter": 60, "verificationCode": "482913" }
}
```

- `expiresIn` 验证码有效期（秒），默认 300
- `resendAfter` 最短重发间隔（秒），默认 60
- `verificationCode` **仅非 prod 回显**（便于 dev 联调）；生产为 `null`，验证码通过邮件送达
- 频控：同邮箱同场景 60s 内不可重发；日上限默认 10 次；错误尝试默认 5 次后作废

## 5. 忘记密码（触发发码）

`POST /api/auth/password/forgot`

请求体（与 `/code` 兼容，`account` 等价于 `target`）：

```json
{ "account": "user@example.com" }
```

或：

```json
{ "channel": "email", "target": "user@example.com" }
```

成功响应：同「发送验证码」（内部 scene 固定为 `reset`）。

## 6. 重置密码

`POST /api/auth/password/reset`

请求体：

```json
{ "email": "user@example.com", "verificationCode": "482913", "newPassword": "newpass123" }
```

成功响应：

```json
{ "code": 0, "message": "密码已重置", "data": null }
```

说明：验证码校验通过后立即作废；新密码长度 ≥ 8。错误码 `4011` 表示验证码错误/过期/次数过多。

---

## 说明

- 验证码当前由后端日志输出（dev）；配置 SMTP 后走真实邮件，接口不变。
- 除登录、注册、刷新、发码、找回密码外，其余业务接口需携带：

```http
Authorization: Bearer <accessToken>
```
