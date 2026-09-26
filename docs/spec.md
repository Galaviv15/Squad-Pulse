# SquadPulse — Technical & Product Spec

**Version:** v2 · draft
**Updated:** Sep 26, 2026
**Status:** Phase 0 in progress: backend, frontend and scraper skeletons exist, no feature code.
**Jira:** SquadPulse (`KAN`), at `squadpulse.atlassian.net`
**Target:** Adult clubs only

This revision updates the original document after a first feedback pass: monolith-first architecture, multi-club model, permissions, and security.

## Table of contents

00. [Overview](#00-overview)
01. [Language & localization](#01-language--localization)
02. [Architecture — modular monolith](#02-architecture--modular-monolith)
03. [Multi-tenancy](#03-multi-tenancy)
04. [Roles & permissions](#04-roles--permissions)
05. [Player entity](#05-player-entity)
06. [Training](#06-training)
07. [Matches, stats & scraping](#07-matches-stats--scraping)
08. [Dashboard](#08-dashboard)
09. [New club onboarding](#09-new-club-onboarding)
10. [Security](#10-security)
11. [Testing & DevOps](#11-testing--devops)
12. [Git & Jira conventions](#12-git--jira-conventions)
13. [Phased roadmap](#13-phased-roadmap)
14. [Open questions](#14-open-questions)

---

## 00. Overview

**SquadPulse** is a web platform that lets an adult football club run its day-to-day professional operations — squad management, tactical planning, training schedules, and match data — from one place, instead of the usual mix of spreadsheets, WhatsApp threads, and whatever the coach scribbled in a paper notebook before training.

It's built for a club's technical staff, not its fans: the **Club Manager** who runs the administrative side, the **Head Coach** who owns squad and tactical decisions, and the specialist staff around them — assistant coach, goalkeeping coach, fitness coach, analyst — who need visibility into the same data without necessarily being able to change it. The platform is multi-club from day one: several clubs can each run their own private instance of the same system, fully isolated from one another (see section 03).

Core capabilities:

- **Squad management** — a living roster of every player: position, physical profile, medical status, and performance data as it becomes available.
- **Tactical board** — a drag-and-drop canvas for building formations and set-piece routines, saved as reusable templates.
- **Training planner** — a calendar of sessions with attendance, intensity, and structured drill content, not just a time slot.
- **Match & league data** — league table, fixtures, and lineups pulled automatically from the national association, blended with data entered by hand.

SquadPulse also has a second purpose alongside the product itself: it's a deliberate training ground for working the way a professional engineering team does — Git workflows, an AI coding agent as an actual collaborator, Jira-driven planning, security practices, automated testing, and a real deployment — all applied end to end, not just described.

## 01. Language & localization

The pilot is a club in Israel, so **Hebrew is the platform's primary — and, at launch, only — supported language**. The product is Hebrew-first and RTL-first, not an English product with a Hebrew translation bolted on.

> **Build the i18n layer now, even for one language.** All user-facing strings go through an i18n library (e.g. `react-i18next`) from the very first screen, instead of being hardcoded into components. With a single language this costs almost nothing; skipping it now and needing a second language later (or even just an English admin view for support/debugging) would mean retrofitting every component. Content lives in translation files, not JSX.

RTL is a first-class layout requirement, not an afterthought: the app shell renders with `dir="rtl"`, Tailwind is used with logical properties (`ms-*`/`me-*` instead of `ml-*`/`mr-*`), and every shadcn/ui component pulled in during implementation gets checked against RTL specifically — menus, sliders, and date pickers are the usual places a "mostly RTL" library quietly breaks.

Internally, the codebase stays in English: enum values, field names, API payloads, database fields, and all Git/Jira artifacts (see section 12) use English identifiers (e.g. `GK`, `EDIT_FULL`). For most of these, the presentation layer translates them into Hebrew through the i18n dictionary. This keeps the engineering side of the project in English (consistent with working in an international-style codebase) while the product itself speaks the user's language.

> **Exception: football terminology that's already "English" in Hebrew.** Position abbreviations (`GK`, `CB`, `DM`, etc.) and formation notation (`4-3-3`, `4-2-3-1`) are displayed **as-is, in English**, everywhere in the UI — including on the tactical board. These aren't translated for the same reason a Hebrew-speaking coach wouldn't translate them out loud: they're the terms actually used pitch-side in Israeli football, and a literal Hebrew translation would be less recognizable than the English shorthand. The i18n layer treats them as pass-through values rather than translated strings — this is a deliberate exception, not a gap in localization.

Scraped content from `football.org.il` is already in Hebrew, so no translation step is needed for league tables, fixtures, or lineups pulled from there.

> ⚠️ **Verify early:** Tactical-board export (Konva.js canvas → image/PDF) needs to render Hebrew text and bidi content correctly — arrows and labels a coach types in Hebrew. Confirm the font and export pipeline handle this before relying on it in Phase 4, rather than discovering a garbled export late.

## 02. Architecture — modular monolith

Instead of the original document's design (a separate Gateway plus three microservices plus a message queue), we're moving to a **modular monolith**: one Spring Boot application, split internally into modules with clear boundaries. A real microservices split will only be considered later, once there's a proven need — for example, if the scraping service ends up needing independent scaling or a different release cadence than the rest of the system.

Tech Stack & Versions:
Backend: Java 21, Spring Boot 4.1, Maven (wrapper included, no local Maven install required), Spring Data MongoDB, Spring Security, Spring Data Redis, jjwt (JWT issuing/validation).
Frontend / client: React 19, TypeScript, Vite 8, Tailwind CSS, react-i18next (Hebrew RTL — see section 01), Zustand, TanStack Query, Vitest for tests.
Scraper: Node.js worker (Playwright + Cheerio) — currently a stub, see Phase 5 on the roadmap.
Infra / local dev: MongoDB + Redis via docker-compose.yml; CI on GitHub Actions.
Email: none yet — behind an EmailSender interface, provider TBD at deployment (Phase 6+).

```
Frontend (React + TS + Vite + Tailwind)
            │
            ▼  REST / HTTPS
┌─────────────────────────────────────────────┐
│  SquadPulse API — one Spring Boot monolith  │
│  auth · squad · tactics · training · match  │
│  scraping-integration · common              │
└─────────────────────────────────────────────┘
            │
            ▼
   MongoDB                    Redis
   (Clubs/Users/Players/      (refresh-token families —
    Matches/Trainings/         cache & rate limiting planned)
    TacticalBoards)
            ⇠ ⇢
   Scraper Worker (Node) — Playwright/Cheerio,
   run as Cron / internal endpoint
```

`RabbitMQ` is dropped for phase 1 — we start with a simple synchronous call / Cron job, and only add a message queue if scraping genuinely needs to run asynchronously.

## 03. Multi-tenancy

The system serves multiple clubs at once, with full isolation between them. The chosen approach is the **Pool model**: shared collections across all clubs (no duplicated tables/schemas per club), with every relevant document tagged with a `clubId` field.
Some Data may be shared for exameple league table (if both clubs are in the same league).

> **How isolation is actually enforced:** `clubId` is included as a claim in the JWT at login. A single central access layer (a Base Repository / Aspect in the `common` module) automatically injects a `clubId` filter into every query — so we don't rely on every endpoint "remembering" to add the filter itself. This is the single most critical thing to check in code review and in tests.
>
> **The one enforced exception:** custom repository methods bypass that layer, so each must include `ClubId` in its name (enforced at build time by an ArchUnit test). The sole escape hatch is the `@GloballyScoped` annotation, for lookups by a globally unique value when no club context exists yet — today only `UserRepository.findByEmail`, used at login to find out which club the caller belongs to (safe because email is unique across the whole system). Uses should be rare, and each one reviewed individually.

The Silo model (a separate database per club) was considered and rejected for now — the operational overhead (running migrations across N databases) is too high relative to the benefit for a small-to-medium number of clubs.

## 04. Roles & permissions

Access is managed along two separate axes: **Title** — the professional role shown in the UI (for display and organization), and **Permission Level** — the actual access level checked on every request. This separation makes it possible, for example, to later grant an "Assistant Coach" partial edit access without changing the role structure itself.

| Title | Default permission level | Note |
|---|---|---|
| Club Manager | `ADMIN` | Manages users, club settings, critical deletions |
| Head Coach | `EDIT_FULL` | Players, lineups, tactical boards, training, schedule |
| Assistant Coach | `VIEW_ONLY` | Can be upgraded to `EDIT_PARTIAL` individually later |
| Goalkeeping Coach | `VIEW_ONLY` | Can be upgraded individually later |
| Fitness Coach | `VIEW_ONLY` | Can be upgraded individually later |
| Analyst | `VIEW_ONLY` | Can be upgraded individually later |
| Player — future | `VIEW_ONLY` | Not in V1; may not get a login at all in the first phase |

Every user belongs to exactly one club (`clubId` on the User document and in the JWT). Inviting a new user and setting their Title + Permission Level requires `ADMIN` permission level — this is enforced by permission level, not by Title. In practice that means the Club Manager today, since Club Manager is the only Title that defaults to `ADMIN`, but any user holding `ADMIN` can do it.

## 05. Player entity

| Field | Type / range |
|---|---|
| Full name | Text |
| Primary position | See position list below |
| Secondary position | Optional, same list |
| Jersey number | 1–99, unique within the club, Optional |
| Date of birth / age | 18–99 (adult clubs only) |
| Height / weight | Numeric |
| Preferred foot | Right / left / both |
| Medical status | Fit / injured (V1); extended fields (injury type, expected return) later |
| Performance data | Minutes played, distance covered, speed, sprints — **phase 2+**, depends on the data source (Veo / chips) |

### Positions

`GK` Goalkeeper · `CB` Center Back · `RB` Right Back · `LB` Left Back · `DM` Defensive Midfielder · `CM` Central Midfielder · `AM` Attacking Midfielder · `RW` Right Winger · `LW` Left Winger · `ST` Striker

Shown in the UI as these English abbreviations, not translated to Hebrew — see the terminology exception in section 01.

## 06. Training

A calendar (monthly and weekly views) with the ability to create a new training session. Each session includes:

- Time and duration
- Participant list (drawn from the club's squad)
- Intensity (a simple scale, e.g. low/medium/high)
- Structured content — warm-up, technical drills, tactical drills, and more — as an ordered list of blocks
- Free-text notes

## 07. Matches, stats & scraping

A combination of automatic scraping and manual entry. The approved scraping scope from `football.org.il`: the current league table, the fixture list (list view + calendar view), lineups, jersey numbers, and playing minutes. Veo/chip data is planned for a future phase, subject to what can actually be extracted from those systems.

> **Terms-of-service check — result.** The site's `robots.txt` is fully open (`Allow: /` for every user-agent, no Crawl-delay). The terms of service that were reviewed contain no explicit clause prohibiting scraping/automated access — but they do reserve copyright over published content, and explicitly disclaim any responsibility for data accuracy. Practical takeaway: be a "good citizen" scraper (identifiable User-Agent, aggressive caching, don't hammer their server), don't republish logos/trademarks, and show a "last updated at X" timestamp on every scraped value — which already matches the resilience approach planned for the scraper.

## 08. Dashboard

The home screen shows clickable components: upcoming schedule, squad, league table, and stats. Clicking any component opens its dedicated, expanded screen.

## 09. New club onboarding

At this stage, only the system owner (Gal) can add a new club to the database, along with an initial Club Manager user for it. From there, any user with `ADMIN` permission level in that club (by default, its Club Manager — see section 04) invites additional users and sets their Title + Permission Level. Every user's actions are restricted strictly to the club they belong to.

A newly invited user is created with no password set — not even a temporary one. The invite immediately triggers the same email-delivered activation code described in section 10 ("Account activation / password reset"); the new user's first action in the app is entering that code together with a password of their own choosing. The `User.active` flag is unrelated to this first-activation state: it exists solely so an `ADMIN` can cut a departed staff member's access without deleting their account, and it is never toggled by the invite or activation flow itself. (Tracked separately as KAN-21; the KAN-19 invite endpoint today only creates the user with no password — it doesn't send anything yet.)

## 10. Security

- **Authentication — access token:** stateless JWT, HS256, signed with `JWT_SECRET` (env var, ≥32 characters). 15-minute TTL. Claims: `sub` (user id), `clubId`, `permissionLevel` — nothing sensitive. Rejected if expired, tampered, unsigned, `alg: none`, signed with the wrong key, from another issuer, or missing a required claim.
- **Authentication — refresh token:** an opaque, high-entropy random value (not a JWT), never stored in Mongo. Carried in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/auth`. 30-day TTL, SHA-256-hashed before being stored in Redis. Refresh tokens are grouped into per-login **families**: each `/auth/refresh` call rotates to a new token in the same family and invalidates the previous one; presenting a token that was already rotated away is treated as evidence of theft and revokes that entire family (ending that one login session everywhere it's used) — the user's other sessions/devices are unaffected. Logout only needs the refresh cookie, not a valid access token, so it still works after the access token has expired. Access tokens themselves can't be revoked early: after logout, reuse-triggered revocation, or an admin deactivating the account, an already-issued access token keeps working until it naturally expires (at most 15 minutes) — only refresh stops immediately.
- **Account activation / password reset:** email-delivered 6-digit code (15-minute TTL, max 5 attempts, rate-limited per email) — no password-reset links. Sits behind an `EmailSender` interface; no real email provider is chosen yet (a log-only stub is used until Phase 6+/deployment). The same mechanism serves both an existing user's "forgot password" and a newly invited user's first activation (see section 09).
- **Password hashing:** Argon2id (instead of bcrypt — more resistant to GPU/ASIC cracking), implemented via Spring Security's `Argon2PasswordEncoder`.
- **Pepper:** a fixed secret string, stored only as an environment variable (must be at least 32 characters & never in the DB or in code), combined with the password before hashing — so a DB leak alone isn't enough to crack it.
- **RBAC:** enforced by Permission Level (see section 04), combined with `clubId` filtering (see section 03), via Spring Security — `PermissionLevel` is mapped to a Spring Security authority, and protected endpoints are annotated `@PreAuthorize`. A `JwtAuthenticationFilter` validates the access token on every request and populates the request's `clubId` context from its claim; this is what makes the per-request filtering in section 03 actually apply.
- **Known accepted trade-off:** inviting a user whose email is already registered (in any club) returns `409 Conflict`, which tells an authenticated `ADMIN` that the email exists somewhere in the system. Accepted because the endpoint itself requires an authenticated `ADMIN` (it's not public), and email is unique system-wide by design (section 03).
- **Additional protections:** CORS restricted to approved domains, input validation on every endpoint, HTTPS everywhere, and automated dependency vulnerability scanning (Dependabot) in CI. Redis-backed rate limiting covers the activation/reset code above; `/auth/login` itself has no rate limiting or lockout yet — tracked as a follow-up.
- **Secrets management:** environment variables / a secrets manager only — no key, password, or pepper ever goes into git.

## 11. Testing & DevOps

A trimmed-down local environment: `docker-compose.yml` with just MongoDB + Redis (no RabbitMQ in phase 1). Backend tests use JUnit 5 + Mockito for units, and Testcontainers for integration. Frontend tests use Vitest + React Testing Library. CI on GitHub Actions runs lint, tests, and a Docker image build on every push.

> **Still open:** The actual deployment target (CD) hasn't been decided yet — Railway / Render / Fly.io for the backend, MongoDB Atlas, and static hosting for the frontend are the natural candidates for a lightweight monolith. We'll decide this later in the roadmap.

## 12. Git & Jira conventions

- **Branches:** `feature/KAN-123-short-desc` / `fix/KAN-124-...` — the ticket number always goes in the branch name.
- **Commits:** Conventional Commits — `feat(squad): add player creation endpoint (KAN-12)`.
- **PRs:** one ticket = one PR into master, even when working solo — it forces CI to run and leaves a real review history.
- **Jira workflow:** Backlog → To Do → In Progress → In Review → Done.
- **Proposed Epic structure:** Auth & Roles · Squad Management · Tactical Board · Training · Match & Scraping Integration · DevOps & Infra.

## 13. Phased roadmap

| Phase | Goal |
|---|---|
|✅ **0 — Project skeleton** | Private repo, package structure inside the monolith, linters, a basic GitHub Actions pipeline, Jira board. | *Except CI Pipeline
|✅ **1 — Local environment** | Docker Compose with MongoDB + Redis. | 
| **2 — Backend core** | Auth plus a single Player entity all the way to a real DB, with a unit test and an integration test from day one. |
| **3 — Frontend MVP** | Dashboard and squad table against the real API — the first "walking skeleton" that runs end to end. |
| **4 — Tactical board** | The Canvas module with Konva.js. |
| **5 — Scraping service** | A separate Node worker, fed manually / by Cron — by now there's actually something for it to feed. |
| **6+ — Hardening & deployment** | Full RBAC, multi-club load testing, monitoring, and deployment to a production environment. |

## 14. Open questions

- **[future]** The scope of Veo/chip integration isn't known yet — it depends on what can actually be pulled out of those systems.

---

*SquadPulse — a living working document, updated as development progresses.*
