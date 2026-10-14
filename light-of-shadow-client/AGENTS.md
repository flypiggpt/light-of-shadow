# AGENTS: How to be productive in this repository

Checklist for a new AI coding agent (follow these before editing code):
- Read `README.md`, `../docs/api/*.md`, and `../docs/style.md` for product intent and integration rules
- Open `app/src/main/java/com/yan/client` to see packages: `ui`, `vision`, `audio`, `network`, and `theme`
- Run a local build (`.\gradlew.bat :app:assembleDebug`) before making behavior changes

Quick architecture (the big picture)
- Single-module Android app (Compose + Material3). Entry: `MainActivity.kt` which drives UI routing via an enum `AuthRoute` (no navigation-component used).
- UI lives under `app/src/main/java/com/yan/client/ui/*` (screens grouped by feature: `login`, `main`, `guide`, `theme`). Follow the existing pattern: one composable screen per file.
- **Backend integration**: `app/src/main/java/com/yan/client/network/ApiClient.kt` handles HTTP communication with `http://132.232.75.62:8080/api`. `TokenStore.kt` manages `accessToken`, `refreshToken`, and `expiresIn`. Login/Register/ForgotPassword screens call backend APIs and cache tokens.
- Vision pipeline: `app/src/main/java/com/yan/client/vision/ObstacleDetector.kt` — runs a local TFLite model from `app/src/main/assets/model.tflite`. Input size = 320, output rows = 300, output cols = 6; threshold = 0.45. Labels read from `app/src/main/assets/labels.txt`.
- Audio/TTS: `app/src/main/java/com/yan/client/audio/TtsAlertManager.kt` (primary), plus `OfflineTtsManager.kt`. TTS exposes helpers: `isReady()`, `getInitializationError()`, `speakIfAllowed(...)`, `speakNow(...)` and uses tag `TtsAlertManager` for logs.

Critical developer workflows (commands & shortcuts)
- Build debug APK (Windows PowerShell):
  .\gradlew.bat :app:assembleDebug
- Compile only Kotlin/Compose (fast check):
  .\gradlew.bat :app:compileDebugKotlin
- Run unit tests:
  .\gradlew.bat test
- Run instrumented tests (requires device/emulator):
  .\gradlew.bat :app:connectedAndroidTest
- Install produced APK, tail logs, and debug API calls:
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb logcat | findstr ApiClient
  adb logcat | findstr TtsAlertManager
  adb logcat | findstr ObstacleDetector

Project-specific conventions & important patterns
- UI navigation is intentionally lightweight: `MainActivity` switches screens by setting `route` (see `AuthRoute` enum). When adding screens, match this enum-driven pattern or refactor carefully.
- Backend API patterns: RESTful design with standard HTTP methods (GET, POST, PUT, DELETE, PATCH). Response format is consistent: `{code, message, data}`. Errors returned with code != 0; code 401 indicates unauthorized/invalid credentials. Use `ApiClient.get()`, `ApiClient.post()`, `ApiClient.put()`, `ApiClient.patch()`, or `ApiClient.delete()` depending on operation.
- Token management: after successful login, store `accessToken` in `TokenStore.accessToken` for use in subsequent authenticated requests. All API methods automatically include `Authorization: Bearer <token>` when calling `getAuthHeaders()`. Currently in-memory only; migrate to DataStore/SharedPreferences for persistence.
- Vision detector assumptions: model outputs normalized coordinates for a 320x320 image; detection code expects rows of [x1,y1,x2,y2,score,classIndex]. Changing model format requires updating `ObstacleDetector.bitmapToBuffer`, output parsing, and label index handling.
- TTS behavior includes built-in cooldowns: see `speakIfAllowed(key, text, cooldownMs, globalCooldownMs)` — avoid triggering speech with high frequency in tests.
- The app deliberately translates labels for display using `LABEL_TRANSLATIONS` in `ObstacleDetector.kt`. Preserve or update this map when changing labels.
- Styling: Neumorphism rules are authoritative in `../docs/style.md`. The codebase uses `ClientTheme` + custom colors in `ui.theme` — prefer reusing existing surfaces `NeumorphicSurface`/theme colors rather than adding new color variants.

