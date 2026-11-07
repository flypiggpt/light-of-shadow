# Light of Shadow · 音影寻光

**An assistive app for people with visual impairments** — on-device obstacle detection with voice alerts, an accessibility-first shopping mall, and an AI shopping assistant that remembers your budget.

**English** | [简体中文](README.zh-CN.md)

> 📦 **Download the APK**: [Release v0.1.0](https://github.com/flypiggpt/light-of-shadow/releases/tag/v0.1.0)

## Highlights

- **Distance-first on-device perception** — a local TFLite detector names what is ahead (行人/车辆/家具…) and speaks it aloud; the in-research depth pipeline adds metric distance while making sure a detection miss never becomes a missed warning
- **AI shopping assistant with real engineering behind it** — a LangGraph state machine (intent → tool routing → multi-turn clarify → degradation), hybrid RAG retrieval, cross-session user profile memory, and order tools — not a bare LLM chat call
- **Quality gates as first-class citizens** — 129 automated tests, a 40-case golden eval set wired into CI (tool-call accuracy / retrieval hit-rate / answer correctness), and regression gates that block merges on quality drops
- **Accessibility throughout** — TTS announcements for guide alerts, assistant replies, and checkout flows; TalkBack semantics on interactive controls

## Architecture

Three independently deployable pieces — a deliberate split so the AI engineering story is not buried inside a web backend:

```
┌────────────────────┐        ┌──────────────────────┐        ┌──────────────────────┐
│   Android client   │  HTTP  │   Java backend        │  HTTP  │   Python Agent        │
│ Kotlin + Compose   │ ─────► │ Spring Boot monolith  │ ─────► │ FastAPI + LangGraph   │
│ CameraX + TFLite   │        │ auth · shop · merchant│        │ tools · RAG · eval    │
└────────────────────┘        └──────────┬───────────┘        └──────────┬───────────┘
        │  on-device, no cloud            │ MySQL · MinIO                 │ Redis
        ▼                                 ▼                               ▼
   obstacle detection + TTS          order / product data          checkpoint · profile
```

- The **client** runs perception fully on-device; the mall talks to the Java backend
- The **Java backend** is a modular monolith (no Spring Cloud — the scale does not justify it) and acts as the gateway to the Agent, injecting user identity and persisting conversation history
- The **Python Agent** owns all intelligence: a LangGraph state machine with nine business tools, hybrid retrieval (vector + BM25 + RRF rerank), cross-session user memory, and an eval/observability stack (Langfuse traces, Prometheus metrics, golden-set regression)

## Features

### Guide (client)
- Real-time obstacle detection with a local TFLite model (COCO classes, Chinese labels)
- Voice alerts with direction, cooldown throttling and rate limiting — no per-frame spam
- TTS readouts for assistant replies and operation results (configurable)

### Shopping mall (client + backend)
- Product browsing & keyword search · cart · checkout · mock payment · cancel · delete · address book
- Merchant web admin (`light-of-shadow-merchant/`, Vue3 + Element Plus): product CRUD + image upload (MinIO presigned URLs)

### AI shopping assistant (Agent)
- **Intent state machine** (LangGraph): 12 intents, tool routing, bounded clarify loops, graceful degradation
- **Memory preferences**: remembers budget / preferences / categories / recent purchases across sessions; repeat questions stop being asked
- **Order tools**: query / track / cancel / delete orders in natural language (write ops require the order id — the agent asks for it)
- **RAG FAQ**: hybrid retrieval over a curated corpus; facts come from retrieval, not from model memory
- **Quality**: 40-case golden set evaluated on real routing (temperature 0), with CI gates

## Repository layout

```
light-of-shadow/
├── light-of-shadow-client/     # Android app (Kotlin + Compose + CameraX + TFLite)
├── light-of-shadow-backend/    # Java 21 · Spring Boot 3 · MyBatis
├── light-of-shadow-merchant/   # Merchant web admin (Vue3 + Element Plus)
├── agent-service/              # Python Agent (FastAPI + LangGraph + RAG + eval)
├── tools/                      # Model export scripts (Depth Anything → TFLite)
├── docs/                       # API contracts · DB schema · Git & style guides
└── .github/workflows/          # Quality gates: agent-quality · backend-quality
```

## Quick start

### Android client

```bash
cd light-of-shadow-client
./gradlew :app:assembleDebug          # or .\gradlew.bat on Windows
bash install-debug.sh                 # build + install + launch (adb)
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. The app points at the demo server by default; override in `local.properties` with `api.base.url=...` for local backends.

### Java backend

```bash
cd light-of-shadow-backend
# 1. copy src/main/resources/application-example.yaml → application.yaml and fill in env
# 2. schema: docs/db/mysql_*.sql against a MySQL 8 instance
mvn test                              # 33 tests, runs on embedded H2 — no external deps
mvn package && java -jar target/light-of-shadow-backend-*.jar
```

Deploy helper: `./deploy-remote.sh deploy` (rsync + build + restart on a remote host).

### Python Agent

```bash
cd agent-service
cp .env.example .env                  # fill AGENT_LLM_API_KEY (DeepSeek)
docker compose up -d --build          # app + redis on :8000
```

Or locally: `pip install -e ".[dev]"` then `uvicorn app.main:app`.

### Merchant web

```bash
cd light-of-shadow-merchant
npm install && npm run dev            # Vue3 + Element Plus
```

## Demo & test accounts

The Android client ships preconfigured against a public demo server (`132.232.75.62`).

| Role | Account | Password |
|---|---|---|
| Merchant | `shopowner` | `shop123456` |
| User | `yan` | `yan` |

## Testing & quality

| Layer | Suite | Runs where |
|---|---|---|
| Agent unit + HTTP | 95 tests + 1 LLM-gated | `pytest tests/` |
| Golden eval | 40 cases: retrieval / tool-call / answer | `python tests/golden/run_eval.py` |
| Java | 33 tests (H2, offline) | `mvn test` |
| CI gates | `agent-quality` · `backend-quality` | every PR |

The golden eval runs **real intent routing** (temperature 0) and is gated by a baseline — if quality drops below the floor, the CI job goes red and the PR cannot merge. Eval reports are attached as CI artifacts.

## API documentation

All contracts live under [`docs/api/`](docs/api/) (Chinese):

- [auth_api.md](docs/api/auth_api.md) — login / register / token refresh & rotation
- [shop_api.md](docs/api/shop_api.md) — products / cart / orders / addresses
- [shoppingguide_api.md](docs/api/shoppingguide_api.md) — AI assistant (sessions / messages / profile)
- [merchant_api.md](docs/api/merchant_api.md) — merchant product management

## Known limitations

Honest list — this is a portfolio project, not a production service:

- **Mock payment** — no real payment or logistics
- **Debug-signed APK** — allow "unknown sources" to install; release signing planned
- **APK size ~105MB** — TFLite native libs for 3 ABIs + unminified debug dex; ABI split / R8 planned
- **minSdk 36** (Android 16+) — currently validated on the dev device only
- Verification codes run in dev mode (logged, not emailed) unless SMTP is configured

## Roadmap

- **On-device metric distance** (research branch, shelved): a Depth-Anything-V2 + detector pipeline where depth discovers obstacles with meter-level distance and the detector only labels them — a missed label never becomes a missed warning. Desktop-free, directly on-device; 51 unit tests already green on the feature branch
- Release signing + APK size optimization
- Accessibility S4: structured voice summaries

## License & contributing

Apache 2.0 · [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md)
Git workflow and UI style guide: [docs/git.md](docs/git.md) · [docs/style.md](docs/style.md)
