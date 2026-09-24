# CLAUDE.md

This file is auto-loaded as standing context for every Claude Code session in this repo. It's the condensed version — for full detail, read `docs/spec.md` (or ask to read a specific section of it).

## What this project is

SquadPulse is a Hebrew-first (RTL) web platform for adult football clubs to manage squad, tactics, training, and match data from one place. Multi-club from day one, fully isolated per club. It's also a deliberate exercise in professional engineering practice: real Git workflow, Jira-driven planning, security, testing, and deployment — done properly, not skipped because it's a side project.

**Status:** Phase 0 (project skeleton). The backend, frontend and scraper skeletons exist (empty modules, tooling, smoke tests) — no feature code has shipped yet.
**Jira:** `squadpulse.atlassian.net`, project key `KAN`.
**Full spec:** `docs/spec.md` in this repo — always read that (not the old private artifact link) for anything beyond this summary.

## Standing rules — always follow these

1. **Ask before doing.** Before making a change with real consequences — creating/editing Jira issues, pushing commits, opening PRs, touching CI/deploy config, deleting anything — explain what you're about to do and wait for confirmation. Don't chain multiple actions without checking in. This applies until Gal says otherwise.
2. **English in code, Hebrew in product.** All code, comments, commit messages, branch names, Jira issues, and docs (including this file and `docs/spec.md`) are in English. The end-user-facing UI is in Hebrew. Never mix these up.
3. **Exception — football terms stay in English in the UI too.** Position codes (`GK`, `CB`, `DM`, ...) and formation notation (`4-3-3`) are shown as-is in English even in the Hebrew UI — see spec section 01. Don't "fix" this by translating them.
4. **`clubId` isolation is the most safety-critical thing in this codebase.** Every query that touches tenant data must go through the central `clubId`-filtering layer in the `common` module (see spec section 03). Any PR touching data access should be checked against this specifically. Custom repository methods must include `ClubId` in their name (ArchUnit-enforced); the sole escape hatch is `@GloballyScoped`, for lookups by a globally unique value with no club context yet (e.g. `UserRepository.findByEmail` at login) — rare, and each use reviewed individually.
5. **Nothing secret goes into git.** Passwords, the Argon2id pepper, API keys, tokens — env vars / secrets manager only. Never commit them, never hardcode them, never put them in Jira or docs either.
6. **i18n from day one.** All UI strings go through the i18n library, never hardcoded into components, even though there's currently only one supported language (Hebrew).
7. **Keep the docs in sync, in the same commit.** When a change affects architecture, module structure, setup/run steps, tooling or dependency versions, or conventions, update `README.md` and this file in that same commit — and re-check them against the change before committing. `docs/spec.md` is Gal's design document: don't edit it silently; if a change makes it out of date, flag exactly what and where, and let Gal decide.

## Architecture at a glance

Modular monolith (Spring Boot), not microservices — see spec section 02 for the reasoning and when a split would actually be reconsidered.

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
├── docs/spec.md                      # Full technical & product spec
└── CLAUDE.md                         # This file
```

Tech stack: React 19 / TS / Vite / Tailwind / shadcn/ui / TanStack Query / Zustand / Konva.js / Recharts on the frontend; Java + Spring Boot on the backend; MongoDB + Redis for storage; Node.js (Playwright/Cheerio) for the scraper; stateless JWT auth (Access token + HttpOnly-cookie Refresh token) with Argon2id + pepper for passwords; GitHub Actions for CI.

## Git & Jira conventions

- Branch: `feature/KAN-123-short-desc` or `fix/KAN-124-...` — always include the ticket key.
- Commit: [Conventional Commits](https://www.conventionalcommits.org/) with the ticket key, e.g. `feat(squad): add player creation endpoint (KAN-12)`.
- One ticket = one PR into `master`, even solo.
- Jira workflow: Backlog → To Do → In Progress → In Review → Done. GitHub↔Jira are linked, so branches/commits/PRs referencing a ticket key show up automatically on that ticket.

## Roadmap (current phase in bold)

Phase 0 — Project skeleton →  → Phase 1 (docker-compose: Mongo + Redis) → **Phase 2 (backend core: auth + Player entity)** → Phase 3 (frontend MVP) → Phase 4 (tactical board) → Phase 5 (scraping service) → Phase 6+ (hardening & deployment).

## When starting a session

Read this file (automatic) plus `docs/spec.md` if the task touches something not summarized above. The relevant Jira ticket's description carries the specific requirements for that task — this file carries context that doesn't change ticket to ticket.
