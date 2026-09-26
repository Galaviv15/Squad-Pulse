# SquadPulse

A web platform for managing an adult football club's day-to-day professional operations — squad, tactics, training, and match data — from one place. Hebrew-first (RTL), multi-club from day one.

**Status:** Backend core in progress. The first real endpoints exist: authentication (login / refresh / logout) and inviting users — see [Auth API](#auth-api). No other feature code has shipped yet; the frontend and scraper are still skeletons.

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
| Database | MongoDB (primary data), Redis (refresh-token families today; cache and rate limiting planned) |
| Scraper | Node.js, Playwright/Cheerio |
| Auth | Stateless JWT (Access + Refresh in HttpOnly cookie), Argon2id password hashing + a pepper (env var, never committed) |
| CI | GitHub Actions (lint, test, build on PRs to `master` and pushes to `master` — no CD yet) |

## Multi-tenancy

Pool model: shared collections across all clubs, every document tagged with `clubId`. Isolation is enforced centrally (a base repository / aspect in the `common` module injects the `clubId` filter automatically) — **this is the single most important thing to check in every PR that touches data access.** Full rationale in spec section 03.

Custom repository methods bypass that layer, so an ArchUnit test fails the build unless each one has `ClubId` in its name. The only exception is a method explicitly annotated `@GloballyScoped` — reserved for lookups by a system-wide unique value that must run before any club context exists (today: `UserRepository.findByEmail`, for login). Each use should be reviewed on its own merits.

Every repository's entity must either extend `ClubScopedEntity` or be explicitly annotated `@NotClubScoped` — the app refuses to start otherwise, so forgetting the base class on tenant data fails loudly. `@NotClubScoped` is only for data no club owns; today that's `Club` itself, the tenant root.

## Language

Hebrew is the primary and only supported UI language at launch (RTL-first, via an i18n library from day one — don't hardcode strings). Exception: football terminology already used in English by Israeli coaches — position codes (`GK`, `CB`, `DM`, ...) and formation notation (`4-3-3`) — stays in English everywhere, including the tactical board. Full detail in spec section 01.

## Security

- JWT: short-lived (15 min) HS256 Access Token carrying `sub` (user id), `clubId` and `permissionLevel`, sent as `Authorization: Bearer ...`. `auth.JwtAuthenticationFilter` validates it on every request and puts its `clubId` into `ClubContext` for the request's duration — so a club is always taken from the token, never from the request body or parameters
- Refresh Token: an opaque random value (not a JWT), 30-day lifetime, only ever in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/auth`. Stored in Redis as a SHA-256 hash, grouped into one token *family* per login; every refresh rotates it, and replaying an already-rotated token revokes the whole family (see `auth.RefreshTokenService` for the Redis key scheme)
- Passwords: Argon2id + pepper (pepper lives only in an env var, never in the DB or in git). The password is HMAC-SHA256'd with the pepper, then hashed with Spring Security's `Argon2PasswordEncoder` (`auth.PepperedPasswordEncoder`). Changing the pepper invalidates every stored hash
- RBAC: enforced by Permission Level (`ADMIN` / `EDIT_FULL` / `EDIT_PARTIAL` / `VIEW_ONLY`), combined with `clubId` filtering. Each level is a Spring Security authority of the same name — restrict an endpoint with `@PreAuthorize("hasAuthority('ADMIN')")` on the controller method, never with a manual check in its body. Every endpoint requires a valid access token unless `auth.SecurityConfig` lists it as public
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

No Docker build or CD yet. Integration tests start their own MongoDB and Redis through Testcontainers (using the runner's Docker), so the workflow needs no service containers. Branch protection on `master` should require both `backend-ci` and `frontend-ci` to pass before merging (GitHub → Settings → Branches).

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

1. `cp .env.example .env`, then replace every value with real ones (`.env` is git-ignored). Use long random values for `JWT_SECRET`, `PASSWORD_PEPPER` and `OWNER_BOOTSTRAP_SECRET` (at least 32 characters each, all different — the backend refuses to start otherwise; `OWNER_BOOTSTRAP_SECRET` is only required by the bootstrap task below).
2. `docker compose up -d` — MongoDB + Redis. MongoDB runs as a single-node replica set (`rs0`), since MongoDB only supports multi-document transactions on a replica set; the healthcheck initiates it on first start. Keep `directConnection=true` in `MONGODB_URI`.
3. Backend: `cd backend && ./mvnw spring-boot:run` (it reads `../.env` automatically). It needs both containers: MongoDB for data, Redis (`REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`) for login sessions — the Redis connection is only opened on first use, so a missing Redis shows up as failing logins, not a failed startup. Checks: `./mvnw verify` (tests + formatting; fix formatting with `./mvnw spotless:apply`).
4. Frontend: `cd frontend && npm install && npm run dev`. Checks: `npm run lint`, `npm run format:check`, `npm test`, `npm run build`.

### Auth API

| Endpoint | Access | What it does |
|---|---|---|
| `POST /auth/login` | public | `{ "email", "password" }` → `200` with `{ "accessToken", "tokenType": "Bearer", "expiresIn" }` in the body and the refresh token in the `refresh_token` cookie. Any failure (unknown email, wrong password, no password set yet, deactivated user) is the same generic `401` |
| `POST /auth/refresh` | public (refresh cookie) | Rotates the refresh cookie and returns a new access token. `401` for a missing / expired / revoked / reused token |
| `POST /auth/logout` | public (refresh cookie) | Revokes this session's token family (other devices stay logged in) and clears the cookie. Always `204` |
| `POST /auth/users/invite` | `ADMIN` | `{ "email", "fullName", "title", "permissionLevel", "dateOfBirth"? }` → `201` with the new user, always in the caller's own club. The user has no password yet, so can't log in until they set one (activation — KAN-21). `403` for non-admins, `409` if the email is taken (in any club) |

Errors use the same JSON shape as every other endpoint (`common.ApiErrorResponse`). An access token stays valid until it expires (at most 15 minutes) even after logout or revocation — only refresh tokens are revocable.

### Bootstrapping a new club

Only the system owner can create a club, together with its initial Club Manager (`CLUB_MANAGER` / `ADMIN`) — see spec section 09. There's no endpoint for this: it's a one-off run of the backend under the `bootstrap` profile, which starts no web server (so it can run alongside the real one), creates both documents in one transaction, and exits (code `0` on success, `1` otherwise). It's gated by the owner secret, not by RBAC: you're prompted for it, and it's compared with `OWNER_BOOTSTRAP_SECRET` (which only the `bootstrap` profile loads — a normal server never binds it). With a missing or wrong secret nothing is written.

```sh
cd backend && ./mvnw package -DskipTests
java -jar target/squadpulse-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=bootstrap \
  --club-name="Club name" \
  --manager-email=manager@example.com \
  --manager-full-name="Full Name" \
  --manager-date-of-birth=1985-03-01   # optional, yyyy-MM-dd
```

It then prompts (no echo) for the owner secret and, only if that's right, for the Club Manager's initial password, twice. Secrets are never accepted as arguments — those are visible to other local users (e.g. via `ps`) and end up in shell history — so `--owner-secret` / `--manager-password` are rejected outright. Run it directly in a terminal: prompting needs one, so it refuses to run through a pipe, `./mvnw spring-boot:run` or an IDE run configuration.

In production, give `OWNER_BOOTSTRAP_SECRET` only to the environment of the bootstrap run, not to the running server's. (Locally, the `.env` import puts it in every process's Spring `Environment` as an unused raw value; nothing outside the `bootstrap` profile reads it.)

## Working with Claude Code

When you start a new Claude Code session in this repo, point it here first — e.g. *"Read the README, then implement KAN-6."* Jira ticket descriptions carry the specific requirements for each task; this file carries the standing context (architecture, conventions, current phase) so you don't have to re-explain it every session.
