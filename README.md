# 音影寻光 (Light of Shadow)

面向视障人群的辅助应用：本地视觉识别障碍物 + 语音报警，配套商城与 AI 导购。

## 仓库结构

```
light-of-shadow/                 # monorepo 根（唯一 git 仓库）
├── docs/                        # 项目文档（API / 设计 / 数据库）
├── light-of-shadow-backend/     # Spring Boot 3 + MyBatis 后端
└── light-of-shadow-client/      # Android 客户端（Kotlin + Compose）
```

- 前后端同仓管理，接口变更与文档更新放在同一次提交里。
- 改后端进 `light-of-shadow-backend/`，改客户端进 `light-of-shadow-client/`。
- API 契约见 `docs/api/`，UI 规范见 `docs/style.md`。

## Git 使用规范

1. **分支开发，不直接在 main 上提交**：`main` 只保留可运行、已测过的版本。
   - 新功能 / 修复先建分支：`git checkout -b feature/商城搜索`（分支名用简短中文或英文，表明要做什么）。
   - 在分支上完成开发并自测通过后，再合并回 `main`。
2. **提交前先 `git status` 确认改动的文件**，一次提交只做一件事：功能、文档、配置分开提交。
3. **提交信息用中文**，一句话说明「做了什么 / 为什么」。示例：
   - `修复登录 401 后未清除 token 的问题`
   - `商城商品列表增加关键词搜索`
4. **合并流程**：
   ```
   git checkout main
   git pull
   git merge feature/商城搜索
   git push
   git branch -d feature/商城搜索
   ```
   合并时若有冲突，手动解决后再 `git commit`。
5. **不提交敏感文件**：`application.yaml`、`local.properties`、密钥、`.env` 已在 `.gitignore`，提交前检查不要 `git add -A` 误加。
6. 跨前后端的接口改动，文档（`docs/api/`）与代码放在同一分支一起改。

## 使用方式

### Client（Android 客户端）

在 `light-of-shadow-client/` 下打包 debug APK：

```
./gradlew :app:assembleDebug          # macOS / Linux / WSL
.\gradlew.bat :app:assembleDebug      # Windows
```

一键「编译 + 安装到手机 + 启动」（WSL）：

```
./install-debug.sh
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

### Backend（后端）

本地改完代码后，用 IDEA 的 Deployment + SFTP 上传到云服务器，再在服务器上打包运行：

1. IDEA 配置 Deployment：连接云服务器，把 `light-of-shadow-backend/` 映射到服务器上的项目路径。
2. 代码改动后上传（Upload）同步到服务器。
3. SSH 登录服务器，进入项目目录执行：
   ```
   ./deploy.sh
   ```
   脚本会 `mvn clean package` → 停掉旧进程 → 启动新 jar。
4. 后端地址为 `http://<服务器IP>:8080`，客户端通过该地址联调。

### 测试

测试由你手动完成：打包 → 上传 → 手机 / 浏览器实际操作验证。改动建议先自测通过后再提交。
