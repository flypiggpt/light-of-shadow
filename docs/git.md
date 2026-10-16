# Git 使用指南（双人开发）

本仓库是 monorepo（前后端 + 文档同仓），两人协作时按本文流程走。目标只有两条：

1. **`main` 永远可运行**——任何人随时拉下来都能编译、能测。
2. **任何时刻都知道对方在干什么**——靠分支命名、提交信息和 PR 描述，不靠口口相传。

---

## 1. 角色与分工

| | 建议 | 说明 |
|---|---|---|
| 默认分工 | 一人偏后端，一人偏客户端 | 不是死板的「只许改自己的目录」，跨目录改完知会一声 |
| 共同责任 | `docs/` | 接口契约两人共管，改动必须走 PR，对方看过再合 |
| 仓库权限 | **仓库主**（owner）有推/合权限；**协作者不加 Collaborator** | 协作者走 **Fork + PR**，见 [§11 Fork 协作完整流程](#10-fork-协作完整流程外部贡献者--无-collaborator-权限) |

> **权限模型说明**：本仓库 `main` 已开分支保护（必须 PR、禁止 force push、admin 同样受限）。
> - **仓库主**：在本仓库开分支、推分支、点 Merge。
> - **协作者（无 Collaborator）**：Fork 到自己账号 → 在 Fork 里推分支 → 向主仓库发 PR；**不能**直接推主仓库，也**不能**自己点合并（由仓库主 Merge）。
> - 以后若升级为 Collaborator（Write），流程变为双方都能推分支/合 PR，分支规则不变。

**原则**：目录不是墙。后端改了响应字段就顺手改客户端调用；客户端缺接口就直接改 `docs/api/` 再实现。只是**改到对方主战场时，在 PR 描述里写清楚**。

---

## 2. 分支模型

```
main  ──●──────●──────●──────●──→   只存已测过、可运行的版本
         \    /        \    /
          ●──●          ●──●        feature/xxx 合并进来
```

| 分支 | 谁建 | 存在多久 | 能不能直接推 |
|---|---|---|---|
| `main` | — | 永久 | ❌ 一律走 PR |
| `feature/xxx` | 任何人 | 开发期间 | ✅ 可反复推自己的分支 |
| `fix/xxx` | 任何人 | 修 bug 期间 | ✅ |
| `docs/xxx` | 任何人 | 纯文档改动 | ✅ |

### 命名

- 中文或英文都行，**一眼能看出在做什么**：
  - `feature/商城搜索`
  - `feature/shop-search`
  - `fix/登录401未清token`
  - `docs/导购接口补充推荐字段`
- 不要用 `dev`、`tmp`、`test1`、`临时` 这种名字。

### 建分支前必做

**仓库主（直接在主仓库开发）：**

```bash
git checkout main
git pull                 # 确保 main 是最新的
git checkout -b feature/商城搜索
```

**协作者（Fork 流程）：** 先把上游 `main` 同步进自己的 Fork，再开分支，见 [§11](#10-fork-协作完整流程外部贡献者--无-collaborator-权限)。

---

## 3. 日常开发循环

两人各自在自己的分支上干活，节奏一样：

```bash
# 1. 开工：建分支（或切回未完成的分支）
git checkout main && git pull
git checkout -b feature/商城搜索

# 2. 写代码，随手小步提交
git status                          # 先看改了啥
git add <具体文件>                   # 不要用 git add -A / git add .
git commit -m "商城商品列表增加关键词搜索"

# 3. 隔天继续之前，先同步远端
git checkout main && git pull
git checkout feature/商城搜索
git rebase main                     # 或 git merge main，见 §5
# 解决冲突（若有），再继续开发

# 4. 自测通过后推分支
git push -u origin feature/商城搜索

# 5. 开 PR → 对方 review → 合并 → 删分支
```

### 提交信息

**中文，一句话，说清楚「做了什么 / 为什么」。** 不用模板格式。

| ✅ 好 | ❌ 不好 |
|---|---|
| 修复登录 401 后未清除 token 的问题 | update |
| 商城商品列表增加关键词搜索 | 改了一些东西 |
| 导购接口补充 recommendedAt 字段 | fix bug |
| 拆分 ShopScreen，购物车抽成独立组件 | refactor |

**一次提交只做一件事。** 功能、文档、配置分开提交。发现手滑混在一起了：

```bash
git reset --soft HEAD~1    # 撤销提交，保留改动
git add A文件 && git commit -m "..."
git add B文件 && git commit -m "..."
```

### 不要 `git add -A`

敏感文件（`application.yaml`、`local.properties`、密钥）虽在 `.gitignore`，但**手滑 force-add 或临时生成的文件**仍可能进库。永远：

```bash
git status          # 看清单
git add 明确的文件    # 一个一个加
git status          # 再确认一遍 staged 列表
```

---

## 4. 合并方式：用 PR，不要直接 push main

GitHub 上开 Pull Request，好处是：改动可讨论、可回溯、能挡住把 main 搞挂的提交。

### 流程（仓库主：同仓库分支）

```
git push -u origin feature/商城搜索
        │
        ▼
  GitHub 开 PR：feature/商城搜索 → main
        │
        ▼
  协作者 review（看代码 + 本地拉下来跑一下）
        │
        ▼
  仓库主点「Squash and merge」
        │
        ▼
  删远程分支 + 删本地分支
```

### 流程（协作者：Fork 分支 → 主仓库 PR）

完整命令见 [§11](#10-fork-协作完整流程外部贡献者--无-collaborator-权限)。骨架：

```
在自己的 Fork 里推 feature 分支
        │
        ▼
  GitHub 开 PR：你Fork的:feature/xxx → flypiggpt/light-of-shadow:main
        │
        ▼
  仓库主 review + Merge（协作者不能自己合）
        │
        ▼
  删自己 Fork 上的分支 + 删本地分支
```

### PR 里写什么

标题就是分支做的事。描述最少三行：

```markdown
## 做了什么
商城商品列表支持按关键词搜索

## 为什么
商品多了以后只能翻页，找不到东西

## 怎么测的
1. 打开商城页，输入「耳机」→ 只显示耳机
2. 清空关键词 → 恢复完整列表
3. 后端接口对着 docs/api/shop_api.md 核对过
```

### Review 怎么做（被请的人）

1. **拉下来跑一遍**，别只看 diff：
   ```bash
   # 对方分支在主仓库（Collaborator 模式）
   git fetch origin
   git checkout -b review/xxx origin/feature/商城搜索

   # 对方分支在他的 Fork（Fork 模式）
   git fetch origin pull/123/head:pr-123
   git checkout pr-123
   # 或：git fetch https://github.com/<对方用户名>/light-of-shadow.git feature/商城搜索:pr-123
   ```
2. 看三件事：能不能跑、有没有破坏现有功能、敏感文件有没有混进来。
3. 意见用 **Comment** 提；小问题可以直接说「可以合了」。
4. 有疑问就 **Request changes**，别勉强合。

### 合并按钮怎么选

| 方式 | 什么时候用 |
|---|---|
| **Squash and merge** | 分支上有很多「改一下」「再改一下」的碎提交 → 合成一个干净提交 |
| **Create a merge commit** | 分支上提交本来就干净、想保留过程 |
| Rebase and merge | 两人共享的分支不要用（会改写历史） |

**推荐默认：Squash and merge。** 两人项目用它最省心。

合并后：

```bash
git checkout main
git pull
git branch -d feature/商城搜索
git push origin --delete feature/商城搜索   # 删远程分支（GitHub 通常也会提示）
```

---

## 5. 同步对方的改动

### rebase 还是 merge？

| 场景 | 用哪个 | 命令 |
|---|---|---|
| 本地提交**还没推**给任何人 | `rebase` | `git rebase main` |
| 本地分支**已经推**了、或对方也在用 | `merge` | `git merge main` |

`rebase` 的优点是历史一条直线好看；代价是**改写提交**。所以铁律是：

> **已经 push 出去的提交，不要再 rebase。**

### 建议节奏

| 时机 | 动作 |
|---|---|
| 每天开工前 | `git pull` |
| 要开 PR 前 | 把 `main` 合进自己分支 / rebase 到 main |
| 对方说「我合了 XX」 | `git pull` |
| 长期分支（超过 2 天） | 每天至少同步一次 `main`，避免最后冲突堆成山 |

```bash
# 完整同步示例（分支还没 push 时）
git fetch origin
git rebase origin/main
```

---

## 6. 冲突怎么解决

冲突不可怕，拖着不解决才可怕。**谁后合 PR 谁解冲突**，但要主动、快。

### 什么时候容易冲突

| 文件 | 原因 | 预防 |
|---|---|---|
| `docs/api/*.md` | 两人都在改接口文档 | 改前先 `git pull`；接口变更在 PR 里说一声 |
| `pom.xml` / `libs.versions.toml` | 两人都加依赖 | 尽量分开 PR 合；加依赖单独提交 |
| 同一个 `.kt` / `.java` | 都在改同一功能 | 开工前打个招呼「我正在改 ShopScreen」 |
| `README.md` | 都在补说明 | 小改不动大结构 |

### 解决步骤

```bash
git status                 # 看哪些文件冲突（both modified）
```

打开冲突文件，找 `<<<<<<<` / `=======` / `>>>>>>>` 标记：

```kotlin
<<<<<<< HEAD
    val title = "搜索"
=======
    val title = "商品搜索"
>>>>>>> feature/xxx
```

1. 读懂两边各自想干什么，**改成一份最终结果**（不是机械二选一）。
2. 删掉所有 `<<<<<<<`、`=======`、`>>>>>>>`。
3. 确认文件能编译 / 文档读得通。
4. 标记为已解决并继续：

```bash
git add 冲突文件
git rebase --continue      # rebase 流程
# 或
git commit                 # merge 流程
```

想放弃这次同步重新来过：

```bash
git rebase --abort         # rebase
git merge --abort          # merge
```

### 文档冲突的额外约定

- **API 契约以实现为准**：如果代码已经这样写了，文档改成和代码一致。
- **语义冲突**（没有 git 冲突但内容矛盾）也算冲突，PR 里对着 `docs/api/` 核对一遍。

---

## 7. monorepo 特有约定

本仓三块内容，提交时**不要混在一个 commit 里**：

```
docs/                        → docs/xxx 提交
light-of-shadow-backend/     → 功能/修复提交
light-of-shadow-client/      → 功能/修复提交
```

### 跨前后端的接口改动（最常见的一起改场景）

规则：**文档 + 服务端 + 客户端，同一 PR 一起走。**

```
feature/订单增加取消接口
├── docs/api/shop_api.md                          ← 先改契约
├── light-of-shadow-backend/.../ShopOrder*.java   ← 再改实现
└── light-of-shadow-client/.../ApiClient.kt       ← 最后改调用
```

PR 描述里贴上：接口路径、请求/响应示例、客户端哪里用到。这样 review 的人不用去猜。

### 一人改前端、一人改后端时

各干各的，互不挡路。只有一种情况会碰上：**都在改 `docs/api/`**。开干前群里说一声「我今天动 shop_api」，或开 PR 后尽快合，减少窗口期。

---

## 8. 禁止事项（踩过的坑）

| ❌ 别做 | ✅ 改成 |
|---|---|
| 直接在 `main` 上提交 | 开 `feature/` / `fix/` 分支走 PR |
| `git push --force` 到 `main` | 永远不要。出事了用新提交修复 |
| `git push --force` 到**对方也在用**的分支 | 只在自己的、只属于自己一个人的分支上 force（且尽量用 `--force-with-lease`） |
| `git add .` / `git add -A` | `git status` 后按文件 add |
| 提交 `application.yaml`、`local.properties`、密钥 | 永远不提交；`.gitignore` 已挡，但自己也要看一眼 |
| 大改 3 天不合 PR | 至少每天同步 `main`，两天内合 |
| commit 写 `update` / `改了点东西` | 中文一句话，说清做什么 / 为什么 |
| 强行合并有冲突、且**没跑过就合进 main** | 解决冲突 → 编译 → 自测 → 再合 |

---

## 9. 常用命令速查

```bash
# 开工
git checkout main && git pull
git checkout -b feature/xxx

# 提交
git status
git add 路径/文件
git commit -m "中文一句话说明"

# 同步 main（分支未 push 时用 rebase）
git fetch origin
git rebase origin/main

# 推分支 / 开 PR
git push -u origin feature/xxx

# 合并后收尾
git checkout main && git pull
git branch -d feature/xxx

# Fork 模式（协作者）额外记住
git remote -v                   # origin=你的 Fork，upstream=主仓库
git fetch upstream
git checkout main && git merge upstream/main && git push

# 出事了
git status                      # 先看现在什么状态
git log --oneline -10           # 再看最近提交
git rebase --abort              # 取消进行中的 rebase
git merge --abort               # 取消进行中的 merge
git restore 路径/文件            # 丢弃工作区未提交的改动（危险，先确认没要的）
```

---

## 10. Fork 协作完整流程（外部贡献者 / 无 Collaborator 权限）

适用于：**没有主仓库推送权限**的人（当前协作者即按此流程）。他的一切改动发生在自己的 Fork 里，通过 PR 汇入主仓库。

主仓库：`https://github.com/flypiggpt/light-of-shadow`（下文称 **upstream**）

### 10.1 一次性准备（只做一遍）

**① 在 GitHub 网页上 Fork**

打开主仓库 → 右上角 **Fork** → 选自己账号 → Create fork。  
得到 `https://github.com/<你的用户名>/light-of-shadow`。

**② 克隆自己的 Fork（不是克隆主仓库！）**

```bash
git clone https://github.com/<你的用户名>/light-of-shadow.git
cd light-of-shadow
```

**③ 把主仓库加为 `upstream`，并确认两个 remote**

```bash
git remote add upstream https://github.com/flypiggpt/light-of-shadow.git
git remote -v
```

应看到：

```
origin    https://github.com/<你的用户名>/light-of-shadow.git  (fetch/push)   ← 你的 Fork
upstream  https://github.com/flypiggpt/light-of-shadow.git     (fetch/push)   ← 主仓库
```

| remote | 是谁 | 用来干什么 |
|---|---|---|
| `origin` | 你的 Fork | `git push` 推你的分支 |
| `upstream` | 主仓库 `flypiggpt/light-of-shadow` | `git fetch` 拉最新代码；**不要往这里 push**（你也没有权限） |

> 若用 SSH：把 URL 换成 `git@github.com:<你>/light-of-shadow.git` 与 `git@github.com:flypiggpt/light-of-shadow.git`。本项目 GitHub SSH 走 443，见根 README / 环境说明。

**④（可选）保持 main 与上游一致**

```bash
git checkout main
git fetch upstream
git merge upstream/main
git push origin main          # 把同步后的 main 也推回自己的 Fork
```

### 10.2 日常开工：同步上游，再开分支

**每次开始做新事情之前**，先让本地 `main` 跟上主仓库：

```bash
git checkout main
git fetch upstream
git merge upstream/main       # 或 git pull upstream main
git push origin main          # 同步到自己的 Fork（可选，但推荐）

git checkout -b feature/商城搜索
```

### 10.3 开发与提交

和普通流程完全一样：

```bash
git status
git add 具体文件
git commit -m "商城商品列表增加关键词搜索"
```

小步提交、中文说明、不要 `git add -A`，见 [§3](#3-日常开发循环)。

### 10.4 推送到自己的 Fork（不是主仓库）

```bash
git push -u origin feature/商城搜索
```

- `origin` = 你的 Fork，**有权限**，随便推。
- `git push upstream ...` = 试图推主仓库，**会被拒绝**（403）——这是预期，别硬来。

同一分支可以反复 push 更新：

```bash
git add ...
git commit -m "根据 review 意见调整分页参数"
git push                      # 已跟踪同一远程分支时直接 push
```

### 10.5 发起 Pull Request（Fork → 主仓库）

1. 打开**你的 Fork** 页面：`https://github.com/<你的用户名>/light-of-shadow`
2. GitHub 通常会黄条提示 **Compare & pull request**；点它  
   或手动：**Pull requests → New pull request**
3. 确认方向（最关键，别选反了）：

   | | base repository | base | compare |
   |---|---|---|---|
   | ✅ 正确 | `flypiggpt/light-of-shadow` | `main` | `<你的用户名>:feature/商城搜索` |
   | ❌ 反了 | 你的 Fork | `main` | … |

4. 标题 = 分支做的事；描述按模板写：

   ```markdown
   ## 做了什么
   商城商品列表支持按关键词搜索

   ## 为什么
   商品多了以后只能翻页，找不到东西

   ## 怎么测的
   1. 打开商城页，输入「耳机」→ 只显示耳机
   2. 清空关键词 → 恢复完整列表
   3. 后端接口对着 docs/api/shop_api.md 核对过
   ```

5. **Create pull request**。

### 10.6 仓库主如何 review + 合并

**网页上：**

1. Files changed 里看 diff，不懂就 Comment。
2. 建议本地真跑一遍（拉 PR 分支）：

   ```bash
   git fetch origin pull/<PR编号>/head:pr-<PR编号>
   git checkout pr-<PR编号>
   # 编译 / 真机测
   ```

3. 合格就 **Review → Approve**（若规则要求），然后 **Squash and merge**。
4. 合并后 GitHub 提示删除分支 → 点删除（删的是 Fork 上的分支，由系统代删或提示作者删）。

**命令行拉 PR（仓库主）：**

```bash
gh pr checkout <PR编号>        # 有 gh 时最省事
# 或
git fetch origin pull/<PR编号>/head:pr-<PR编号>
git checkout pr-<PR编号>
```

### 10.7 合并后，协作者收尾

```bash
git checkout main
git fetch upstream
git merge upstream/main       # 拿到刚合进主仓库的改动
git push origin main          # 更新自己的 Fork
git branch -d feature/商城搜索
git push origin --delete feature/商城搜索   # 删 Fork 上的分支（可选）
```

### 10.8 改到一半发现主仓库已前进（同步冲突）

```bash
git checkout main
git fetch upstream && git merge upstream/main && git push origin main

git checkout feature/商城搜索
git merge main                # 已 push 给别人看时用 merge；见 §5
# 解决冲突（§6）→ commit → git push
```

### 10.9 Fork 流程最容易犯的错

| ❌ 错误 | 后果 | ✅ 正确做法 |
|---|---|---|
| `git clone` 了主仓库，然后 `git push` | 403，没权限 | clone **自己的 Fork**，`upstream` 指主仓库 |
| `git push upstream feature/xxx` | 403 | 推自己的 `origin` |
| PR 的 base 选成了自己的仓库 | PR 开到了自己 Fork 里，主仓库看不见 | base = `flypiggpt/light-of-shadow:main` |
| 从不 `fetch upstream`，Fork 越用越旧 | 冲突巨大，PR 无法合并 | 每次开工前先同步 `main` |
| 在 Fork 的 `main` 上直接改 | 污染自己的 main，也不好提 PR | 永远开 `feature/` `fix/` 分支 |
| PR 开很久不更新 | 对方难 review，冲突增多 | 有新改动就 push 更新分支，或在 PR 里留言说明 |

### 10.10 Fork 模式 vs Collaborator 模式

| | Fork + PR（当前） | Collaborator（Write） |
|---|---|---|
| 推分支到主仓库 | ❌ | ✅ |
| 推自己 Fork | ✅ | 不需要 Fork |
| 开 PR | Fork → 主仓库 | 同仓库分支 → `main` |
| 自己点 Merge | ❌（仓库主合） | ✅（分支保护仍要求走 PR） |
| 适合 | 外援、不交钥匙、责任边界清晰 | 长期固定搭档、互合 PR |

两种模式的**分支命名、提交纪律、PR 描述、冲突处理完全相同**，只有 remote 和谁能点 Merge 不同。

---

## 11. 两人协作 checklist

**每次开工**

- [ ] `git checkout main && git pull`（Fork 流程先 `git fetch upstream` 再合并，见 [§11.2](#102-日常开工同步上游再开分支)）
- [ ] 知道对方今天在动哪块

**每次提交**

- [ ] `git status` 看过 staged 列表
- [ ] 一次提交只做一件事
- [ ] 中文提交信息

**每次开 PR**

- [ ] 已把 `main` 合进自己分支 / rebase 过
- [ ] 本地编译 + 自测通过
- [ ] 跨前后端改动带上了 `docs/api/`
- [ ] PR 描述写了「做了什么 / 为什么 / 怎么测的」
- [ ] （Fork 流程）PR 方向确认：`flypiggpt/light-of-shadow:main` ← 你的分支

**每次 review**

- [ ] 拉下来实际跑过
- [ ] 没有敏感文件
- [ ] 和现有功能不打架

**每次合并后**

- [ ] 删掉已合并的分支（本地 + Fork 远程）
- [ ] 各自同步主仓库 `main`（Fork 流程见 [§11.7](#107-合并后协作者收尾)）
