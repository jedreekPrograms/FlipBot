# FlipBot — Java Marketplace Automation

A Java/Spring Boot application for monitoring marketplace listings, coordinating browser workers and managing persistent negotiation workflows. The project combines a PostgreSQL-backed API, a React dashboard, a Java Playwright runtime and a mobile companion.

**Status:** active development. The working application is demonstrated below. Some runtime functionality depends on a private module, and the database bootstrap still has limitations described under [Local development](#local-development).

[![CI](https://img.shields.io/github/actions/workflow/status/jedreekPrograms/FlipBot/ci.yml?branch=main&label=CI)](https://github.com/jedreekPrograms/FlipBot/actions/workflows/ci.yml)

## Watch the application

<p align="center">
  <a href="https://www.youtube.com/watch?v=xaNDLMsuKKk">
    <img src="https://img.youtube.com/vi/xaNDLMsuKKk/maxresdefault.jpg" alt="Watch the FlipBot full demo on YouTube" width="900">
  </a>
</p>

<p align="center">
  <strong>▶ <a href="https://www.youtube.com/watch?v=xaNDLMsuKKk">Watch the full 2:34 FlipBot demo on YouTube</a></strong>
</p>

The recording shows the real application: the dashboard, bot configuration, saved listing history, parallel browser workers and runtime telemetry.

| Approx. time | Walkthrough |
| --- | --- |
| 0:05 | Dashboard and operational metrics |
| 0:25 | Bot configuration and negotiation rules |
| 0:50 | Persisted listing and purchase history |
| 1:15 | Live automation runtime |
| 1:40 | Ten headless browser slots |
| 2:05 | Worker states, cooldowns and errors |
| 2:28 | Dashboard updates alongside runtime logs |

## What the system does

- Discovers listings using model filters or search queries and verifies the target before taking action.
- Persists negotiation steps, timing, conversation identity and outcomes so work can continue after a process restart.
- Coordinates multiple workers while serializing jobs for the same bot.
- Uses database-backed ownership, action guards and audit records to control duplicate real actions.
- Plans daily action quotas alongside the capacity already reserved for active negotiations.
- Records market observations and exposes worker state, retry timing and cooldowns in the dashboard.

## Engineering focus

| Problem | Approach |
| --- | --- |
| Two bots find the same listing | Shared marketplace ownership keyed by marketplace and listing ID |
| A worker fails around a real click | Persistent action guards, confirmation checks and audit information |
| Browser UI or availability changes | Live verification, bounded retries and explicit terminal states |
| An account becomes temporarily blocked | Persisted bot-wide cooldown rather than immediate repeated jobs |
| A process restarts | PostgreSQL holds business state; browser contexts are disposable |

A separate market-statistics observer collects read-oriented data without sharing the normal negotiation queue.

## Technology and modules

| Module | Technology and responsibility |
| --- | --- |
| [Backend](backend/) | Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL; application state and APIs |
| [Browser runtime](playwright/) | Java and Playwright; scheduling, discovery and public orchestration |
| [Dashboard](frontend/) | React, TypeScript and Vite; configuration, history and monitoring |
| [Mobile companion](mobile/) | Expo, React Native and WebView; access to the existing dashboard |
| [Local database](docker/) | PostgreSQL 17 through Docker Compose |
| [CI](.github/workflows/ci.yml) | Backend and browser-module tests, frontend checks and mobile type-checking |

Flyway migration files are present, but the checked-in backend currently disables their execution. See the database setup requirements below.

## Local development

### Requirements and boundaries

- Java 21; Maven for the Playwright module.
- Node.js 22+ and npm for frontend development.
- Docker with Compose for the local database.
- Browser execution additionally needs Chromium/Playwright and the authorized private core.

**A fresh clone is not currently a one-command full-runtime demo.** The database schema needs an existing prepared baseline, and the real browser entry point requires access to the private core. The public source and unit tests can be reviewed independently; the video shows the complete running application.

### Database

```shell
cd docker
docker compose up -d
```

Compose creates a local `flipbot` database on `localhost:5433`. The checked-in backend configuration instead points to `flipbot_pr74`, with Hibernate schema creation and Flyway both disabled. Starting the container alone therefore does not initialize a usable application schema.

Use an existing prepared database and explicitly override `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` as needed. Do not apply the historical migration chain to a blank database without first establishing its baseline.

### Backend and dashboard

Set `FLIPBOT_ENCRYPTION_KEY` to a Base64-encoded 32-byte key before starting the backend. One way to generate a development key, if Python is installed, is:

```shell
python -c "import os,base64; print(base64.b64encode(os.urandom(32)).decode())"
```

From `backend/`:

```shell
bash ./mvnw spring-boot:run
```

On Windows, use `.\mvnw.cmd spring-boot:run`. The backend listens on port `8081`.

In a second terminal:

```shell
cd frontend
npm ci
npm run dev
```

The Vite development server proxies `/api` to `http://localhost:8081`.

### Authorized browser runtime

On an authorized Windows machine, from `playwright/`:

```powershell
.\bootstrap-private-core.ps1
```

The script checks out the private `jedreekPrograms/Flipbot-Core` repository into an ignored directory, runs its tests, installs the private Maven artifact and activates the local profile. It uses the machine's configured Git credentials. Reload the Maven project after bootstrapping.

Run `pl.flipbot.playwright.FlipBotPlaywrightApplication` for scheduled multi-bot execution. Without the private core, this entry point deliberately refuses to start.

### Mobile access

See the [mobile guide](mobile/README.md) for the Expo development workflow, standalone APK notes and private Tailscale access to the existing backend.

## Tests

Run these commands from the corresponding module directories:

| Module | Checks |
| --- | --- |
| `backend/` | `bash ./mvnw test`; Windows: `.\mvnw.cmd test` |
| `playwright/` | `mvn test` |
| `frontend/` | `npm ci`, `npm run lint`, `npm run build` |
| `mobile/` | `npm install`, `npm run typecheck` |

Backend tests need PostgreSQL and the encryption key. The [CI workflow](.github/workflows/ci.yml) documents its disposable database configuration: Flyway disabled and Hibernate `create-drop`. Private-core tests run in its own repository.

## Runtime configuration

The scheduler accepts environment variables for worker count and scheduling intervals:

```text
FLIPBOT_WORKER_COUNT
FLIPBOT_SYNC_INTERVAL_SECONDS
FLIPBOT_NEGOTIATION_CHECK_INTERVAL_SECONDS
FLIPBOT_CATALOG_SCAN_INTERVAL_SECONDS
FLIPBOT_PRICE_PROBE_INTERVAL_SECONDS
FLIPBOT_FAILURE_RETRY_SECONDS
FLIPBOT_RATE_LIMIT_RETRY_SECONDS
FLIPBOT_SHUTDOWN_TIMEOUT_SECONDS
FLIPBOT_SCHEDULER_HEADLESS
```

## Current next steps

- Establish a clean Flyway baseline and reproducible empty-database setup.
- Add a public demonstration mode that does not need private browser integration.
- Extend regression coverage and runtime tracing.
- Improve deployment packaging and the screenshot gallery.

## Project history and scope

FlipBot was migrated from VintedOfferTracker. Original pull-request records are preserved in the [history archive](https://github.com/jedreekPrograms/FlipBot/tree/archive/github-pr-history); historical PR numbers in imported messages refer to that archive.

FlipBot is an independent project and is not affiliated with Vinted. Marketplace integration depends on an external website and its rules; the project does not guarantee uninterrupted access.
