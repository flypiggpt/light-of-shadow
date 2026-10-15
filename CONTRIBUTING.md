# 贡献指南

感谢你参与音影寻光。本项目是 monorepo（前后端 + 文档同仓），请按以下流程贡献。

## 开始之前

1. 读 [README.md](README.md) 了解结构与构建方式
2. 读 [docs/git.md](docs/git.md) 了解分支、提交、PR 规范（**必读**）
3. 接口契约以 [docs/api/](docs/) 为准，UI 规范见 [docs/style.md](docs/style.md)

## 你可能想贡献的类型

| 类型 | 说明 | 建议入口 |
|---|---|---|
| 修 bug | 用 `fix/` 分支 | Issue 里认领或自报 |
| 新功能 | 用 `feature/` 分支 | **先开 Issue 讨论**，避免和现有路线冲突 |
| 文档 | `docs/` 下的 API、指南、注释 | 小改可直接 PR |
| 无障碍改进 | 语音、提示、交互 | 特别欢迎，请在 PR 里说明测试人群 |
| 安全问题 | 请走 [SECURITY.md](SECURITY.md) | **不要**开公开 Issue |

## 提交流程（摘要）

完整步骤见 [docs/git.md](docs/git.md)。骨架如下：

```bash
git checkout main && git pull
git checkout -b feature/你的改动

# 开发 + 自测
git add 具体文件          # 不要用 git add -A
git commit -m "中文一句话：做了什么 / 为什么"

git push -u origin feature/你的改动
# 然后在 GitHub 开 Pull Request
```

**禁止直接向 `main` 推送。**

## Pull Request 要求

- [ ] 标题说清改动（中文，一句话）
- [ ] 描述包含：**做了什么 / 为什么 / 怎么测的**
- [ ] 已将 `main` 合进自己的分支（或 rebase 过）
- [ ] 本地编译通过；客户端改动尽量真机或模拟器跑过
- [ ] 跨前后端的接口改动，**同一 PR** 内包含 `docs/api/` 更新 + 后端 + 客户端
- [ ] 没有敏感文件（密钥、`application.yaml`、`local.properties`）
- [ ] 一次 PR 只做一件事（功能 / 文档 / 配置分开）

## 代码约定

### 通用

- 提交信息用中文，格式：`做了什么 / 为什么`
- 一次提交只做一件事
- 不确定的设计决策，在 PR 描述里写清你的取舍

### 后端（`light-of-shadow-backend/`）

- Java 21 + Spring Boot 3 + MyBatis
- 接口统一响应 `{code, message, data}`，鉴权失败 `401`
- 新接口先写进 `docs/api/`，再实现
- 敏感配置走环境变量，参考 `application-example.yaml`

### 客户端（`light-of-shadow-client/`）

- Kotlin + Compose + Material3，视觉风格遵循 [docs/style.md](docs/style.md)
- 一屏一个 Composable 文件；UI 导航沿用 `MainActivity` 的 `AuthRoute` 模式
- 网络请求不阻塞 UI 线程，走协程 + `Dispatchers.IO`
- 语音提示注意 TTS 冷却，避免刷屏式播报

## 行为准则

- 友好、具体、对事不对人
- Review 给出可执行建议（「这里可能 401 时 token 没清」比「不行」有用）
- 视障辅助场景下，**可访问性不是加分项，是需求**；讨论交互时请优先考虑语音与无障碍

## 问题反馈

| 类型 | 方式 |
|---|---|
| Bug、功能建议 | GitHub Issue |
| 安全漏洞 | [SECURITY.md](SECURITY.md)（私密渠道） |
| 文档错漏 | 直接开 PR 修，或 Issue |

## License

参与贡献即表示你同意你的改动以本仓库 [LICENSE](LICENSE)（Apache 2.0）发布。
