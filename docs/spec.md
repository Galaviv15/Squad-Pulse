# SquadPulse — Technical & Product Spec

**Version:** v3 · draft
**Updated:** Oct 6, 2026
**Status:** Phase 2 in progress. Auth & roles are done (epic KAN-10). The Player entity (KAN-25), the squad API for listing, getting, creating and updating players (KAN-26), releasing, re-activating and permanently deleting players (KAN-27), the squad summary (KAN-28) and player photos (KAN-29) are in place, as are the current-user endpoint `GET /auth/users/me` (KAN-34, the first ticket of epic KAN-33, Club & Staff Administration), the club logo (KAN-30), the staff photo (KAN-32), the staff list `GET /auth/users` (KAN-36), user deactivation / re-activation (KAN-37) and club settings `GET` / `PATCH /clubs/me` (KAN-38). The Frontend MVP (Phase 3, epic KAN-43) has started: the frontend infrastructure is in place (KAN-45: routing, theme, dev proxy, test setup), with its API client and session (KAN-46) and the login, forgot-password and set-password / activation screens (KAN-47), and the app shell that every protected screen renders in (KAN-48: sidebar navigation, top bar with the page title and the current user); the first product screen, the squad table, is done too (KAN-49), as are the player card and the add / edit player form (KAN-50), releasing, re-activating and permanently deleting players and managing their photo in the frontend (KAN-59), the dashboard (KAN-51) and the squad's cards view (KAN-52). The scraper is still a skeleton.
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

RTL is a first-class layout requirement, not an afterthought: the app shell renders with `dir="rtl"`, Tailwind is used with logical properties (`ms-*`/`me-*` instead of `ml-*`/`mr-*`), and every shadcn/ui component pulled in during implementation gets checked against RTL specifically — menus, sliders, and date pickers are the usual places a "mostly RTL" library quietly breaks. The shadcn/ui CLI runs in its RTL mode (`"rtl": true` in `components.json`), which rewrites generated components to logical classes, and the whole app is wrapped in a `DirectionProvider` set to RTL, because the underlying Base UI components (menus, popovers, sliders) otherwise default to LTR. Neither replaces the per-component check (KAN-45). The app shell (KAN-48) puts the navigation sidebar on the start side, the right in RTL. The visual design (theme tokens, typography, density and UI conventions) is in `docs/design/ui-conventions.md` (KAN-44).

Internally, the codebase stays in English: enum values, field names, API payloads, database fields, and all Git/Jira artifacts (see section 12) use English identifiers (e.g. `GK`, `EDIT_FULL`). For most of these, the presentation layer translates them into Hebrew through the i18n dictionary. This keeps the engineering side of the project in English (consistent with working in an international-style codebase) while the product itself speaks the user's language.

> **Exception: football terminology that's already "English" in Hebrew.** Position abbreviations (`GK`, `CB`, `DM`, etc.) and formation notation (`4-3-3`, `4-2-3-1`) are displayed **as-is, in English**, everywhere in the UI — including on the tactical board. These aren't translated for the same reason a Hebrew-speaking coach wouldn't translate them out loud: they're the terms actually used pitch-side in Israeli football, and a literal Hebrew translation would be less recognizable than the English shorthand. The i18n layer treats them as pass-through values rather than translated strings — this is a deliberate exception, not a gap in localization.

Scraped content from `football.org.il` is already in Hebrew, so no translation step is needed for league tables, fixtures, or lineups pulled from there.

> ⚠️ **Verify early:** Tactical-board export (Konva.js canvas → image/PDF) needs to render Hebrew text and bidi content correctly — arrows and labels a coach types in Hebrew. Confirm the font and export pipeline handle this before relying on it in Phase 4, rather than discovering a garbled export late.

## 02. Architecture — modular monolith

Instead of the original document's design (a separate Gateway plus three microservices plus a message queue), we're moving to a **modular monolith**: one Spring Boot application, split internally into modules with clear boundaries. A real microservices split will only be considered later, once there's a proven need — for example, if the scraping service ends up needing independent scaling or a different release cadence than the rest of the system.