Integration & external dependencies to be aware of
- Network: INTERNET permission is required (already in AndroidManifest.xml). `ApiClient` uses standard `HttpURLConnection` with 10-second timeouts. All API calls block the thread and must run in `Dispatchers.IO` via coroutines. Supports full REST: GET, POST, PUT, PATCH, DELETE.
- CameraX is used (preview + analysis) in `GuideScreen.kt` — image analysis pipeline calls `ObstacleDetector.detect(imageProxy)` and closes `imageProxy` in a finally block.
- TensorFlow Lite Interpreter is used (org.tensorflow.lite.Interpreter). Large model changes may require adjusting `Interpreter.Options()` threads and memory usage.
- Coroutines are used throughout (Kotlin 1.7.3, kotlinx-coroutines-android 1.7.3) for async operations, especially in `LoginScreen.kt` and `AuthFlowScreens.kt` where API calls must not block the UI thread.

Fast examples for common modifications
- Add a new REST endpoint: extend `ApiClient.kt` with a new method using `get()`, `post()`, `put()`, `patch()`, or `delete()`. Example: `fun getUserProfile(): ApiResponse = get("/api/user/profile", getAuthHeaders())`
- Handle 401 Unauthorized in API responses: clear `TokenStore.accessToken`, optionally call `ApiClient.refresh()` to attempt token refresh, or navigate back to login screen if refresh fails.
- Add automatic Bearer token to all authenticated requests: all methods automatically use `getAuthHeaders()` which returns `Authorization: Bearer <token>` if `TokenStore.accessToken` is set.
- Migrate TokenStore to persistent storage: create new class using Android DataStore (or SharedPreferences), update login flows to save/load tokens from it.
- Add a new translated label: update `LABEL_TRANSLATIONS` in `ObstacleDetector.kt` and `assets/labels.txt` (preserve index mapping).
- Tune detection sensitivity: change SCORE_THRESHOLD in `ObstacleDetector.kt` (default 0.45) and run real-device tests.

Key files to open first
- `README.md` (project intent & run commands)
- `../docs/api/auth_api.md` (auth backend contract)
- `../docs/api/shop_api.md` / `../docs/api/shoppingguide_api.md` / `../docs/api/merchant_api.md` (feature backend contracts and client integration examples)
- `../docs/style.md` (visual rules)
- `app/src/main/java/com/yan/client/network/ApiClient.kt` (HTTP client & all REST methods)
- `app/src/main/java/com/yan/client/ui/login/LoginScreen.kt` (login flow implementation)
- `app/src/main/java/com/yan/client/ui/login/AuthFlowScreens.kt` (register & forgot-password flows)
- `app/src/main/java/com/yan/client/vision/ObstacleDetector.kt` (vision pipeline)
- `app/src/main/java/com/yan/client/audio/TtsAlertManager.kt` (TTS lifecycle & logs)
- `app/src/main/java/com/yan/client/ui/guide/GuideScreen.kt` (camera & analyzer integration)

If you change runtime behavior, follow this quick validation loop
1. Build: .\gradlew.bat :app:assembleDebug
2. Install: adb install -r app/build/outputs/apk/debug/app-debug.apk
3. Reproduce on device/emulator; tail logs filtered by tag (Windows):
   adb logcat | findstr ApiClient
   adb logcat | findstr TtsAlertManager
   adb logcat | findstr ObstacleDetector

Limitations and non-discoverable assumptions (call out to maintainers if unclear)
- Token persistence: TokenStore is in-memory only; tokens are lost on app restart. Recommend migrating to DataStore for persistence before production.
- Token refresh: automatic token refresh on 401 is not yet implemented. Consider adding an interceptor or retry logic.
- Request body on DELETE: current implementation does not support body on DELETE requests (REST standard). If needed, modify the `request()` method.
- Distance estimation is intentionally removed from `ObstacleDetector` (see comment). If required, discuss expected UX and units with product owner.

---
Last updated: 2026-05-21

