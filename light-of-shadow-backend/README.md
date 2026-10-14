# light-of-shadow-backend

Spring Boot 3 + MyBatis 后端,提供登录鉴权、商城、导购问答能力。Java 21。

## 已实现模块

- **Auth**:登录 / 注册 / 刷新令牌(JWT + 不透明 refresh token,SHA-256 哈希存储)
- **Shop**:商品浏览 / 购物车 / 订单 / 图片上传(买家),商家商品管理
- **ShoppingGuide**:基于 LLM 的导购问答(意图识别、商品推荐、动作建议;会话状态存内存,重启丢失)

## API 文档(仓库根 `docs/api/`)

- `../docs/api/auth_api.md` — 登录 / 注册 / 刷新令牌
- `../docs/api/shop_api.md` — 商城买家侧(商品 / 购物车 / 订单 / 图片上传)
- `../docs/api/merchant_api.md` — 商家侧(商品管理)
- `../docs/api/shoppingguide_api.md` — 导购问答

## Database

- MySQL DDL:`../docs/db/mysql_schema.sql`(库与基础表)、`../docs/db/mysql_shop_tables.sql`(商城表,需先执行 schema)、`../docs/db/mysql_add_user_role.sql`(用户表加 role 字段,USER/MERCHANT/ADMIN)
- 按序执行:先 `mysql_schema.sql`,再 `mysql_shop_tables.sql` 与 `mysql_add_user_role.sql`

## Image storage

- 本地上传文件保存在 `uploads/`(可用 `app.storage.local.base-dir` 配置)
- 通过 `/media/**` 对外访问,30 天缓存

## Local config

- 示例配置:`src/main/resources/application-example.yaml`
- 部署时复制为 `application.yaml` 并填写 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `JWT_SECRET` / `DEEPSEEK_API_KEY` 等环境变量
- 敏感配置(application.yaml)已被 `.gitignore` 排除,不会入库

## 部署

- 一键打包部署:`./deploy.sh`(mvn package → 停旧进程 → 启动新 jar)

## Notes

- Email/phone verification password-reset APIs are intentionally not implemented yet.
- Access tokens are JWTs; refresh tokens are opaque random strings stored as SHA-256 hashes.
- 导购会话状态保存在内存中,服务重启后会话与反馈记录丢失,需接入持久化。
