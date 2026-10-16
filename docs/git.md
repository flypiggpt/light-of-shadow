# Git 协作流程（协作者）

> 你是**外部协作者**：没有主仓库推送权限，通过 **Fork + PR** 贡献。本文是唯一流程说明，照着做即可。

**主仓库**：`https://github.com/flypiggpt/light-of-shadow`（下称 upstream）  
**你的 Fork**：`https://github.com/<你的用户名>/light-of-shadow`（下称 origin）

---

## 流程总览

```
Fork → clone 自己的 Fork → 开分支 → 小步提交 → 推到 origin
  → 向 upstream:main 发 PR → 仓库主 review/合并 → 同步 main、删分支
```

---

## 1. 一次性准备

```bash
# GitHub 网页上先 Fork 主仓库，然后：
git clone https://github.com/<你的用户名>/light-of-shadow.git
cd light-of-shadow
git remote add upstream https://github.com/flypiggpt/light-of-shadow.git
git remote -v
# origin   = 你的 Fork（push 用）
# upstream = 主仓库（只 fetch，不要 push）
```

## 2. 开工

```bash
git checkout main
git fetch upstream
git merge upstream/main
git push origin main          # 同步自己的 Fork
git checkout -b feature/做了什么
```

分支名：`feature/xxx`、`fix/xxx`、`docs/xxx`，中文或英文，一眼能看懂在做什么。

## 3. 提交

```bash
git status                    # 先看改了什么
git add 具体文件               # 禁止 git add -A / git add .
git commit -m "中文一句话：做了什么 / 为什么"
```

规则：
- **一次提交只做一件事**（功能 / 文档 / 配置分开）
- **不要**提交 `application.yaml`、`local.properties`、密钥、`.env`
- **不要**直接在 `main` 上改

## 4. 推送

```bash
git push -u origin feature/做了什么
```

只推 `origin`（你的 Fork）。`git push upstream ...` 会被拒，不要尝试。

## 5. 发 Pull Request

- **base**：`flypiggpt/light-of-shadow` → `main`
- **compare**：`<你的用户名>:feature/做了什么`
- 标题 = 做了什么；描述固定三段：

```markdown
## 做了什么
## 为什么
## 怎么测的
```

跨前后端的接口改动：`docs/api/` + 后端 + 客户端必须在**同一 PR** 里改完。

## 6. 等 review

- 有意见就在 PR 里改：`git add ... && git commit && git push`
- **已 push 的提交不要再 rebase**（会改写历史）
- 要同步主仓库最新改动时：

```bash
git checkout main
git fetch upstream && git merge upstream/main && git push origin main
git checkout feature/做了什么
git merge main
# 解决冲突后 commit，再 git push
```

## 7. 合并后收尾

仓库主合并后，你本地：

```bash
git checkout main
git fetch upstream
git merge upstream/main
git push origin main
git branch -d feature/做了什么
git push origin --delete feature/做了什么
```

---

## 硬性禁止

| 禁止 | 原因 |
|---|---|
| 推 `upstream` / 推主仓库 `main` | 无权限，且 main 有分支保护 |
| `git add -A` | 易带入密钥与无关文件 |
| `git push --force`（尤其对共享分支） | 会抹掉别人提交 |
| 在 `main` 上直接开发 | 一律走 `feature/fix/docs` 分支 |
| 提交密钥 / `application.yaml` / `local.properties` | 安全红线 |

## 冲突怎么办

1. 先同步 `upstream/main` 合进自己分支（见 §6）
2. 打开冲突文件，删掉 `<<<<<<<` / `=======` / `>>>>>>>`，合成一份正确结果
3. `git add 冲突文件 && git commit`（或 `git rebase --continue`）
4. 想放弃本次同步：`git merge --abort` / `git rebase --abort`

## 提交信息示例

| ✅ | ❌ |
|---|---|
| 商城商品列表增加关键词搜索 | update |
| 修复登录 401 后未清除 token 的问题 | fix bug |
| 导购接口补充 recommendedAt 字段 | 改了一些东西 |
