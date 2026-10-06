# SquadPulse

A web platform for managing an adult football club's day-to-day professional operations — squad, tactics, training, and match data — from one place. Hebrew-first (RTL), multi-club from day one.

**Status:** Backend core in progress. The first real endpoints exist: authentication (login / refresh / logout), forgot / reset password, inviting users (who activate their account with an emailed code), the club's staff list (`GET /auth/users`), deactivating and re-activating users, and the current user's own profile (`GET /auth/users/me`) — see [Auth API](#auth-api). The club's settings (viewing and renaming it) and its optional logo — see [Club API](#club-api). An optional photo per staff user — see [Staff photo API](#staff-photo-api). Squad: listing (with filters), viewing, adding, editing, releasing, re-activating and permanently deleting players, a squad summary (player count, average age, players per line), and an optional photo per player — see [Squad API](#squad-api). No other feature code has shipped yet. The frontend has its infrastructure (routing, theme, components, API proxy, test setup) and its API client and session, but no screens yet; the scraper is still a skeleton.

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
├── docs/design/ui-conventions.md     # Approved UI design: theme tokens, type scale, layout rules
├── docker-compose.yml                # MongoDB + Redis, local dev only
└── README.md
```

## Tech stack

| Layer | Choice |
|---|---|
| Frontend | React 19, TypeScript, Vite, Tailwind CSS 4, shadcn/ui (Base UI, RTL mode), React Router 8 (data router), TanStack Query, Zustand, Konva.js (tactical board), Recharts |
| Backend | Java, Spring Boot (single modular monolith) |
| Database | MongoDB (primary data), Redis (refresh-token families, failed-login counts, and activation / reset codes today; cache planned) |
| Scraper | Node.js, Playwright/Cheerio |
| Auth | Stateless JWT (Access + Refresh in HttpOnly cookie), Argon2id password hashing + a pepper (env var, never committed) |
| CI | GitHub Actions (lint, test, build on PRs to `master` and pushes to `master` — no CD yet) |

## Multi-tenancy

Pool model: shared collections across all clubs, every document tagged with `clubId`. Isolation is enforced centrally (a base repository / aspect in the `common` module injects the `clubId` filter automatically) — **this is the single most important thing to check in every PR that touches data access.** Full rationale in spec section 03.

Custom repository methods bypass that layer, so an ArchUnit test fails the build unless each one has `ClubId` in its name. The only exception is a method explicitly annotated `@GloballyScoped` — reserved for lookups by a system-wide unique value that must run before any club context exists (today: `UserRepository.findByEmail`, for login). Each use should be reviewed on its own merits.

Every repository's entity must either extend `ClubScopedEntity` or be explicitly annotated `@NotClubScoped` — the app refuses to start otherwise, so forgetting the base class on tenant data fails loudly. `@NotClubScoped` is only for data no club owns; today that's `Club` itself, the tenant root.

A `Player` (`players` collection) is a roster record owned by one club, not a global person: the same person in two clubs is two independent records with no link between them. Leaving the club sets `active: false` rather than deleting the document. Jersey numbers are unique among a club's **active** players, enforced by the partial unique index `clubId_jerseyNumber_active_unique` (only documents where `jerseyNumber` is a number and `active` is `true`), created at startup by `auto-index-creation` like the `users` email index. A write that breaks it fails with `DuplicateKeyException`, which the squad API turns into a `409` only when it names that index. Like `User`, `Player` uses optimistic locking (`@Version`). The list filters run in memory on the club's players, loaded through the club-scoped repository, never through a hand-built `MongoTemplate` query, which would bypass the `clubId` filter.

Images (player photos, the club logo and staff photos) are stored in MongoDB **GridFS**, in the dedicated `images` bucket (`images.files` / `images.chunks`), and the rest of the code reaches them only through `common.ImageStorage`, which takes no `clubId` — it uses the one in `ClubContext`. GridFS isn't a Spring Data repository, so the club-scoped layer doesn't cover it: `common.GridFsImageStorage`, the only GridFS code, stores `clubId`, kind and owner id as metadata on every file and adds the `clubId` to every query itself, and an ArchUnit test fails the build if anything outside `common` uses GridFS. Each owner has one current image (the latest by upload date, then id); storing a new one removes the older ones, and that's safe under concurrent uploads without a transaction. The index `images_club_kind_owner_uploadDate` on `images.files` is created at startup. The interface uses no GridFS types, so it can move to object storage (S3/R2) later by swapping the implementation.

## Language

Hebrew is the primary and only supported UI language at launch (RTL-first, via an i18n library from day one — don't hardcode strings). Exception: football terminology already used in English by Israeli coaches — position codes (`GK`, `CB`, `DM`, ...) and formation notation (`4-3-3`) — stays in English everywhere, including the tactical board. Full detail in spec section 01.

## Security

- JWT: short-lived (15 min) HS256 Access Token carrying `sub` (user id), `clubId` and `permissionLevel`, sent as `Authorization: Bearer ...`. `auth.JwtAuthenticationFilter` validates it on every request and puts its `clubId` into `ClubContext` for the request's duration — so a club is always taken from the token, never from the request body or parameters
- Refresh Token: an opaque random value (not a JWT), 30-day lifetime, only ever in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/auth`. Stored in Redis as a SHA-256 hash, grouped into one token *family* per login; every refresh rotates it, and replaying an already-rotated token revokes the whole family (see `auth.RefreshTokenService` for the Redis key scheme)
- Login throttling: after 5 failed logins within 15 minutes for the same (email, client IP) pair, further attempts from that pair get `429` (with `Retry-After`) without the password being checked, until the window ends; a successful login clears the count. Keyed by the pair so nobody can lock a user out by failing their password from elsewhere, and unknown emails are throttled the same way so a `429` reveals nothing. The IP is the TCP peer address — `X-Forwarded-For` is deliberately not trusted until a deployment target and its proxy are chosen. Limits are in `application.yml` under `squadpulse.security.login-throttle` (see `auth.LoginThrottleService` for the Redis key scheme)
- Activation / password reset: an emailed 6-digit code, valid 15 minutes and for 5 wrong guesses, single use — no reset links. Stored in Redis only as an HMAC keyed with the pepper, so a Redis dump doesn't reveal it. `POST /auth/forgot-password` is limited to 5 requests per email per 24 hours, silently: beyond that nothing is sent, but the response is the same `202`. That long window is what bounds brute-forcing a code (about 25 guesses a day). Setting a password with a code ends every refresh session the user has. Password policy: 8–128 characters, no composition rules (NIST / OWASP). Limits are in `application.yml` under `squadpulse.security.password-reset` (see `auth.PasswordResetCodeService` for the Redis key scheme)
- Deactivation: a Club Manager can deactivate a user (`POST /auth/users/{id}/deactivate`) instead of deleting them. It ends every refresh session the user has, and login, refresh, forgot / reset password and `/auth/users/me` refuse them from then on. Re-activation ends any old session again, so a refresh cookie that wasn't used while the user was deactivated can't come back to life. An access token already issued keeps working until it expires (at most 15 minutes), except on `/auth/users/me` and the user-management writes, which re-read their caller
- Email: everything goes through `common.EmailSender`. The only implementation today, `common.LoggingEmailSender`, writes each email — **activation and reset codes included, in plaintext** — to the log instead of sending it. Fine for local development (read the code off the console), but it must be replaced by a real, asynchronous provider before any deployment
- Passwords: Argon2id + pepper (pepper lives only in an env var, never in the DB or in git). The password is HMAC-SHA256'd with the pepper, then hashed with Spring Security's `Argon2PasswordEncoder` (`auth.PepperedPasswordEncoder`). Changing the pepper invalidates every stored hash
- RBAC: enforced by Permission Level (`ADMIN` / `EDIT_FULL` / `EDIT_PARTIAL` / `VIEW_ONLY`), combined with `clubId` filtering. Each level is a Spring Security authority of the same name — restrict an endpoint with `@PreAuthorize("hasAuthority('ADMIN')")` on the controller method, never with a manual check in its body. The levels are hierarchical, `ADMIN > EDIT_FULL > EDIT_PARTIAL > VIEW_ONLY` (`auth.SecurityConfig#permissionLevelHierarchy`, derived from `PermissionLevel`'s declaration order), so an endpoint requires the lowest level that may use it and every higher level passes too; read endpoints require `VIEW_ONLY` explicitly. An ArchUnit test fails the build if any controller method handling requests has neither `@PreAuthorize` (on the method or its class) nor `@PublicEndpoint` (`common`) — so a forgotten annotation can't leave an endpoint open to every authenticated user (the one exempt class is `common.ApiErrorController`, which renders the container's `/error` dispatch); `@PublicEndpoint` methods must match `SecurityConfig.PUBLIC_ENDPOINTS`, which another test checks. `EDIT_PARTIAL` is not required by any endpoint yet, so for now it grants what `VIEW_ONLY` does. Every endpoint requires a valid access token unless `auth.SecurityConfig` lists it as public
- Image uploads: the type is decided **only by the file's magic bytes** — JPEG, PNG or WebP; anything else (SVG, GIF, HTML, ...) is refused, and the client's `Content-Type` and file name are ignored and never stored. At most 2 MB (`squadpulse.images.max-size`), with the servlet container's multipart limit (`spring.servlet.multipart.*`: 2MB per file, 3MB per request) as the outer guard. `server.tomcat.max-swallow-size: 20MB` is a deliberate, **server-wide** setting: when a request is answered before its body has been read (an oversize upload's `413`, or a `401`), Tomcat reads and discards up to that much of the rest so the client gets the error response instead of a connection reset (the 2MB default is below the upload limit, so a 3 MB photo could get a reset). The cost: for any rejected request, Tomcat may read up to 20MB of body it then throws away; beyond that it still resets the connection. Served with the detected type and `X-Content-Type-Options: nosniff` (Spring Security's default headers, which also add `Cache-Control: no-store`). Images aren't re-encoded, so EXIF metadata (possibly a GPS location) is stored and served as uploaded
- CORS restricted, rate limiting via Redis, input validation on every endpoint, Dependabot in CI
- **Nothing secret ever goes into git** — env vars / secrets manager only

## Git & Jira workflow

- **Branches:** `feature/KAN-123-short-desc` / `fix/KAN-124-...` — always include the ticket key.
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) with the ticket key, e.g. `feat(squad): add player creation endpoint (KAN-12)`.
- **PRs:** one ticket = one PR into `master`, even solo — keeps CI as a real gate and leaves a review trail.
- **Jira workflow:** Backlog → To Do → In Progress → In Review → Done. GitHub is connected to Jira, so branches/commits/PRs referencing a ticket key show up automatically on that ticket.

## CI

One workflow, [`.github/workflows/ci.yml`](.github/workflows/ci.yml), runs on PRs targeting `master` and on pushes to `master`. A new push to the same ref cancels the previous in-flight run. Both jobs run on `ubuntu-24.04`, pinned on purpose instead of `ubuntu-latest`, so the runner OS (and its Docker, which Testcontainers uses) changes only in a PR that changes it; moving to `ubuntu-26.04` (Docker 29) is a deliberate future step. Two independent jobs run in parallel:

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

Prerequisites: **JDK 21**, Node 22.22+ (or 24+; React Router 8 needs 22.22), Docker.

1. `cp .env.example .env`, then replace every value with real ones (`.env` is git-ignored). Use long random values for `JWT_SECRET`, `PASSWORD_PEPPER` and `OWNER_BOOTSTRAP_SECRET` (at least 32 characters each, all different — the backend refuses to start otherwise; `OWNER_BOOTSTRAP_SECRET` is only required by the bootstrap task below).
2. `docker compose up -d` — MongoDB + Redis. MongoDB runs as a single-node replica set (`rs0`), since MongoDB only supports multi-document transactions on a replica set; the healthcheck initiates it on first start. Keep `directConnection=true` in `MONGODB_URI`.
3. Backend: `cd backend && ./mvnw spring-boot:run` (it reads `../.env` automatically). It needs both containers: MongoDB for data, Redis (`REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`) for login sessions, failed-login counts and activation / reset codes — the Redis connection is only opened on first use, so a missing Redis shows up as failing logins, not a failed startup. Checks: `./mvnw verify` (tests + formatting; fix formatting with `./mvnw spotless:apply`).
4. Frontend: `cd frontend && npm install && npm run dev`, then open http://localhost:5173/. Checks: `npm run lint`, `npm run format:check`, `npm test`, `npm run build`.
   - The app's own pages all live under `/app` (`/` redirects there), never under a backend path, so a browser reload always gets the app.
   - The dev server proxies the backend's paths (`/auth`, `/squad`, `/clubs`, `/users`, whole path segments only) to the backend, so the app calls the API on its own origin: no CORS, and the refresh cookie (`Path=/auth`) works unchanged. The backend must therefore be running, on port 8080 by default. Without it, API calls through the proxy fail with `502`.
   - The app calls the backend only through `src/lib/api` (`apiFetch` / `apiJson`): it adds the access token, which is kept in memory only (never in browser storage), and on a `401` renews it once through the refresh cookie and retries. A reload therefore starts without an access token; the session is restored from the cookie.
   - Every `/app` page needs a login, except the login screen (`/app/login`), "forgot password" (`/app/forgot-password`) and "set password with a code" (`/app/reset-password`, also how an invited user activates their account: the login screen links to it). On load the app shows a loading screen while it restores the session; if the backend can't be reached, it shows a "no connection" screen with a retry button instead of the login form. In development the activation / reset codes appear in the backend's log (the logging email stub). Logging out in one tab logs out every other tab of the app too. Use Chrome locally: over plain `http://localhost` Safari drops the refresh cookie, so a reload there always lands on the login screen (KAN-56).
   - Tests (Vitest + Testing Library) never call the real backend: they mock it with [MSW](https://mswjs.io/), and a request no mock covers fails the test.
   - Optional settings, in `frontend/.env.local` (git-ignored; see `frontend/.env.example`): `SQUADPULSE_BACKEND_URL`, where the dev proxy sends those paths (default `http://localhost:8080`; used only by `vite.config.ts`, never sent to the browser); `VITE_API_BASE_URL`, the backend's base URL as the app calls it, baked in at build time (empty by default = the app's own origin: the dev proxy, or a reverse proxy in production).

### Auth API

| Endpoint | Access | What it does |
|---|---|---|
| `POST /auth/login` | public | `{ "email", "password" }` → `200` with `{ "accessToken", "tokenType": "Bearer", "expiresIn" }` in the body and the refresh token in the `refresh_token` cookie. Any failure (unknown email, wrong password, no password set yet, deactivated user) is the same generic `401`; after 5 failures in 15 minutes from the same IP for the same email, `429` with `Retry-After` |
| `POST /auth/refresh` | public (refresh cookie) | Rotates the refresh cookie and returns a new access token. `401` for a missing / expired / revoked / reused token |
| `POST /auth/logout` | public (refresh cookie) | Revokes this session's token family (other devices stay logged in) and clears the cookie. Always `204` |
| `POST /auth/forgot-password` | public | `{ "email" }` → always `202` with an empty body. Emails a 6-digit reset code only if the email belongs to an active user and is within its limit (5 requests per 24 hours), otherwise does nothing — the response is the same either way, never `429`. `400` for a blank or overlong email |
| `POST /auth/reset-password` | public | `{ "email", "code", "newPassword" }` → `204`. Sets the password with an emailed code — a reset, or an invited user's activation — and ends **all** of the user's refresh sessions (access tokens they already hold stay valid until they expire). Doesn't log in. `401` with one generic message for a wrong, expired, used or burned code, or an unknown / deactivated user; `400` if the code isn't 6 digits or the password isn't 8–128 characters (doesn't use up the code or a guess) |
| `GET /auth/users` | `ADMIN` | → `200` with every user of the caller's club as a plain JSON array (no wrapper, no pagination, no query parameters), each with the same fields as the invite response below. Includes the caller, **deactivated** users (`active: false`) and invited users who haven't set a password yet (`activated: false`) — never another club's. Ordered active users first, then by `fullName`, then by `id` (plain string order, no Hebrew collation). `hasPhoto` says whether `GET /users/{id}/photo` has an image. `ADMIN` only: it shows emails, dates of birth and levels, so it's a management screen, not a staff directory. `403` for non-admins. Two queries per call (the users, and which of them have a photo); read-only |
| `POST /auth/users/invite` | `ADMIN` | `{ "email", "fullName", "title", "permissionLevel", "dateOfBirth"? }` → `201` with the new user (`{ "id", "email", "fullName", "title", "permissionLevel", "dateOfBirth", "active", "hasPhoto", "activated" }`; `hasPhoto` and `activated` are always `false` for a new user), always in the caller's own club. The user has no password yet: they're emailed an activation code (valid 15 minutes) and set one through `/auth/reset-password`, then log in. If the code expires, they use `/auth/forgot-password`. `403` for non-admins, `409` if the email is taken (in any club) |
| `PATCH /auth/users/{id}/permission-level` | `ADMIN` | `{ "permissionLevel" }` → `200` with the updated user (the same fields as the invite response; `hasPhoto` says whether `GET /users/{id}/photo` has an image, `activated` whether the user has set a password — `false` for an invited user who hasn't activated yet, whose level may be changed too). Sets another user's level in the caller's own club — any level, including granting or removing `ADMIN`; nothing else about the user changes (other body fields are ignored). `400` for a missing / unknown level, `403` for non-admins, `404` if there's no such user in the caller's club (a user in another club looks exactly like a nonexistent id), `409` for the caller's own id — nobody can change their own level, so a club's only admin can't lock the club out. Takes effect at the target's next `/auth/refresh`: an access token they already hold keeps the old level until it expires (at most 15 minutes) |
| `POST /auth/users/{id}/deactivate` | `ADMIN` | No body → `200` with the user (the same fields as the invite response, with the real `hasPhoto` and `activated`). Deactivates another user of the caller's club: sets `active: false` and ends **all** of their refresh sessions. From then on their login is the generic `401`, `/auth/refresh` is `401`, `/auth/forgot-password` sends them nothing, `GET /auth/users/me` is `401`, and their photo can't be changed (`409`). An access token they already hold keeps working on other endpoints until it expires (at most 15 minutes). Everything else is kept: password, permission level, title, photo, and their entry in the staff list (with `active: false`). Another `ADMIN` can be deactivated too, and keeps the `ADMIN` level. Already deactivated → `200` with the current state and nothing written (no `version` / `updatedAt` change). `403` for non-admins, `404` (`"User not found"`) if there's no such user in the caller's club (a user in another club looks exactly like a nonexistent id, even one already deactivated), `409` for the caller's own id (`"You can't deactivate or reactivate yourself; another ADMIN must do it"`) |
| `POST /auth/users/{id}/reactivate` | `ADMIN` | No body → `200` with the user. Sets `active: true` again, and **also** ends any refresh session the user had: a refresh cookie from before the deactivation never comes back to life, so the user must log in again. Sends no email and needs no new password: the old one works again, and a user who never set one (`activated: false`) still needs their activation code or forgot-password. Already active → `200`, nothing written. Same `403` / `404` / `409` as deactivate |
| `GET /auth/users/me` | any authenticated user (`VIEW_ONLY`) | → `200` with the caller: `{ "id", "email", "fullName", "title", "permissionLevel", "dateOfBirth", "active", "hasPhoto", "activated", "club": { "id", "name", "hasLogo" } }` — the same fields as the user responses above, plus their club, for the app header and for hiding actions the user can't perform. `hasPhoto` says whether `GET /users/me/photo` has an image and `club.hasLogo` whether `GET /clubs/me/logo` has one, so the client can skip those requests. No path id: it always returns the caller, in the club of their access token. `permissionLevel` is the **effective** level, the one in the caller's access token that every endpoint enforces right now, not the stored one: after an admin changes it, the new level shows here only after the caller's next `/auth/refresh`. Every other field is read from the database. `active` and `activated` are always `true` in a `200` (a user without a password can't log in). `401` (the same body as a request without a token, `"Authentication required"`) for a missing / invalid token, or a valid one whose user has been deactivated or deleted — so on app load a deactivated user's refresh fails too and they land on the login screen. Read-only |

Errors use the same JSON shape as every other endpoint (`common.ApiErrorResponse`). An access token stays valid until it expires (at most 15 minutes) even after logout, deactivation or revocation — only refresh tokens are revocable. There are two exceptions, which re-read the caller and answer the generic `401` (`"Authentication required"`, the same body as a request without a token) once they're deactivated or deleted: `GET /auth/users/me`, and exactly these **writes**: every user-management write — invite, permission-level, deactivate and reactivate — and `PATCH /clubs/me` (see [Club API](#club-api)). The writes do this so a just-deactivated admin can't use their remaining minutes to deactivate or demote the admin who deactivated them, or rename the club. The club-logo and staff-photo writes deliberately don't re-check the caller: they're cosmetic and reversible, and the token lives at most 15 minutes. The caller is checked first, so a deactivated caller gets `401` whatever the target. The read-only `GET /auth/users` and every other endpoint don't re-check.

**Concurrent writes to a user.** `User` is protected by optimistic locking (a `@Version` field): a save made from a stale copy fails instead of silently overwriting a concurrent change. Users stored before that field existed get it automatically: on startup, before the server accepts requests, `auth.UserVersionBackfill` sets `version: 0` on every user document that has none — nothing to do by hand. `/auth/reset-password`, `PATCH /auth/users/{id}/permission-level` and `POST /auth/users/{id}/deactivate` / `reactivate` resolve a conflict themselves by reloading the user and retrying (up to 3 attempts; a reset re-checks on each one that the user is still active, so it can never undo a deactivation; deactivate / reactivate re-check whether the user is already in the requested state, and never move the session-invalidation time backwards). A conflict that isn't resolved that way is a `409` with a generic "modified concurrently, please retry" message.

### Club API

The caller's own club only: "me" is the club of the access token — there's never a club id in the path or body, and none is read from the request. Deliberately **not** under `/auth`: the refresh-token cookie is scoped to `Path=/auth`, and requests that don't need it (like a logo fetched on every app load) mustn't carry it.

| Endpoint | Access | What it does |
|---|---|---|
| `GET /clubs/me` | `VIEW_ONLY` | `200` with `{ "id", "name", "hasLogo" }` — the same object as `club` in `GET /auth/users/me` |
| `PATCH /clubs/me` | `ADMIN` | `{ "name" }` → `200` with the club as stored, same shape as `GET` |
| `PUT /clubs/me/logo` | `ADMIN` | `multipart/form-data` with the image in the part named `file` → `204`. Replaces any existing logo. Doesn't change the club itself |
| `GET /clubs/me/logo` | `VIEW_ONLY` | `200` with the image bytes, `Content-Type` = the detected type, `Content-Length`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`. `404` (`"This club has no logo"`) if there's none |
| `DELETE /clubs/me/logo` | `ADMIN` | Removes the logo → `204`, also when there was none |

**Settings (`PATCH /clubs/me`).** A partial update of the club's settings; today `name` is the only one, so it's required. Settings added later will be optional fields of the same body, a missing one meaning "unchanged". `name` is trimmed, then must be 1–100 characters: missing, `null`, blank or longer → `400` with `"name: must not be blank"` / `"name: size must be between 0 and 100"` in `details`. The owner bootstrap applies the same rule. Unknown fields are ignored, as everywhere: an `id` or `clubId` in the body changes nothing. There's no `version` and no `409`: a rename changes only `name` (a targeted update, never a rewrite of the club document), so of two concurrent renames the later one wins, and each response shows what was stored right after its own write. Like the user-management writes, it re-reads the caller first: a deactivated admin whose access token hasn't expired yet gets the generic `401` (`"Authentication required"`). `GET` doesn't re-check, and neither do the logo writes below — deliberately: a logo is cosmetic and reversible, and the token lives at most 15 minutes. `403` for non-admins. Clubs stored before this rule with a longer or padded name are returned as stored.

**Logo.** The same upload rules, limits and errors as [player photos](#squad-api) (JPEG, PNG or WebP by content, at most 2 MB, `400` / `413` as described there); there's no `409`, as a club has no released state. `403` for non-admins on `PUT` / `DELETE`. Like the photo, `GET` needs the `Authorization` header — no public URL — so the frontend fetches it as a blob (see the frontend note under Squad API); use `club.hasLogo` from `GET /auth/users/me` to skip the request when there's no logo.

### Staff photo API

Each staff user can have one optional profile photo. Deliberately **not** under `/auth` (unlike the other user endpoints), for the same reason as the club logo: the refresh-token cookie is scoped to `Path=/auth`, and photos fetched often (app header, staff lists) mustn't carry it. `me` is always the user of the access token — never read from the request.

| Endpoint | Who | What it does |
|---|---|---|
| `PUT /users/me/photo` | any authenticated user (`VIEW_ONLY`) | `multipart/form-data` with the image in the part named `file` → `204`. Sets the caller's own photo, replacing any existing one |
| `GET /users/me/photo` | any authenticated user (`VIEW_ONLY`) | The caller's own photo (see `GET /users/{id}/photo`) |
| `DELETE /users/me/photo` | any authenticated user (`VIEW_ONLY`) | Removes the caller's own photo → `204`, also when there was none |
| `PUT /users/{id}/photo` | `ADMIN` | Like `PUT /users/me/photo`, for any user of the caller's club (an admin may also use it on themselves) |
| `GET /users/{id}/photo` | `VIEW_ONLY` | `200` with the image bytes, `Content-Type` = the detected type, `Content-Length`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`. `404` (`"This user has no photo"`) if there's none |
| `DELETE /users/{id}/photo` | `ADMIN` | Like `DELETE /users/me/photo`, for any user of the caller's club |

The `me` endpoints are the only ones where `VIEW_ONLY` writes anything: it's the caller's own profile, never another user's or club data. The same upload rules, limits and errors as [player photos](#squad-api) (JPEG, PNG or WebP by content, at most 2 MB, `400` / `413` as described there). `404` (`"User not found"`) on any `{id}` operation if there's no such user in the caller's club — a user of another club looks exactly like a nonexistent id. A **deactivated** user is treated like a released player: their photo can still be read, but `PUT` / `DELETE` are a `409` (`"This user has been deactivated; their profile can't be changed"`), whoever the caller is — including the deactivated user with an access token that hasn't expired yet. `403` for non-admins on `PUT` / `DELETE /users/{id}/photo`. The user itself is never modified (its `version` doesn't change). Like the other images, `GET` needs the `Authorization` header — no public URL — so the frontend fetches it as a blob (see the frontend note under Squad API); use `hasPhoto` from `GET /auth/users/me` and the user responses to skip the request when there's no photo.

### Squad API

Every endpoint works on the caller's own club only (the `clubId` comes from the access token, never from the request). A player in another club looks exactly like a nonexistent id: `404`.

| Endpoint | Access | What it does |
|---|---|---|
| `GET /squad/players` | `VIEW_ONLY` | The club's players, filtered (see below) and in squad order: primary position `GK` → `ST`, then jersey number (players without one last), then name. Not paginated |
| `GET /squad/players/{id}` | `VIEW_ONLY` | One player, including a released one (`"active": false`). `404` if there's no such player in the caller's club |
| `POST /squad/players` | `EDIT_FULL` | `{ "fullName", "primaryPosition", "secondaryPosition"?, "jerseyNumber"?, "dateOfBirth", "heightCm"?, "weightKg"?, "preferredFoot"?, "medicalStatus"? }` → `201` with the new player and its URL in `Location`. Always an active player of the caller's club; `medicalStatus` defaults to `FIT` |
| `PUT /squad/players/{id}` | `EDIT_FULL` | The same fields plus `"version"`, with `medicalStatus` required → `200` with the updated player. A **full replacement**: an optional field that's missing or `null` is cleared |
| `POST /squad/players/{id}/release` | `EDIT_FULL` | `{ "version" }` → `200` with the player, now `"active": false`. The player leaves the club but stays on record; their `jerseyNumber` is kept as history and is free for another player |
| `POST /squad/players/{id}/reactivate` | `EDIT_FULL` | `{ "version", "jerseyNumber"? }` → `200` with the player, `"active": true`, wearing exactly the `jerseyNumber` sent: missing or `null` means **no number** (not "keep the old one"). The client should pre-fill the old number; if it has been taken, pick another or none |
| `DELETE /squad/players/{id}` | `ADMIN` | Permanently deletes a player, active or released, **and their photo** → `204`, no body. Only for records created by mistake — leaving the club is a release. **Not version-checked**: it wins over a concurrent edit |
| `PUT /squad/players/{id}/photo` | `EDIT_FULL` | `multipart/form-data` with the image in the part named `file` → `204`. Replaces any existing photo (see below) |
| `GET /squad/players/{id}/photo` | `VIEW_ONLY` | `200` with the image bytes, `Content-Type` = the detected type, `Content-Length`, `X-Content-Type-Options: nosniff`. `404` if the player has no photo. Works for released players too |
| `DELETE /squad/players/{id}/photo` | `EDIT_FULL` | Removes the photo → `204`, also when there was none |
| `GET /squad/summary` | `VIEW_ONLY` | `{ "playerCount", "averageAge", "lines": { "GOALKEEPERS", "DEFENSE", "MIDFIELD", "ATTACK" } }` for the club's **active** players only (see below) |

Filters for `GET /squad/players`, all optional and combined with AND: `status` = `active` (default) / `released` / `all`; `position` (matches the **primary** position only); `minAge` / `maxAge` (whole years, inclusive, each 18–99); `medicalStatus`; `preferredFoot`. An unknown value, an out-of-range age or `minAge` > `maxAge` is a `400` naming the parameter in `details` (e.g. `"minAge: must be less than or equal to maxAge"`).

A player's response carries `id`, every field, `active`, `version`, `createdAt`, `updatedAt` and `hasPhoto` (whether `GET .../photo` has an image; always `false` right after `POST`), never `clubId`. Field rules (a `400` naming the field in `details` otherwise, e.g. `"fullName: must not be blank"`; a value that can't be read at all, like an unknown enum or a malformed date, is `"dateOfBirth: invalid value"`): `fullName` 1–100 characters after trimming; `secondaryPosition`, if set, differs from `primaryPosition`; `jerseyNumber` 1–99; age from `dateOfBirth` (`yyyy-MM-dd`) 18–99; `heightCm` 140–220; `weightKg` 40–150. Body fields that aren't part of the request (`clubId`, `active`, `id`, ...) are ignored. A missing body on `POST` / `PUT` is a `400` (`"Malformed request body"`); a missing `version` is a `400` naming it.

