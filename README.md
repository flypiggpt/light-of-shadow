# Light of Shadow (音影寻光)

An assistive app for people with visual impairments: on-device obstacle detection with voice alerts, plus a built-in shop and AI shopping assistant.

**English** | [简体中文](README.zh-CN.md)

> In-depth docs under [`docs/`](docs/) (API contracts, Git workflow, UI style) are currently written in Chinese. This README covers what you need to build and run the project.

## Repository layout

Monorepo — backend, Android client, and docs live in one Git repository:

```
light-of-shadow/
├── docs/                        # API contracts, design, database, guides
├── light-of-shadow-backend/     # Spring Boot 3 + MyBatis backend
└── light-of-shadow-client/      # Android client (Kotlin + Compose)
```

- API contracts: [`docs/api/`](docs/api/)
- UI style guide: [`docs/style.md`](docs/style.md)
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md) · Security: [SECURITY.md](SECURITY.md) · License: [Apache 2.0](LICENSE)

## Getting started

### Android client

From `light-of-shadow-client/`:

```bash
./gradlew :app:assembleDebug          # macOS / Linux / WSL
.\gradlew.bat :app:assembleDebug      # Windows
```

One-shot build + install + launch (WSL):

```bash
./install-debug.sh
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Backend

1. Configure IntelliJ IDEA Deployment (SFTP) and map `light-of-shadow-backend/` to the project path on your cloud server.
2. Upload code changes from IDEA.
3. SSH into the server and run:
   ```bash
   ./deploy.sh
   ```
   The script runs `mvn clean package`, stops the old process, and starts the new jar.
4. Backend base URL: `http://<server-ip>:8080` — the client is pointed at this host.

Config secrets (`application.yaml`, DB credentials, `JWT_SECRET`, `DEEPSEEK_API_KEY`) are supplied via environment variables; see `light-of-shadow-backend/src/main/resources/application-example.yaml`. They are gitignored and must never be committed.

## Git workflow

Full guide (Chinese): [docs/git.md](docs/git.md). Highlights:

1. Never commit directly to `main` — use `feature/` / `fix/` branches and open a Pull Request.
2. Commit messages: one line in Chinese, stating **what / why**. One concern per commit.
3. Do not use `git add -A`. Stage files by name; never commit `application.yaml`, `local.properties`, or secrets.
4. Cross-cutting API changes ship in **one PR**: `docs/api/` + backend + client together.

## Testing

Manual for now: build → deploy/install → verify on a phone or in a browser. Self-test before you open a PR.

## License

Licensed under the [Apache License 2.0](LICENSE).
