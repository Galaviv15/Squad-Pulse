# SquadPulse

A web platform for managing an adult football club's day-to-day professional operations — squad, tactics, training, and match data — from one place. Hebrew-first (RTL), multi-club from day one.

**Status:** Early scaffolding (Phase 0 of the roadmap below). The backend, frontend and scraper skeletons exist (empty modules, tooling, smoke tests); no feature code has shipped yet.

**Full spec:** [SquadPulse — full technical spec](/docs/spec.md)

**Jira project:** `squadpulse.atlassian.net`, project key **`KAN`**

---

## Overview

SquadPulse is built for a club's technical staff — Club Manager, Head Coach, and specialist staff (assistant coach, goalkeeping coach, fitness coach, analyst) — not its fans. Core capabilities: squad management, a drag-and-drop tactical board, a training planner, and match/league data blended from automated scraping and manual entry. See the full spec for the complete picture; this file only covers what's needed to start working in the repo day to day.

## Architecture

One Spring Boot **modular monolith** (not microservices — see spec section 02 for why), talking to MongoDB and Redis, with a separate small Node.js worker for scraping.

```
squadpulse/
├── backend/                          # Spring Boot monolith
│   └── src/main/java/com/squadpulse/
│       ├── auth/                     # JWT, RBAC, users
│       ├── squad/                    # Players, roster
│       ├── tactics/                  # Tactical board (Canvas backend)
│       ├── training/                 # Training calendar & sessions
│       ├── match/                    # League table, fixtures, lineups (domain data)
│       ├── scrapingintegration/      # Talks to the scraper worker, feeds match
│       └── common/                   # Shared: clubId enforcement, error handling, etc.
├── frontend/                         # React 19 + TypeScript + Vite + Tailwind
├── scraper/                          # Node.js worker (Playwright/Cheerio)
├── docker-compose.yml                # MongoDB + Redis, local dev only
└── README.md
```

## Tech stack

| Layer | Choice |
|---|---|
| Frontend | React 19, TypeScript, Vite, Tailwind CSS, shadcn/ui, TanStack Query, Zustand, Konva.js (tactical board), Recharts |
| Backend | Java, Spring Boot (single modular monolith) |
| Database | MongoDB (primary data), Redis (cache, rate limiting, refresh-token blacklist) |
| Scraper | Node.js, Playwright/Cheerio |
| Auth | Stateless JWT (Access + Refresh in HttpOnly cookie), Argon2id password hashing + a pepper (env var, never committed) |
| CI | GitHub Actions (lint, test, build on PRs to `master` and pushes to `master` — no CD yet) |

## Multi-tenancy

Pool model: shared collections across all clubs, every document tagged with `clubId`. Isolation is enforced centrally (a base repository / aspect in the `common` module injects the `clubId` filter automatically) — **this is the single most important thing to check in every PR that touches data access.** Full rationale in spec section 03.

## Language

Hebrew is the primary and only supported UI language at launch (RTL-first, via an i18n library from day one — don't hardcode strings). Exception: football terminology already used in English by Israeli coaches — position codes (`GK`, `CB`, `DM`, ...) and formation notation (`4-3-3`) — stays in English everywhere, including the tactical board. Full detail in spec section 01.

## Security

- JWT: short-lived Access Token + HttpOnly-cookie Refresh Token
- Passwords: Argon2id + pepper (pepper lives only in an env var, never in the DB or in git)
- RBAC: enforced by Permission Level (`ADMIN` / `EDIT_FULL` / `EDIT_PARTIAL` / `VIEW_ONLY`), combined with `clubId` filtering
- CORS restricted, rate limiting via Redis, input validation on every endpoint, Dependabot in CI
- **Nothing secret ever goes into git** — env vars / secrets manager only

## Git & Jira workflow

- **Branches:** `feature/KAN-123-short-desc` / `fix/KAN-124-...` — always include the ticket key.
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) with the ticket key, e.g. `feat(squad): add player creation endpoint (KAN-12)`.
- **PRs:** one ticket = one PR into `master`, even solo — keeps CI as a real gate and leaves a review trail.
- **Jira workflow:** Backlog → To Do → In Progress → In Review → Done. GitHub is connected to Jira, so branches/commits/PRs referencing a ticket key show up automatically on that ticket.

## CI

One workflow, [`.github/workflows/ci.yml`](.github/workflows/ci.yml), runs on PRs targeting `master` and on pushes to `master`. A new push to the same ref cancels the previous in-flight run. Two independent jobs run in parallel:

- **`backend-ci`** — JDK 21 (Temurin): `./mvnw spotless:check`, then `./mvnw verify`.
- **`frontend-ci`** — Node 22: `npm ci`, `npm run lint`, `npm run format:check`, `npm run test`, `npm run build`.

No Docker build, CD, or Mongo/Redis service containers yet (revisit when the first Testcontainers-based tests land in Phase 1). Branch protection on `master` should require both `backend-ci` and `frontend-ci` to pass before merging (GitHub → Settings → Branches).

## Roadmap

| Phase | Goal |
|---|---|
| 0 | Repo, package structure, linters, basic CI, Jira board *(in progress)* |
| 1 | Backend core: auth + a single `Player` entity end to end, with tests from day one |
| 2 | Local env: `docker-compose.yml` (MongoDB + Redis) |
| 3 | Frontend MVP: dashboard + squad table against the real API (first walking skeleton) |
| 4 | Tactical board (Konva.js) |
| 5 | Scraping service (Node worker, manual/Cron trigger — no message queue yet) |
| 6+ | Hardening: full RBAC, multi-club load testing, monitoring, deployment |

## Local development

Prerequisites: **JDK 21**, Node 22.12+ (or 24+), Docker.

1. `cp .env.example .env`, then replace every value with real ones (`.env` is git-ignored). Use long random values for `JWT_SECRET` and `PASSWORD_PEPPER` (at least 32 characters each — the backend refuses to start otherwise).
2. `docker compose up -d` — MongoDB + Redis.
3. Backend: `cd backend && ./mvnw spring-boot:run` (it reads `../.env` automatically). Checks: `./mvnw verify` (tests + formatting; fix formatting with `./mvnw spotless:apply`).
4. Frontend: `cd frontend && npm install && npm run dev`. Checks: `npm run lint`, `npm run format:check`, `npm test`, `npm run build`.

## Working with Claude Code

When you start a new Claude Code session in this repo, point it here first — e.g. *"Read the README, then implement KAN-6."* Jira ticket descriptions carry the specific requirements for each task; this file carries the standing context (architecture, conventions, current phase) so you don't have to re-explain it every session.