**Squad summary.** Counts the club's active players only, whatever their medical status; released players never count. `lines` always has all four keys, in the order above, with `0` for an empty line: each player counts once, in the line of their **primary** position (`GK` → goalkeepers; `CB`, `RB`, `LB` → defense; `DM`, `CM`, `AM` → midfield; `RW`, `LW`, `ST` → attack), so the four add up to `playerCount`. `averageAge` is the mean of each player's **exact** age today — completed years plus the elapsed fraction of the current birthday year (for a 29 February birthday in a non-leap year, the birthday counts as 1 March, as for the age filters) — rounded half up to one decimal, e.g. `26.4`. A player without a date of birth (only possible in data not written through the API) is counted but left out of the average; `averageAge` is `null` when no active player has one, including an empty squad.

**Photos.** One optional photo per player. Accepted: JPEG, PNG or WebP, recognized by the file's own bytes — the declared `Content-Type` and file name don't matter (a PNG sent as `x.jpg` is stored and served as `image/png`). Errors: an empty file → `400` `"file: must not be empty"`; not one of those formats (SVG, GIF, ...) → `400` `"file: must be a JPEG, PNG or WebP image"`; no `file` part → `400` `"file: is required"`; a request that isn't multipart → `400` `"Malformed multipart request"`; over **2 MB** (2 × 1024 × 1024 bytes) → `413` (`"Content Too Large"`). A released player's photo can be read but not replaced or removed (`409`, as for any edit); release and re-activation keep it. Uploading or removing a photo doesn't change the player's `version`, so it never makes a pending edit stale.