Tech Stack & Versions:
Backend: Java 21, Spring Boot 4.1, Maven (wrapper included, no local Maven install required), Spring Data MongoDB, Spring Security, Spring Data Redis, jjwt (JWT issuing/validation).
Frontend / client: React 19, TypeScript, Vite 8, Tailwind CSS 4, shadcn/ui (on Base UI, RTL mode), React Router 8 (data router; every SPA route lives under `/app`, so it never collides with the backend's `/auth`, `/squad`, `/clubs` and `/users` paths on a browser reload), react-i18next (Hebrew RTL — see section 01), Zustand, TanStack Query, the Heebo font (self-hosted), ESLint 10 (flat config), and Vitest with MSW (mocked API) for tests. In development the Vite dev server proxies the backend's paths, so the app calls the API on its own origin (KAN-45). Every call to the backend goes through one API client (`src/lib/api`, KAN-46), which attaches the access token, refreshes it when needed (section 10), parses the error shape and fetches images as blobs with the token. Every `/app` screen needs a login except the three public auth screens (KAN-47): login (`/app/login`), forgot password (`/app/forgot-password`) and set a password with an emailed code (`/app/reset-password`, which is also an invited user's activation). On load the app restores the session with a single refresh before it shows anything, so neither the login form nor protected content flashes, then loads `GET /auth/users/me`; if the backend can't be reached it shows a "no connection" screen with a retry, not the login form. Every protected screen renders inside the app shell (KAN-48): a sidebar with the club's logo and name, the live sections (the dashboard at `/app` and the squad at `/app/squad`, whose screens are KAN-51 and KAN-49, with KAN-52's cards view) and the sections not built yet (schedule, training, matches, tactical board), which are shown disabled under "coming soon" and are never links; and a top bar with the page title (also the browser tab's title), the current user's photo, name and Title, and logout. An unknown `/app` path shows a not-found page inside the shell. The club logo and the user's photo are requested only when `/me` says they exist (`club.hasLogo`, `hasPhoto`); without one, while it loads, or if it fails, the shell shows initials instead. The dashboard (`/app`, KAN-51) shows the club's active players from `GET /squad/summary` (section 05): how many there are, with a link to the squad table; their average age, with one decimal, or a dash when no active player has a date of birth; and a donut of the players per line, drawn in plain SVG with no chart library, with a legend that gives each line's name and count, never percentages. The donut's center shows the number of active players, which can be larger than the sum of the lines (a player without a primary position is in no line). The dashboard's other cards (next training, next match, league table and the weekly schedule) are "coming soon" placeholders, with no data and no links. The squad screen (`/app/squad`, KAN-49) shows the club's players from `GET /squad/players` (section 05) as a table or, switched with a list / cards toggle, as a grid of player cards (KAN-52). It has a status control (active / released / all), filters for primary position, age range, medical status and preferred foot, the number of players shown, and each player's photo (requested only when `hasPhoto`; initials otherwise), in the server's order, never re-sorted. Both views show the same list, fields, filters and count, and switching between them doesn't reload the list. The filters are kept in the page URL under the API's own parameter names, so a reload or a shared link keeps them; a value the API would reject (an unknown value, an age outside 18–99, a minimum above the maximum) is dropped and the URL corrected, never sent. The chosen view is kept in the URL too (`view=cards`; the default list is never written, and any other value is dropped), but it's a page setting, not a filter: it's never sent to the API, and clearing the filters keeps it. Ages are computed in the browser from the date of birth by the server's rule (a 29 February birthday counts on 1 March in non-leap years) but with the browser's date, so around midnight on a birthday a shown age can differ by one from the server's age filter. Released players are dimmed and marked. The "add player" button is shown only from `EDIT_FULL` up. The player card (`/app/squad/{id}`, KAN-50) shows all of a player's fields, their photo and whether they're released, to every user. Adding (`/app/squad/new`) and editing (`/app/squad/{id}/edit`) a player use one form, open only from `EDIT_FULL` up: anyone below sees a "no permission" message there, and nothing is requested. The form checks the same field rules as the API before sending and shows the server's field errors on their fields. It tells apart a taken jersey number, a player changed by someone else since the form was opened (with a button that loads the latest version, replacing the user's edits) and a released player by the error's `code` (section 10), never by its message. The edit form is filled only from a fresh load made when it opens, never from an older copy the app already holds, and a later background refresh never overwrites what the user typed; the `version` it sends is the one the form was filled from. A released player has no edit action, and its edit page shows a message instead of the form. After a save the app shows the player's card, and all the squad data it holds is refreshed. Each row of the squad table, and each card, has an actions menu: open the card; edit (from `EDIT_FULL` up, active players only); release or re-activate (from `EDIT_FULL` up); and, last and set apart, permanent deletion (`ADMIN` only). The player card offers the same actions (KAN-59). Release, re-activation and permanent deletion are each confirmed in a dialog, and an action the user's level doesn't allow isn't shown. Re-activation asks for the jersey number, prefilled with the player's old one (empty means none), and shows a taken number on that field. A release or re-activation that finds the player changed since it was loaded, or already in that state, says so and offers to load the latest data. After a permanent deletion from the card the app shows the squad instead, without leaving the deleted card in the browser history, with a one-time notice naming the player; a deletion that finds the player already gone is treated as done. From `EDIT_FULL` up, an active player's photo can be uploaded, replaced or removed on the card (removal is confirmed). The browser checks the type (JPEG, PNG or WebP) and the 2 MB limit before sending; the server's checks still apply. A replaced photo shows at once, without a reload. The squad screens are loaded on first visit as a separate code chunk; if a page inside the shell fails to load or render, an error with a reload button is shown inside the shell. Over plain `http://localhost`, Chrome keeps the `Secure` refresh cookie but Safari doesn't (verified with Safari 26.5.2), so Safari can't refresh locally: a page reload, or the access token's 15-minute expiry, logs you out. Serving the dev server over HTTPS is KAN-56; until then, develop in Chrome.
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
   (Clubs/Users/Players/      (refresh-token families,
    Matches/Trainings/         login throttling, reset codes)
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
> **The one enforced exception:** custom repository methods bypass that layer, so each must include `ClubId` in its name (enforced at build time by an ArchUnit test). The sole escape hatch is the `@GloballyScoped` annotation, for lookups by a globally unique value when no club context exists yet — today only `UserRepository.findByEmail`, used at login to find out which club the caller belongs to (safe because email is unique across the whole system). Uses should be rare, and each one reviewed individually. Players deliberately have **no** globally scoped lookup — see section 05.
>
> **Filtering happens in memory, on club-scoped loads.** A query built by hand against `MongoTemplate` would bypass that layer too, and nothing — no test, no ArchUnit rule — would catch a missing `clubId` criterion in it. So list filters (e.g. the squad filters in section 05) load the club's records through a club-scoped repository method and filter and sort them in Java. At a squad's size (30–40 players) this costs nothing; if a future list is too large for this, its query needs the same review as a `@GloballyScoped` finder.
>
> A second, deliberate bypass exists outside the repository layer: `UserVersionBackfill` (KAN-24), a startup schema backfill that sets a `version` field on `users` documents missing one, across all clubs. It filters and writes only that field and reads no tenant data. It can be removed once every environment has been backfilled.
>
> **A third bypass, confined by design: the image store** (KAN-29). Images (player photos, the club logo and staff photos) live in MongoDB GridFS, in the `images` bucket, which is not a Spring Data repository, so the central layer doesn't protect it. Instead, every image goes through one interface, `common.ImageStorage`, which takes no `clubId`: its only implementation, `GridFsImageStorage` (package-private in `common`), stores the caller's `clubId` (from the request context), the image kind and the owner's id as metadata on every file, and builds every query through a single helper that adds the `clubId` criterion. An ArchUnit rule forbids GridFS anywhere outside `common`. The interface uses no GridFS types, so the store can move to object storage (S3/R2, Phase 6) by replacing the implementation only.

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

In the UI each Title is shown in Hebrew, in the masculine form, since the system stores no gender (KAN-48): Club Manager = מנהל מועדון, Head Coach = מאמן ראשי, Assistant Coach = עוזר מאמן, Goalkeeping Coach = מאמן שוערים, Fitness Coach = מאמן כושר, Analyst = אנליסט.

Every user belongs to exactly one club (`clubId` on the User document and in the JWT). Inviting a new user and setting their Title + Permission Level requires `ADMIN` permission level — this is enforced by permission level, not by Title. In practice that means the Club Manager today, since Club Manager is the only Title that defaults to `ADMIN`, but any user holding `ADMIN` can do it.

An `ADMIN` can change another user's Permission Level in their club (`PATCH /auth/users/{id}/permission-level`), including granting or removing `ADMIN`. A user can never change their **own** level, so a club's only `ADMIN` can't lock the club out by demoting themselves. The change takes effect at the target's next token refresh, at most one access-token lifetime (15 minutes).

An `ADMIN` can deactivate another user of their club (`POST /auth/users/{id}/deactivate`) and re-activate them (`POST /auth/users/{id}/reactivate`) (KAN-37). Deactivation is how a departed staff member's access is cut without deleting the account. Neither endpoint takes a body, and both return `200` with the user response. A user who is already in the requested state gets a `200` with their current state, and nothing is written. Players are different here (releasing a released player is a `409`): a player change carries the `version` the client loaded, while these are absolute commands with no version. A user can never deactivate or re-activate **themselves** (`409`), the same rule as the permission level, so a club's only `ADMIN` can't lock the club out. Deactivating another `ADMIN` is allowed. A user who doesn't exist or belongs to another club gets `404`, also when they are already in the requested state. Deactivation ends all of the user's refresh sessions (section 10). From then on, login, refresh, forgot-password, password reset and `/me` are refused, and the user's photo can't be changed. Everything else is kept: password, Permission Level, Title, photo and their entry in the staff list. Re-activation also ends any refresh session left from before the deactivation, sends no email and needs no new password. A user who never activated can be deactivated and re-activated too, and still has to activate.

Any authenticated user can read their own profile with `GET /auth/users/me`; a deactivated or deleted user gets a `401` instead, even with a still-valid access token (see section 10). There is no id in the path: it always answers about the caller, identified by the access token. It returns the user's `id`, `email`, `fullName`, `title`, `permissionLevel`, `dateOfBirth`, `active` (always `true` in a successful response), `hasPhoto` (whether `GET /users/me/photo` has an image) and `activated` (always `true`: a user without a password can't log in), plus their club as `club { id, name, hasLogo }`, and nothing else: no password, version, session or timestamp field, and no top-level `clubId`. Its `permissionLevel` is the caller's **effective** level, taken from the access token: what the server enforces on that request, not the stored value. So after a level change, `/me` shows the new level only after the caller's next `/auth/refresh`, the same moment the change takes effect everywhere else. Every other field is read from the database. The frontend uses it for the app header and to hide actions the caller can't perform. It loads it after every login and every app load, before any protected screen renders (KAN-47).

Any authenticated user can read their club's settings with `GET /clubs/me`, and an `ADMIN` can change them with `PATCH /clubs/me` (KAN-38). "me" is always the club in the caller's access token: there is no club id in the path, and none is read from the body. Unknown body fields, such as an `id` or `clubId`, are ignored. Today the only setting is the club's **name**. `PATCH` is a partial update: future settings will be optional fields of the same body, where an absent field means "unchanged". While the name is the only setting, the body must contain it: `{"name": "..."}`. The name is trimmed, then must be 1–100 characters, otherwise `400`. Both endpoints return `200` with `{ id, name, hasLogo }`, the same object as `/me`'s `club`. `PATCH` returns the club as stored after the change. There is no `version` and no `409`: two renames at the same time both succeed, and the later one is kept (section 10). `PATCH` re-checks that the caller is still an active user (section 10). The path deliberately doesn't start with `/auth`, for the same refresh-cookie reason as the logo below.

A club can have an optional **logo** (`PUT` / `GET` / `DELETE /clubs/me/logo`, permissions in the table below). It is always the caller's own club, taken from the access token: there is no club id in the path. Setting or removing it needs `ADMIN`, because it is a club setting; any authenticated user can read it. Upload, serving and limits follow the player photo rules (section 05): one multipart `file` part, JPEG, PNG or WebP recognized by content, at most 2 MB (`413` above that), `400` for an empty file, another format (SVG included), a missing part or a non-multipart request. An upload replaces the previous logo (`204`). `GET` returns the image with its detected type, only to an authenticated request, or `404` when the club has no logo. `DELETE` returns `204`, also when there was none. The logo is kept in the image store (section 03), not on the club document, which an upload or delete never changes. Unlike a player, a club has no released state and can't be deleted, so nothing here is a `409`. If clubs ever become deletable, deleting one must also delete its logo. The path deliberately doesn't start with `/auth`: the refresh-token cookie is scoped to `/auth` (section 10), and the logo, fetched on every app load, must not carry it. `/me` reports whether the club has one in `club.hasLogo`, so the frontend asks for the image only when there is one.

Every staff user can have an optional **profile photo** (KAN-32): `PUT` / `GET` / `DELETE /users/me/photo` for the caller's own, and `PUT` / `GET` / `DELETE /users/{id}/photo` for any user of the caller's club (permissions in the table below). `me` is always the user id from the access token, never read from the request. Any authenticated user may set and remove **their own** photo, so these are the only writes open to `VIEW_ONLY`: it is the caller's own profile, never club data or another user's. Setting or removing another user's photo needs `ADMIN`; any authenticated user of the club can read anyone's. A user id that doesn't exist or belongs to another club gets `404`. Upload, serving and limits follow the player photo rules (section 05), exactly as for the logo above. A deactivated user's photo can still be read, but setting or removing it is a `409`, whoever the caller is, including the deactivated user themselves with a still-valid access token (the same rule as a released player); deactivation keeps the photo. The photo is kept in the image store (section 03), never on the `User` document, which a photo upload or delete never writes, so it never changes the user's `version` (section 10). Users can't be permanently deleted today; if that is ever added, it must delete the user's photo too, and the upload must re-check the user after storing, as the player photo does. The paths deliberately don't start with `/auth`, for the same refresh-cookie reason as the logo. The user responses of `/me`, `POST /auth/users/invite`, `PATCH /auth/users/{id}/permission-level` and deactivate / re-activate carry `hasPhoto` right after `active` (always `false` on an invite), so the frontend asks for the image only when there is one.

An `ADMIN` can list their club's staff users with `GET /auth/users` (KAN-36). It is `ADMIN`-only because it is a management screen, not a staff directory: it exposes emails, dates of birth, permission levels and deactivated users. If a directory for every user is ever needed, it gets its own, slimmer response. The response is a plain JSON array of the same user response as invite and permission-level change, with no pagination, wrapper or query parameters (a club has a handful of staff users). It includes every user of the caller's club: the caller, deactivated users (`active: false`) and invited users who haven't set a password yet (`activated: false`), and never a user of another club. The order is active users first, then by `fullName`, then by `id`, in plain string order (no Hebrew collation, like the squad list). It costs two club-scoped queries, one for the users and one for which of them have a photo, and never writes a user. The path is under `/auth`, so the refresh cookie (section 10) is sent with it, accepted as for `/me`: it isn't an image and isn't fetched in bulk.

Every user response (`/me`, the list, invite, permission-level change, deactivate / re-activate) ends with `activated`, after `hasPhoto` (and before `club` on `/me`): `true` once the user has set a password through the activation code (section 09), `false` until then. It is independent of `active`: an invited user who hasn't activated is `active: true, activated: false`, and deactivation or re-activation never changes `activated`. The password hash itself is never exposed.

`EDIT_PARTIAL` is assignable but not yet required by any endpoint (today it grants the same as `VIEW_ONLY`). Its scope is deliberately left undefined for now — it is not part of the Squad Management epic (KAN-11), and will be decided in a future ticket.

Every endpoint declares the **minimum** permission level it requires; the levels are ordered (`ADMIN` > `EDIT_FULL` > `EDIT_PARTIAL` > `VIEW_ONLY`), so each level also admits every level above it.

| Endpoint | Minimum level |
|---|---|
| `POST /auth/users/invite` | `ADMIN` |
| `PATCH /auth/users/{id}/permission-level` | `ADMIN` |
| `POST /auth/users/{id}/deactivate` | `ADMIN` |
| `POST /auth/users/{id}/reactivate` | `ADMIN` |
| `GET /auth/users` (the club's staff list) | `ADMIN` |
| `GET /auth/users/me` (the caller's own profile) | `VIEW_ONLY` |
| `GET /clubs/me` (club settings) | `VIEW_ONLY` |
| `PATCH /clubs/me` (club settings) | `ADMIN` |
| `PUT /clubs/me/logo` | `ADMIN` |
| `GET /clubs/me/logo` | `VIEW_ONLY` |
| `DELETE /clubs/me/logo` | `ADMIN` |
| `PUT /users/me/photo` (own photo) | `VIEW_ONLY` |
| `GET /users/me/photo` | `VIEW_ONLY` |
| `DELETE /users/me/photo` (own photo) | `VIEW_ONLY` |
| `PUT /users/{id}/photo` | `ADMIN` |
| `GET /users/{id}/photo` | `VIEW_ONLY` |
| `DELETE /users/{id}/photo` | `ADMIN` |
| `GET /squad/players` (list, with filters) | `VIEW_ONLY` |
| `GET /squad/players/{id}` | `VIEW_ONLY` |
| `POST /squad/players` | `EDIT_FULL` |
| `PUT /squad/players/{id}` (incl. medical status) | `EDIT_FULL` |
| `POST /squad/players/{id}/release` | `EDIT_FULL` |
| `POST /squad/players/{id}/reactivate` | `EDIT_FULL` |
| `DELETE /squad/players/{id}` (permanent) | `ADMIN` |
| `PUT /squad/players/{id}/photo` | `EDIT_FULL` |
| `GET /squad/players/{id}/photo` | `VIEW_ONLY` |
| `DELETE /squad/players/{id}/photo` | `EDIT_FULL` |
| `GET /squad/summary` | `VIEW_ONLY` |

Releasing and re-activating a player need only `EDIT_FULL`, but permanently deleting one needs `ADMIN`: an `EDIT_FULL` user can release a player, but never delete one.

## 05. Player entity

| Field | Type / range |
|---|---|
| Full name | Text, required, 1–100 characters, stored trimmed. One field, not first/last (Hebrew names don't split cleanly) |
| Primary position | Required; see position list below |
| Secondary position | Optional, same list; must differ from the primary position |
| Jersey number | Optional, 1–99; unique among the club's **active** players (enforced by a database index, see below) |
| Date of birth | Required; age 18–99 (adult clubs only). Age is always derived from it, never stored |
| Height | Optional, whole centimetres, 140–220 |
| Weight | Optional, whole kilograms, 40–150 |
| Preferred foot | Optional: right / left / both |
| Medical status | Fit / injured (V1), defaults to fit; extended fields (injury type, expected return) later |
| Active | Whether the player is currently on the club's squad; defaults to true (see "Leaving the club" below) |
| Photo | Optional, one per player. Stored outside the player document (in the image store, section 03), so changing it never changes the player's `version`. Player responses carry `hasPhoto` |
| Performance data | Minutes played, distance covered, speed, sprints — **phase 2+**, depends on the data source (Veo / chips) |

Players use optimistic locking (`@Version`) from day one, like `User` (see section 10).

### A player belongs to one club

A player is a **club-scoped roster record**, not a global person. If the same real person moves from club A to club B, A's record is released and stays in A as part of A's history (its training sessions, lineups and injuries), and B creates its own, fully independent record. The system doesn't know the two records are the same person, and neither club can see or edit the other's record — this is ordinary `clubId` isolation (section 03), with no cross-club field and no globally scoped player lookup.

If players ever get logins (see section 04, "Player — future"), the intended extension point is an optional link from a player record to the global `User` account (whose email is unique system-wide). Each club would still see only its own record.

### Leaving the club, and permanent deletion

- **Leaving the club is a soft delete:** the player is released (`active = false`) rather than deleted, so references to them from training sessions and lineups stay valid. A released player can be re-activated, back into the same club only (a player who moved to another club is a separate record there — see above).
- **Jersey numbers** are unique only among a club's active players. This is guaranteed by a partial unique index on `(clubId, jerseyNumber)` in the database, not by a check in code, so concurrent writes can't both take the same number.
  - **Releasing keeps the number on the record** as history; it just stops being reserved, so another player can take it.
  - **Re-activation sets the number from the request** — a full replacement, like an update: a missing or `null` number means the player comes back without one, not "keep the old one". The client pre-fills the old number. Since a released player can't be edited, this is the only way to change a released player's number, so a player whose old number has since been taken can still come back.
  - **A taken number is rejected** (`409 Conflict`, code `JERSEY_NUMBER_TAKEN`, also under a race) and the player stays released. There is deliberately no automatic fallback to "no number": the user decides.
- **Permanent deletion** exists only for records created by mistake, and requires `ADMIN`. It works on active and released players alike. It is deliberately **not** version-checked: a deletion may win over a concurrent edit, and a deletion racing a concurrent deletion of the same player still succeeds (`204`), while deleting a player that is already gone answers `404` (the frontend treats that as done, KAN-59). Once training sessions or lineups reference players, permanent deletion of a referenced player must be refused with `409 Conflict` (not enforced yet — nothing references players today). Permanent deletion also deletes the player's photo, photo first: a failure in between leaves a player without a photo, which a retry of the deletion cleans up, never a file that no longer belongs to any player.

### Squad API behaviour

- **Update is a full replacement** (`PUT`): every editable field is sent; an optional field that's missing or `null` clears the stored value, and the medical status is required. Neither create nor update can set `active` or `clubId` — such fields in the body are ignored.
- **Stale edits are refused.** An update carries the `version` the client loaded. If the player has been saved since — detected either by that check or by the save itself losing a race — the answer is `409 Conflict` (code `STALE_VERSION`) with a "reload and apply your changes again" message, and the edit is never retried on the server (see section 10).
- **A released player is read-only** until re-activated: it can be fetched by id (`active: false`), but an update returns `409 Conflict` (code `PLAYER_RELEASED`).
- **Release and re-activation** (`POST /squad/players/{id}/release`, `POST /squad/players/{id}/reactivate`) carry the `version` the client loaded, with the same stale-version `409` as an update, and return the updated player. Releasing an already released player, or re-activating an already active one, is a `409 Conflict` (code `PLAYER_ALREADY_RELEASED` / `PLAYER_ALREADY_ACTIVE`), not a silent success. A missing body or missing `version` is a `400`.
- **A jersey number already taken** by an active player of the club returns `409 Conflict` (code `JERSEY_NUMBER_TAKEN`), also when two writes race (the database index decides).
- **Another club's player** is simply not found (`404`), exactly like an id that doesn't exist.
- **List filters** (all optional, combined with AND): status `active` (default) / `released` / `all`; position — matches the **primary** position only; age range `minAge`–`maxAge`, inclusive, each 18–99; medical status; preferred foot. Filtering runs in memory on the club's own players (section 03).
- **List order** is fixed: primary position (GK → ST), then jersey number (players without one last), then name. No pagination.
- **Squad summary** (`GET /squad/summary`) covers the club's **active** players only, whatever their medical status. It returns `playerCount`, `averageAge` and `lines`:
  - `lines` always has all four lines (see "Positions" below), in the order `GOALKEEPERS`, `DEFENSE`, `MIDFIELD`, `ATTACK`, with `0` for an empty one. Each player counts once, by **primary position only**, so for data written through the API the four add up to `playerCount`.
  - `averageAge` is the mean of each player's **exact** age: completed years plus the elapsed fraction of the current birthday year. Averaging whole years would skew it down by about half a year. A 29 February birthday counts as 1 March in non-leap years, the same as for the age filters, so the whole-number part always matches them. It is rounded half up to one decimal.
  - A player without a date of birth (only possible in data not written through the API) is counted but left out of the average. `averageAge` is `null` when no active player has one, including an empty squad.
  - Like the list filters, it is computed in memory on the club's own players.
- **Player photo** (`PUT` / `GET` / `DELETE /squad/players/{id}/photo`, permissions in section 04):
  - Upload is a multipart request with one `file` part, and replaces any existing photo (`204`). Only JPEG, PNG and WebP are accepted, recognized by the file's **content** (its leading bytes), never by its name or the client's declared type. At most 2 MB: a larger file is `413 Content Too Large`. An empty file, another format (SVG included), a missing `file` part or a request that isn't multipart is a `400`.
  - `GET` returns the image itself with its detected type, only to an authenticated request (there's no public URL), or `404` when the player has no photo. `DELETE` returns `204`, also when there was no photo.
  - A **released player's photo is read-only**, like the rest of the record: upload and delete are `409 Conflict` (code `PLAYER_RELEASED`), `GET` still works, and release and re-activation keep the photo.
  - Another club's player, or an unknown one, is `404`, checked before the image store is touched. An invalid upload is still a `400` / `413` first, as for any invalid request body, which reveals nothing about the player.
  - A player has exactly one current photo, even when two uploads race; this is done by ordering the stored files, without a transaction. An upload that races a permanent deletion of its player removes its own file again (`404`); a tiny remaining window that can still leave an unreachable file is documented in the code.
  - A list sets `hasPhoto` with one image-store query for the whole list, not one per player.

### Positions

`GK` Goalkeeper · `CB` Center Back · `RB` Right Back · `LB` Left Back · `DM` Defensive Midfielder · `CM` Central Midfielder · `AM` Attacking Midfielder · `RW` Right Winger · `LW` Left Winger · `ST` Striker

Each position belongs to one **line**, which the squad summary groups by: goalkeepers (`GK`); defense (`CB`, `RB`, `LB`); midfield (`DM`, `CM`, `AM`); attack (`RW`, `LW`, `ST`). The mapping is defined in one place in the code (`Position.line()`).

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

The home screen shows clickable components: upcoming schedule, squad, league table, and stats. Clicking any component opens its dedicated, expanded screen. In the MVP (KAN-51) only the squad component has data, from `GET /squad/summary` (section 05), and only its active-players figure links to its screen (the squad table); next training, next match, the league table and the weekly schedule are shown as non-clickable "coming soon" placeholders, and there are no stats yet (section 02). In the frontend the dashboard is the home route `/app`, and the main navigation between screens is the app shell's sidebar (section 02, KAN-48).

## 09. New club onboarding

At this stage, only the system owner (Gal) can add a new club to the database, along with an initial Club Manager user for it. The club name follows the same rule as renaming it (section 04): trimmed, then 1–100 characters. From there, any user with `ADMIN` permission level in that club (by default, its Club Manager — see section 04) invites additional users and sets their Title + Permission Level. Every user's actions are restricted strictly to the club they belong to.

A newly invited user is created with no password set — not even a temporary one. The invite immediately triggers the same email-delivered activation code described in section 10 ("Account activation / password reset"); the new user's first action in the app is entering that code together with a password of their own choosing. The `User.active` flag is unrelated to this first-activation state: it exists solely so an `ADMIN` can cut a departed staff member's access without deleting their account (deactivate / re-activate, section 04), and it is never toggled by the invite or activation flow itself. Until they set their password, the invited user shows as `activated: false` in the user responses and the staff list (section 04). The email carries only the code, no link, so the app's login screen links to the set-password screen (an "enter a code and set a password" link); setting the password there logs the user straight in (KAN-47). If the code expires first, they request a new one through "forgot password".

## 10. Security

- **Authentication — access token:** stateless JWT, HS256, signed with `JWT_SECRET` (env var, ≥32 characters). 15-minute TTL. Claims: `sub` (user id), `clubId`, `permissionLevel` — nothing sensitive. Rejected if expired, tampered, unsigned, `alg: none`, signed with the wrong key, from another issuer, or missing a required claim. The browser app keeps it in memory only (never in `localStorage`, `sessionStorage` or a readable cookie), so after a page reload the session is restored through the refresh cookie.
- **Authentication — refresh token:** an opaque, high-entropy random value (not a JWT), never stored in Mongo. Carried in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/auth`. 30-day TTL, SHA-256-hashed before being stored in Redis. Refresh tokens are grouped into per-login **families**: each `/auth/refresh` call rotates to a new token in the same family and invalidates the previous one; presenting a token that was already rotated away is treated as evidence of theft and revokes that entire family (ending that one login session everywhere it's used) — the user's other sessions/devices are unaffected. The browser app (KAN-46) therefore refreshes carefully. It refreshes only when a request that carried an access token gets a `401`, then retries that request once. The public endpoints (`/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/forgot-password`, `/auth/reset-password`) are never sent the access token, so their `401` (a wrong password or reset code) never triggers a refresh or a resend; a `403` never triggers one either. Concurrent `401`s share a single refresh within a tab, and refreshes are serialized across tabs with a Web Lock, because two refreshes sent at once with the same cookie would look like reuse and end the session. A refresh is never aborted: a cancelled refresh could leave the server rotated but the browser holding the old cookie. A refresh refused with a `4xx` ends the session (the user goes to login); a `5xx` or a network failure keeps it, so a backend blip doesn't log anyone out, and the next `401` tries again. A refresh that finishes after the session ended, or after a new login started, is discarded and never brings the old session back. Accepted gaps: a page closed or reloaded while a refresh is in flight can still lose the rotated cookie, ending that session (see section 14); and a refresh from another tab, or one that starts while a login request is already in flight, can still finish after that login. On app load the browser app sends exactly one refresh to restore the session: a `4xx` shows the login screen, while a `5xx` or no response shows a "no connection" screen with a retry instead, since the cookie may still be valid (KAN-47). Whenever the session ends, the app clears all the server data it has cached. An explicit logout also ends the session in the app's other open tabs: they get a `BroadcastChannel` message and drop their in-memory access tokens, without sending a second logout. A session that ends by itself isn't broadcast. Logout only needs the refresh cookie, not a valid access token, so it still works after the access token has expired. Access tokens themselves can't be revoked early: after logout, reuse-triggered revocation, or an admin deactivating the account, an already-issued access token keeps working until it naturally expires (at most 15 minutes) — only refresh stops immediately. One deliberate exception: `GET /auth/users/me` re-reads the caller and answers the generic `401` (`Authentication required`, the same body as a request with no token) when the user has been deactivated or deleted, or isn't in the token's club. Because the frontend calls it on every app load, a deactivated user is sent to login rather than continuing on a still-valid token. It doesn't check `sessionsInvalidatedAt` or the password, so access tokens still survive a password reset, as below. A second exception: every user-management write (invite, permission-level change, deactivate, re-activate) and the club-settings write (`PATCH /clubs/me`, KAN-38) re-read the caller first and answer the same generic `401` if they have been deactivated or deleted. This stops a deactivated `ADMIN` from using a still-valid token to deactivate or demote the club's remaining admins, or to rename the club. It is checked once per request, so two admins deactivating each other at the very same instant could both pass it (accepted). The read-only staff list, `GET /clubs/me` and every other endpoint don't re-check the caller. That includes the club-logo and staff-photo writes, which are cosmetic and reversible: a deactivated `ADMIN`'s still-valid access token can change the logo or another user's photo until it expires. A staff-photo write still checks its target, though, so a deactivated user can't change their own photo (`409`, section 04). Deactivation and re-activation both set `User.sessionsInvalidatedAt`, which ends every refresh session the user had. Refresh already refuses a deactivated user, but a refresh cookie that isn't presented while the user is deactivated would otherwise come back to life on re-activation. Setting it again on re-activation also covers users deactivated directly in the database, who never got the timestamp. The value never moves backwards: if a concurrent password reset stored a later instant, the later one is kept.
- **Account activation / password reset:** email-delivered 6-digit code (15-minute TTL, max 5 wrong attempts, then the code is burned) — no password-reset links. Code requests are rate-limited to 5 per email per 24 hours. Beyond that the request is silently ignored, so the response never reveals whether an email is registered or throttled. Codes are stored as a peppered HMAC, never in plaintext. A successful reset ends **all** of the user's refresh sessions (`User.sessionsInvalidatedAt`). Access tokens already issued still expire naturally. Password policy: 8–128 characters, length only, no composition rules (NIST/OWASP). Sits behind an `EmailSender` interface; no real email provider is chosen yet. The current log-only stub prints codes in plaintext and must never run in production (to be replaced in Phase 6+). The same mechanism serves both an existing user's "forgot password" and a newly invited user's first activation (see section 09). In the browser app (KAN-47) a successful reset is followed by an automatic login with the new password; if that login fails (for example, throttled), the password is still set, and the user lands on the login screen with a "password saved" notice.
- **Password hashing:** Argon2id (instead of bcrypt — more resistant to GPU/ASIC cracking), implemented via Spring Security's `Argon2PasswordEncoder`.
- **Pepper:** a fixed secret string, stored only as an environment variable (must be at least 32 characters & never in the DB or in code), combined with the password before hashing — so a DB leak alone isn't enough to crack it.
- **RBAC:** enforced by Permission Level (see section 04), combined with `clubId` filtering (see section 03), via Spring Security — `PermissionLevel` is mapped to a Spring Security authority, and protected endpoints are annotated `@PreAuthorize`. A `JwtAuthenticationFilter` validates the access token on every request and populates the request's `clubId` context from its claim; this is what makes the per-request filtering in section 03 actually apply.
- **Concurrent writes:** `User` and `Player` use optimistic locking (`@Version`), so a concurrent write can never silently overwrite another — and in particular a user's deactivation (`active = false`) can never be undone by a racing write such as a password reset. A single-field absolute change to a user (reset, permission level, deactivation / re-activation) reloads and retries, re-checking on each attempt whether the change still applies; a conflict that can't be resolved returns `409 Conflict` (code `CONCURRENT_MODIFICATION`). Any future endpoint that writes a user (e.g. a profile edit) must follow the same rule (KAN-24). Staff photo changes don't write the `User` document at all, so they can't conflict with it (KAN-32). Player edits are multi-field form edits based on what the user saw, so they are never retried: the client sends the `version` it loaded, and a mismatch — or a save race lost after that check — returns `409 Conflict` (code `STALE_VERSION`) asking the user to reload (section 05). The club has no `@Version` (KAN-38). Its only editable setting, the name, is changed by a targeted single-field update (a `$set` of `name` alone), so two concurrent renames resolve to the later one, and a rename can never overwrite another field of the club document. If the club ever gets several settings edited together as one form, it must move to `@Version`, like a player: existing club documents need a backfill first (as KAN-24 did for users), and the client sends the `version` it loaded.
- **Known accepted trade-off:** inviting a user whose email is already registered (in any club) returns `409 Conflict` (code `EMAIL_ALREADY_REGISTERED`), which tells an authenticated `ADMIN` that the email exists somewhere in the system. Accepted because the endpoint itself requires an authenticated `ADMIN` (it's not public), and email is unique system-wide by design (section 03).
- **Additional protections:** no cross-origin access: the browser app is served from the same origin as the API (the Vite dev proxy locally, a reverse proxy in production), so the backend has no CORS configuration and grants no other origin access (KAN-45). Also input validation on every endpoint, HTTPS everywhere, and automated dependency vulnerability scanning (Dependabot) in CI. Validation happens on the incoming request; entity constraints are **not** re-checked when a document is saved to the database, so every endpoint that writes must validate its full input first.
- **Image uploads** (KAN-29): the image type is taken from the file's content (magic bytes) only; the client's `Content-Type` and file name are ignored, never stored and never echoed. SVG is rejected (it can carry script). Limits: 2 MB per image in the application, enforced again by the servlet container (2 MB per file, 3 MB per request), with Tomcat's `max-swallow-size` raised to 20 MB so an oversize upload of up to 20 MB gets a clean `413` rather than a dropped connection (beyond that the connection may still be reset, so the client should check the size before uploading). Images are served with `X-Content-Type-Options: nosniff` and `Cache-Control: no-store` (Spring Security defaults). EXIF metadata, which can include a GPS location, is **not** stripped (see section 14).
- **Error responses:** every error has the same JSON shape (`timestamp`, `status`, `error`, `code`, `message`, `details`), always sent as `Content-Type: application/json`, whatever the request's `Accept` header says (including one that excludes JSON or can't be parsed). `code` is a machine-readable reason, so a client never has to parse the English `message` (KAN-50). Today only the `409 Conflict`s the application raises carry one; on every other error the key is present with the value `null`. The codes are `JERSEY_NUMBER_TAKEN`, `STALE_VERSION`, `PLAYER_RELEASED`, `PLAYER_ALREADY_ACTIVE`, `PLAYER_ALREADY_RELEASED` (section 05), `EMAIL_ALREADY_REGISTERED`, `CANNOT_CHANGE_OWN_PERMISSION_LEVEL`, `CANNOT_CHANGE_OWN_ACTIVE_STATUS`, `USER_DEACTIVATED` (section 04) and `CONCURRENT_MODIFICATION` (a concurrent write the server couldn't resolve by retrying). A code, once shipped, is never renamed or reused, and every new kind of `409` gets its own. A validation `400` lists one `"name: message"` entry per invalid body field or query parameter in `details`; a body value that can't be read (e.g. a malformed date) is reported as `"<field>: invalid value"`, and a missing required query parameter, header or cookie as `"<name>: is required"`. Rejected values and request headers (such as the `Content-Type` sent) are never echoed back in an error; only the `404` for an unknown path and the `405` name the request's method and path (KAN-31).
  - A body in a format the endpoint doesn't read is `415 Unsupported Media Type`, listing the accepted types in `details` and in the `Accept` header; an `Accept` header the endpoint can't satisfy is `406 Not Acceptable`. Any other status the web framework raises is passed on as is, with a generic message, rather than becoming a `500`.
  - **Logging:** every `500` is logged at ERROR with its stack trace and the request's method and path, never its query string, headers, body or cookies. An exception thrown in a servlet filter is logged this way exactly once, by the application, not also by the servlet container. A `5xx` a filter sends without an exception is logged at ERROR with the method and path, without a stack trace. Client errors (`4xx`) are never logged at ERROR, including requests the security firewall rejects, and a client that disconnects mid-response is logged at DEBUG only.
  - **Errors raised before Spring MVC** use the same JSON shape, whatever the `Accept` header says, and never an HTML error page (KAN-35). An exception thrown in a servlet filter (e.g. inside the JWT filter) is a `500` with `"An unexpected error occurred"`. A path the security firewall rejects (e.g. one containing `//`, `;` or `/./`) is a `400` with `"The request could not be processed"`; this happens before authentication, so also without a token. Any other error status sent below Spring MVC keeps its status, with the generic `4xx` or `5xx` message. A direct request to `/error` is a `401` without a token and a `404` (`"No endpoint GET /error"`) with one. The only exception: a request the servlet container rejects itself, before the application sees it (a malformed request line or headers, or an encoded `/` (`%2F`) in the path), still gets the container's own HTML error page.
- **Rate limiting:** Redis-backed rate limiting covers both the activation/reset code above and `/auth/login` itself: 5 failed attempts on a given (email, IP) pair within a 15-minute window return 429 Too Many Requests with Retry-After (KAN-22).
- **Secrets management:** environment variables / a secrets manager only — no key, password, or pepper ever goes into git.

## 11. Testing & DevOps

A trimmed-down local environment: `docker-compose.yml` with just MongoDB + Redis (no RabbitMQ in phase 1). Backend tests use JUnit 5 + Mockito for units, and Testcontainers for integration. Frontend tests use Vitest + React Testing Library, with MSW mocking the API; a request no test mocked fails the test (KAN-45). CI on GitHub Actions runs lint, tests, and a Docker image build on every push.

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
| **2 — Backend core** | Auth plus a single Player entity all the way to a real DB, with a unit test and an integration test from day one. *Auth & roles done (KAN-10); Player entity done (KAN-25); squad list/get/create/update done (KAN-26); release / re-activation / deletion done (KAN-27); squad summary done (KAN-28); player photos done (KAN-29); current-user endpoint `GET /auth/users/me` done (KAN-34); club logo done (KAN-30); staff photo done (KAN-32); staff list `GET /auth/users` done (KAN-36); user deactivation / re-activation done (KAN-37); club settings `GET` / `PATCH /clubs/me` done (KAN-38). Backend work for the Frontend MVP is complete.* |
| **3 — Frontend MVP** | Dashboard and squad table against the real API — the first "walking skeleton" that runs end to end. *Design direction done (KAN-44); frontend infrastructure done (KAN-45); API client and session done (KAN-46); auth screens done (KAN-47); app shell done (KAN-48); squad table done (KAN-49); player card and add / edit form done (KAN-50, which also added a machine-readable `code` to `409` error responses); release / re-activation / deletion and photo upload in the frontend done (KAN-59); the dashboard done (KAN-51); the squad cards view done (KAN-52).* |
| **4 — Tactical board** | The Canvas module with Konva.js. |
| **5 — Scraping service** | A separate Node worker, fed manually / by Cron — by now there's actually something for it to feed. |
| **6+ — Hardening & deployment** | Full RBAC, multi-club load testing, monitoring, and deployment to a production environment. |

## 14. Open questions

- **[future]** The scope of Veo/chip integration isn't known yet — it depends on what can actually be pulled out of those systems.
- **[Phase 5]** The scraper also brings in jersey numbers (section 07). If it ever writes player records, how it reconciles with manually entered numbers (which are unique among a club's active players) has to be decided then.
- **[Phase 6]** Images: uploaded photos keep their EXIF metadata (possibly a GPS location); stripping it needs server-side re-encoding. When images move to object storage (S3/R2), decide whether to keep serving them through the API or hand out short-lived signed URLs (or a CDN), which would change the photo API, and migrate the existing GridFS files once.
- **[Phase 6]** Refresh rotation has no grace window: a token rotated away is rejected at once, and presenting it revokes the family. The browser app avoids sending two refreshes at once (section 10), but a page closed or reloaded mid-refresh can still lose the new cookie and end that session. A short server-side window in which the just-rotated token is still accepted would close that gap, at a small cost to theft detection. Decide if real users hit it.

---

*SquadPulse — a living working document, updated as development progresses.*
