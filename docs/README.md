# 文档索引

音影寻光项目文档。API 契约以本目录为准，前后端联调时对照这里的文档修改。

## API

| 文档 | 说明 |
|---|---|
| [api/auth_api.md](api/auth_api.md) | 登录 / 注册 / 令牌刷新 |
| [api/merchant_api.md](api/merchant_api.md) | 商家侧商品管理 |
| [api/shop_api.md](api/shop_api.md) | 买家侧商城：商品、购物车、订单 |
| [api/shoppingguide_api.md](api/shoppingguide_api.md) | AI 导购会话与推荐 |

统一响应格式：`{code, message, data}`，`code == 0` 成功。

## 设计

| 文档 | 说明 |
|---|---|
| [style.md](style.md) | 客户端 UI 风格（Neumorphism）与组件约定 |
| [smtp-setup.md](smtp-setup.md) | 验证码真实发信（QQ 邮箱 SMTP）配置步骤 |

## 数据库

| 文档 | 说明 |
|---|---|
| [db/mysql_schema.sql](db/mysql_schema.sql) | 用户与基础表 |
| [db/mysql_add_user_role.sql](db/mysql_add_user_role.sql) | 用户角色字段 |
| [db/mysql_shop_tables.sql](db/mysql_shop_tables.sql) | 商城相关表 |

## 规划

| 文档 | 说明 |
|---|---|
| [plan.md](plan.md) | 下一阶段计划：技术栈（Java + Python Agent + Redis）与功能对照、里程碑 |

## 协作

| 文档 | 说明 |
|---|---|
| [git.md](git.md) | Git 使用指南（双人开发）：分支、PR、冲突、monorepo 约定 |

## 约定

- 后端接口变更 → 先改本目录文档，再改代码。
- 客户端接入细节写在各 API 文档的示例里，不要另开副本。