**Frontend note:** like every endpoint, `GET .../photo` needs the `Authorization` header, so a plain `<img src="/squad/players/{id}/photo">` won't work. In the app, `useAuthorizedImage(path)` (`frontend/src/lib/api`) fetches it with the token, shows it through an object URL and revokes that URL when it's no longer shown. Pass `null` when `hasPhoto` is false, to skip the request for players without one.

**Conflicts (`409`).**
- **Jersey number taken:** another *active* player in the club already has it (checked by a unique database index, so it holds under concurrent writes too). A released player's number is free, and players without a number never clash.
- **Jersey number taken on re-activation:** the number sent is held by another active player (possibly taken while the player was away). Same message as above; the player stays released — there's no silent fallback to "no number".
- **Stale edit:** `PUT`, `release` and `reactivate` must send the `version` the client loaded. If the player has been saved since, the request is refused ("reload it and apply your changes again") and nothing is written. The client should reload and re-apply; it's never retried automatically. A save that loses a race right after that check gets the same `409` and message. `DELETE` has no version check.
- **Released player:** a released player can be read but not edited — nor their photo replaced or removed — until they're re-activated.
- **Already released / already active:** releasing a released player ("This player has already been released") or re-activating an active one ("This player is already active").

### Errors

Every error from the API has one JSON shape (`common.ApiErrorResponse`): `{ "timestamp", "status", "error", "message", "details": [] }`. `error` is the status's standard reason phrase (or `Validation Failed`). Rejected values and request headers (such as the `Content-Type` sent) are never echoed back in an error; only the `404` for an unknown path and the `405` name the request's method and path. Error bodies are always JSON (`Content-Type: application/json`), whatever the `Accept` header says — even one that excludes JSON, such as `Accept: application/xml`, or can't be parsed.

