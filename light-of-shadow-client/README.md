# 音影寻光 · Android 客户端

这是“音影寻光”项目的 Android Studio 客户端工程，当前首页已实现为新拟态（Neumorphism）风格的登录页。

## 当前内容
- 登录页：账号 / 密码输入
- 操作入口：登录、注册账号、忘记密码
- 主页面：开始导盲按钮、社区占位、登出
- 导盲识别页：本地 YOLOv8n 识别 + 语音报警
- 风格说明：`../docs/style.md`
- 接口文档：`../docs/api/`（`auth_api.md` 登录、`shop_api.md` 商城、`shoppingguide_api.md` 导购、`merchant_api.md` 商家）

## 运行方式
在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

如果只想确认 Kotlin/Compose 是否能编译，也可以执行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

## 导盲识别说明
- 识别模型已打包到 `app/src/main/assets/model.tflite`
- 标签文件在 `app/src/main/assets/labels.txt`
- 点击主页面的“开始导盲”即可进入相机识别页面
- 识别到障碍物后会用语音播报，例如“前方约3米有行人”

## 联调说明
当前前端页面已经预留登录、注册、找回密码的交互入口，后端可依据 `../docs/api/auth_api.md` 中的接口约定进行对接。
