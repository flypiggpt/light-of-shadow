# SMTP 发信配置（QQ 邮箱）

验证码默认走 `DevCodeSender`（日志打印，不真发信）。  
要真实发邮件：配置 `spring.mail` 后自动切换为 `EmailCodeSender`。

## 你需要准备

1. 一个 **QQ 邮箱**（发件人）
2. 登录 [https://mail.qq.com](https://mail.qq.com) → 设置 → 账户
3. 找到 **POP3/IMAP/SMTP/Exchange/CardDAV/CalDAV服务**，开启 **IMAP/SMTP服务**
4. 按提示用手机发短信，获取 **授权码**（16 位字母，不是 QQ 密码）

## 服务器配置

编辑 `/home/ubuntu/light-of-shadow-backend/src/main/resources/application.yaml`，在 `spring:` 下加：

```yaml
  mail:
    host: smtp.qq.com
    port: 465
    username: 你的QQ邮箱@qq.com
    password: 刚才拿到的授权码
    properties:
      mail.smtp.ssl.enable: true
```

或用环境变量（推荐，不落盘）：

```bash
export MAIL_USERNAME=你的QQ邮箱@qq.com
export MAIL_PASSWORD=授权码
```

然后在 `application.yaml` 里写：

```yaml
  mail:
    host: smtp.qq.com
    port: 465
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
    properties:
      mail.smtp.ssl.enable: true
```

## 验证

重启后端后，调一次发码接口：

```bash
curl -X POST http://127.0.0.1:8080/api/auth/code \
  -H 'Content-Type: application/json' \
  -d '{"channel":"email","target":"你的另一个邮箱","scene":"register"}'
```

- 响应里 **没有** `verificationCode` 字段（prod 不回显）
- 收信邮箱收到「【Light of Shadow】验证码」邮件

## 常见问题

| 问题 | 处理 |
|---|---|
| `AuthenticationFailed` | 用**授权码**，不是 QQ 密码；确认 SMTP 已开启 |
| 连接超时 | 确认 465 端口、`mail.smtp.ssl.enable: true` |
| 还是日志打印验证码 | 说明 `spring.mail.host` 没配上，仍是 DevCodeSender |

## 163 / Gmail

| 邮箱 | host | port | 备注 |
|---|---|---|---|
| QQ | smtp.qq.com | 465 | 授权码 |
| 163 | smtp.163.com | 465 | 授权码 |
| Gmail | smtp.gmail.com | 465 | App Password（需开两步验证） |