| Status | When | `message` / `details` |
|---|---|---|
| `400` Validation Failed | An invalid body field or query parameter, a query parameter of the wrong type, or a missing required query parameter / header / cookie / file part | `"Request validation failed"`; one `"name: problem"` entry per problem, e.g. `"minAge: must be greater than or equal to 18"`, `"q: is required"` |
| `400` Bad Request | A body that isn't valid JSON or has a value that can't be read; a broken multipart request; a request the endpoint rejects as a whole; a path the security firewall rejects (e.g. one containing `//` or `;`) | `"Malformed request body"` (with `"<field>: invalid value"` when the field is known), `"Malformed multipart request"`, the endpoint's own message, or `"The request could not be processed"` for a rejected path |
| `401` Unauthorized | No or invalid access token (`"Authentication required"`), or an auth failure such as bad credentials | |
| `403` Forbidden | The caller's permission level is too low | `"Access denied"` |
| `404` Not Found | No such resource in the caller's club, or no such endpoint (`"No endpoint GET /x"`) | |
| `405` Method Not Allowed | The path exists, but not for this method | `details` and the `Allow` header list the supported methods |
| `406` Not Acceptable | The `Accept` header allows nothing the endpoint produces (all JSON endpoints produce `application/json`) | `"None of the accepted media types can be produced"`; `details` lists what it produces |
| `409` Conflict | A duplicate, a stale `version`, a released player, ... | the reason |
| `413` Content Too Large | An upload over its limit | |
| `415` Unsupported Media Type | A body whose `Content-Type` the endpoint doesn't read (e.g. `text/plain` instead of `application/json`) | `"Unsupported Content-Type"`; `details` and the `Accept` header list the accepted types |
| `429` Too Many Requests | Too many failed logins | `Retry-After` header in seconds |
| `500` Internal Server Error | A bug | `"An unexpected error occurred"` |

Authentication comes first: a request without a valid token is a `401` whatever else is wrong with it — except a path the security firewall rejects, which is a `400` before authentication is looked at. After that, the request's form is checked before the permission level, so a malformed request from a caller who couldn't make it anyway gets a `400` / `415` rather than a `403` (nothing is read or changed either way). Any other status Spring itself raises is passed on with a generic message (`"The request could not be processed"`, or `"An unexpected error occurred"` for a 5xx).

**Logging.** Every `500` is logged at `ERROR` with its stack trace, the HTTP method and the path — never the query string, headers, body or cookies. Client errors (`4xx`) are never logged at `ERROR`. A client that disconnects mid-response (e.g. leaving a page while a photo downloads) isn't logged above `DEBUG`.

**Errors raised before Spring MVC** get the same JSON shape, with generic messages (KAN-35). An exception thrown in a servlet filter, such as the JWT filter, is caught by `common.UnhandledExceptionFilter`, which runs before every other filter except Boot's character-encoding one: a `500` `"An unexpected error occurred"`, logged once at `ERROR` like any other `500` (Tomcat no longer logs it). Anything that still reaches the servlet container's error page, `/error`, is rendered by `common.ApiErrorController`, which replaces Spring Boot's own (its Whitelabel HTML page is switched off too): most commonly a `400` `"The request could not be processed"` for a path Spring Security's firewall rejects (e.g. `/squad//players`), or a `5xx` a filter sent, logged at `ERROR` with its method and path. A direct request for `/error` is a `401` without a token and a `404` with one, like any unknown path. A new filter must not write its own error format. The one limit: a request so malformed that Tomcat's connector rejects it before the application sees it (a broken request line or header, or an encoded `/` (`%2F`) in the path) still gets Tomcat's own HTML error page.

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

## Deployment notes

Nothing is deployed yet (Phase 6+). Things the deployment must respect:

- **Request body size at the reverse proxy.** A reverse proxy or load balancer in front of the app must allow request bodies of **at least 3MB** (the app's `spring.servlet.multipart.max-request-size`), or image uploads (player photos, the club logo, staff photos) fail at the proxy before they reach the app — and with the proxy's error, not the app's JSON `413`. nginx's default `client_max_body_size` is 1MB, so it must be raised (e.g. `client_max_body_size 3m;`).
- **Email:** `common.LoggingEmailSender` must be replaced by a real provider first (see [Security](#security)).

## Working with Claude Code

When you start a new Claude Code session in this repo, point it here first — e.g. *"Read the README, then implement KAN-6."* Jira ticket descriptions carry the specific requirements for each task; this file carries the standing context (architecture, conventions, current phase) so you don't have to re-explain it every session.
