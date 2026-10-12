# KAN-69: Frontend: next-match and league-table dashboard cards, matches screen, refresh button with status

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `5b28407`, the KAN-68 merge, PR #51). If it doesn't, stop and report. Then create the branch `feature/KAN-69-matches-screens-refresh`.

## Context

Jira KAN-69, epic KAN-14 ("Match & Scraping Integration"), the last ticket of the first scraping slice. Blocked by KAN-68 (done). The backend is complete; this ticket is **frontend only** — no change under `backend/` or `scraper/`. If you find you need a backend change, stop and report why.

The endpoints you build against (read the code, not only this list — see "Verify against reality"):

- `GET /matches/fixtures` (`VIEW_ONLY`) → `match.FixturesResponse`: `state` (`NOT_MAPPED` / `NO_DATA` / `OK`), `lastUpdatedAt` (instant or `null`), `teamName` (our team's name as the site shows it, or `null`), `mappingProblem` (`null` or `TEAM_NOT_IN_STANDINGS`), `games[]`: `date` (Israel-local `yyyy-MM-dd`), `kickoffTime` (`HH:mm` or `null` = not set yet), `ownSide` (`HOME` / `AWAY`), `opponentName`, `venue` (or `null`), `ownScore` / `opponentScore` / `result` (`WIN` / `DRAW` / `LOSS`; all `null` while not played). Sorted by date, then time, an unset time last within its day.
- `GET /matches/league-table` (`VIEW_ONLY`) → `match.LeagueTableResponse`: `state`, `lastUpdatedAt`, `round` (or `null`), `mappingProblem`, `rows[]`: `position`, `teamName`, `played`, `won`, `drawn`, `lost`, `goalsFor`, `goalsAgainst`, `goalDifference`, `points`, `ownTeam`. Sorted by position. **No** ids in either response.
- `POST /matches/refresh` (`EDIT_FULL`, no body) and `GET /matches/refresh` (`VIEW_ONLY`) → `scrapingintegration.RefreshStatusResponse`: `outcome` (POST only: `STARTED` (HTTP **202**) / `ALREADY_RUNNING` / `COOLDOWN` / `NOT_MAPPED` / `SCRAPER_UNAVAILABLE`, all others **200**; always `null` on GET), `state` (`NOT_MAPPED` / `IDLE` / `RUNNING` / `SUCCEEDED` / `FAILED`), `failureReason` (only with `FAILED`: `SOURCE_BLOCKED` / `SOURCE_TIMEOUT` / `SOURCE_ERROR` / `PARSE_ERROR` / `REJECTED` / `NO_RESULT`), `startedAt`, `finishedAt`, `nextRefreshAt` (when a POST may next start a scrape, `null` if it may now; present on GET too).

Facts carried over from KAN-66 / KAN-67 / KAN-68 that this UI must respect:

- The data's `lastUpdatedAt` (when the site was read) is **separate** from the refresh run's times. The table's `lastUpdatedAt` may come from another club's scrape in the same league — that's correct; show it as is.
- After a failed or blocked scrape the old data stays, with its old `lastUpdatedAt`. Show both: the failure and the data's own time.
- **Before a season's first round every run ends `FAILED PARSE_ERROR` although the fixtures were stored.** So a run that ends `FAILED` may still have changed data.
- `SCRAPER_UNAVAILABLE` is a POST outcome only; it doesn't change the stored status (a reload shows the previous state). Treat it as "try again".
- A double click while a trigger is in flight can answer `ALREADY_RUNNING` for a trigger that then fails: rely on `GET` for the truth, not on the POST outcome alone.
- A run that never reports back becomes `FAILED NO_RESULT` after 10 minutes (evaluated on read), so polling always ends. Run records expire after 7 days → `IDLE`.
- spec §07: **every scraped value shows "last updated at X"**, and **no logos or trademarks** from the source site are displayed (team names as text only, no crests).

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Routes and navigation

- New route `/app/matches` (title key e.g. `nav.matches`, "משחקים"), loaded **lazily** like the squad pages (route `lazy`, function form, handle stays static — see the comments in `app/router.tsx`). Covered by the existing pathless `errorElement`.
- Sidebar: move "משחקים" from the `COMING_SOON` group into `NAV_ITEMS` (a `NavLink` to `/app/matches`, same icon). The other coming-soon items stay.
- Update the router's comment that lists the backend's paths (add `/matches`); the dev proxy already has `matches` in `BACKEND_PREFIXES`.

### 2. The matches screen: two tabs, the tab in the URL

- Two tabs: "משחקים" (games, default) and "טבלת הליגה" (table). The selected tab is a search param (e.g. `?tab=table`; no param = games) so the dashboard can link straight to the table and Back / reload keep it. An unknown value falls back to games. Follow the existing segmented-control / view-toggle pattern (`components/squad/ViewToggle.tsx`, `segmentedControl.ts`, and how `lib/squad/view.ts` / `routeState.ts` keep the squad's view in the URL) — accessible as tabs or a radio group, whichever that pattern already is; don't invent a second pattern.
- The screen's header holds the **refresh control** (decision 5) and each tab shows its data's "עודכן לאחרונה" line.
- **Games tab:** the whole season, in the order the backend sends. Each game: date (dd.MM.yyyy) with the Hebrew weekday, kickoff time or "שעה טרם נקבעה" when `kickoffTime` is `null`, home / away, opponent, venue when present.
  - Played (`result` not `null`): the score and a win / draw / loss badge.
  - Not played and its date is today or later: an upcoming game. The **next game** (decision 4's rule) is visually highlighted.
  - Not played and its date is before today: "אין תוצאה" (postponed or not yet updated) — never a fake score.
- **Table tab:** the full table: position, team, played, won, drawn, lost, goals for:against, goal difference, points. Our row (`ownTeam`) highlighted (not only by colour: also e.g. bold + an accessible marker such as `aria-current="true"` or a visually hidden "הקבוצה שלנו"). The round when present ("מחזור X"). Narrow screens: the table scrolls sideways inside its card, never the page.

### 3. Scorelines in RTL (non-obvious — get this right)

Never render a score as one string like `"3–1"`: a run of digits is laid out left-to-right inside RTL text, so the home score lands on the **left** while the home team's name is on the **right**. Render each score in its own element, positioned with its team (e.g. a grid: home team · home score · separator · away score · away team, in DOM order, so RTL puts home on the right). Home / away come from `ownSide`: our team is `teamName` (fall back to a generic "הקבוצה שלנו" string if it's `null`). Test the DOM order of the two scores relative to the two names for both a home and an away game.

### 4. Dashboard cards (replace two placeholders)

The dashboard keeps its layout (`pages/DashboardPage.tsx`). In `ComingSoonCards`, "האימון הבא" stays a placeholder; "המשחק הבא" and "טבלת הליגה" become real cards from the read endpoints. The weekly-schedule placeholder stays. The cards are **read-only**: no refresh button on the dashboard.

- **Next-match card:** the first game with `result === null` whose date is today or later (Israel time, decision 7). Shows opponent, home / away, date + weekday, time or "שעה טרם נקבעה", venue when present. No such game → "אין משחקים קרובים". The card's "last updated" line, and a link to `/app/matches` (games tab).
- **League-table card:** the **full table** in a scroll container about **5 rows high** (columns: position, team, played, points), with a sticky header inside the container. Our row highlighted (same rule as the table tab). On first render the container is scrolled so our row is visible (ideally centred). **Scroll only the container** (set its `scrollTop`); do **not** use `element.scrollIntoView()`, which also scrolls the page. No own row (e.g. `mappingProblem`) → start at the top. A link to `/app/matches?tab=table`, and the card's "last updated" line.
- Both cards: loading and error states like `SquadSummaryCards`; a failed request on one card doesn't affect the other or the squad row.

### 5. The refresh control (matches screen header only)

- Rendered only when `hasPermission(permissionLevel, "EDIT_FULL")` (from `useCurrentUser`) **and** the status isn't `NOT_MAPPED`. For `VIEW_ONLY` / `EDIT_PARTIAL` nothing is rendered **and `GET /matches/refresh` is never requested** (test this with the unmocked-request guard: no handler for it in their tests).
- Status from `GET /matches/refresh` (a TanStack query). **Polling:** `refetchInterval` of **3 s while `state === "RUNNING"`**, off otherwise. Put the interval in one named constant.
- **When a run ends** — the status changes from `RUNNING` to `SUCCEEDED` **or** `FAILED` — invalidate both data queries (fixtures and league table), **including on `FAILED`** (see the pre-season fact above). Detect the transition from the query data, not from the POST.
- Button states:
  - `RUNNING` (or a POST in flight) → disabled, spinner, "סורק…".
  - `nextRefreshAt` in the future → disabled, "אפשר לרענן שוב בעוד X דקות" (minutes rounded **up**, minimum 1; proper Hebrew plurals through i18next — verify how the installed i18next resolves Hebrew plural forms). Re-render the countdown on a timer (e.g. every 15–30 s). When it reaches zero, just enable the button: **don't** auto-refetch at zero. If the browser clock is ahead of the server's, the user's POST simply answers `COOLDOWN` with a fresh `nextRefreshAt` — that's the designed recovery; no loop.
  - Otherwise → enabled, "רענון נתונים".
- POST handling (a mutation, never retried — the client default):
  - `STARTED` / `ALREADY_RUNNING` → put the returned status into the status query's cache (so polling starts at once), then rely on GET.
  - `COOLDOWN` → cache the returned status (shows the countdown).
  - `NOT_MAPPED` → cache it (the control disappears; the data cards already explain).
  - `SCRAPER_UNAVAILABLE` → a transient error message ("שירות הסריקה לא זמין כרגע. נסו שוב בעוד כמה דקות."), the button stays enabled, the cached status and the data unchanged.
  - Any HTTP / network error → a generic error message, data unchanged.
  - Make sure the client accepts a **202 with a JSON body** (`lib/api/client.ts`) — test it.
- Status line under / beside the button:
  - `FAILED` → a warning with the time it ended and a plain Hebrew sentence per `failureReason` (no internals; e.g. blocked → "האתר חסם את הבקשה", timeout → "האתר לא הגיב בזמן", source error → "האתר החזיר שגיאה", parse error → "לא הצלחנו לקרוא את הנתונים מהאתר", rejected → "הנתונים שהתקבלו נדחו", no result → "לא התקבלה תוצאה"). Add that the data shown is from its own "last updated" time.
  - `SUCCEEDED` → nothing extra needed beyond the data's refreshed "last updated" line (a short "עודכן בהצלחה" while just finished is optional).
  - Status changes (scanning, finished, failed, unavailable) are announced through a `role="status"` region that is **already in the DOM** before the text changes (see `test/liveRegion.ts` and how existing screens do it).

### 6. Empty, not-mapped and problem states (cards and screen)

- `NOT_MAPPED` → "המועדון עוד לא מקושר לאתר ההתאחדות. הקישור מוגדר על ידי מנהל המערכת." No "last updated" line, no refresh control.
- `NO_DATA` → "עוד לא נסרקו נתונים." On the matches screen an `EDIT_FULL` user also sees the refresh control (that's how data first arrives).
- `mappingProblem === "TEAM_NOT_IN_STANDINGS"` → a visible warning to **every** user: the club's team wasn't found in the league table, the link to the site may be wrong, contact the system owner. The data still shows.
- Network / 5xx error on a read → the existing error-state pattern; retrying follows the query client's defaults.

### 7. Time and dates: always Israel time

- Instants (`lastUpdatedAt`, `finishedAt`, `nextRefreshAt`) are shown with `Intl.DateTimeFormat` and **`timeZone: "Asia/Jerusalem"` explicitly**, as `dd.MM.yyyy HH:mm` — never the browser's zone (a user abroad must see the same times as the games, which are Israel-local).
- "Today" for the next-match rule and the "no result" rule is the **Israel** calendar date of now (computed with `Asia/Jerusalem`), compared with the game's `yyyy-MM-dd` as strings / parts — never `new Date("yyyy-MM-dd")` (UTC midnight). `lib/squad/dates.ts` is browser-local; put the Israel-time helpers in a new `lib/matches/` module with their own unit tests, including a case where the browser's local date and Israel's date differ (e.g. 23:30 UTC).
- Weekday names: the existing Hebrew day keys if they fit, otherwise i18n keys — not `Intl`'s output, unless you verify it matches the UI's wording.

### 8. Code organisation and i18n

- A `lib/matches/` module like `lib/squad/`: types (mirroring the backend records exactly), query options with keys under one prefix (so a single invalidation refetches both data queries), the next-game / today / formatting helpers.
- Components under `components/matches/` (and the dashboard cards under `components/dashboard/`), the page under `pages/matches/`.
- Every UI string through i18n (`he.json`); football terms per CLAUDE.md rule 3. Remove the now-unused placeholder strings.

## Out of scope

Any backend or scraper change; a refresh button on the dashboard; showing the refresh status to `VIEW_ONLY` / `EDIT_PARTIAL`; lineups, player stats (KAN-70); manual match entry; the next-training card and the weekly schedule; any logo or crest.

## Verify against reality, not memory

Check these against what's actually installed (`frontend/package.json` + `package-lock.json` / `node_modules`) and the backend code, not general knowledge. Say in the summary how you checked each:

- The JSON the backend really sends: how `Instant` (`lastUpdatedAt`, …), `LocalDate` (`date`) and `LocalTime` (`kickoffTime`, `@JsonFormat HH:mm`) serialize with this Boot / Jackson version — from an existing backend test's raw JSON or a real response, not assumption. Your TS types and MSW fixtures must match that exactly.
- TanStack Query (installed version): `refetchInterval` as a function of the query (its signature in this version), `setQueryData` from a mutation, invalidating by key prefix.
- React Router (installed 8.x): search params for the tab, lazy route function form.
- i18next (installed version): Hebrew plural suffixes it resolves (`_one`, `_two`, `_other`, … via `Intl.PluralRules`) — test the "X דקות" string for 1, 2 and 5.
- `Intl.DateTimeFormat` with `timeZone: "Asia/Jerusalem"` in the Node version that runs Vitest (and jsdom) — prove it with a test, including the DST transition month (late October) if cheap.
- That `apiJson` resolves a 202 response with a JSON body.

## Read first

`CLAUDE.md` (standing rules, especially 2, 3, 6 and 7), `docs/design/ui-conventions.md`, then only:

- Backend (read only, to mirror the contract): `match/FixturesResponse.java`, `match/LeagueTableResponse.java`, `match/MappingProblem.java`, `match/MatchDataState.java`, `scrapingintegration/RefreshStatusResponse.java`, `scrapingintegration/RefreshController.java`, and the "Matches API" section of `README.md`.
- Frontend: `app/router.tsx`, `app/pageTitle.ts`, `components/shell/Sidebar.tsx`, `pages/DashboardPage.tsx`, `components/dashboard/ComingSoon.tsx`, `components/dashboard/SquadSummaryCards.tsx`, `lib/squad/summary.ts`, `lib/squad/types.ts`, `lib/squad/dates.ts`, `lib/squad/view.ts`, `lib/squad/routeState.ts`, `components/squad/ViewToggle.tsx`, `components/squad/segmentedControl.ts`, `lib/api/client.ts`, `lib/queryClient.ts`, `lib/auth/currentUser.ts`, `lib/auth/permissions.ts`, `i18n/locales/he.json`, `components/ui/table.tsx`, `components/ui/card.tsx`, `components/ui/badge.tsx`.
- Tests as a style model: `pages/DashboardPage.test.tsx`, `test/render.tsx`, `test/msw/handlers.ts`, `test/msw/squad.ts`, `test/msw/auth.ts`, `test/setup.ts`, `test/unhandledRequestGuard.ts`, `test/consoleGuard.ts`, `test/liveRegion.ts`, and one test that uses `vi.useFakeTimers({ toFake: ["Date"], ... })` (e.g. in `pages/squad/SquadPage.test.tsx`).
- E2E: `e2e/env.ts`, `e2e/helpers.ts`, `e2e/squad.spec.ts`, `e2e/start-backend.sh`.
- `docs/spec.md` sections 02, 07, 08, 13 and the status line: to list what goes out of date, **not** to edit.

## Tests

Vitest + MSW, the existing rules: console guard, unmocked-request guard, no default MSW handlers (`test/msw/handlers.ts` stays empty — add shared building blocks in a new `test/msw/matches.ts`, like `squad.ts`). Every existing test that renders `/app` (the dashboard) will now request the two read endpoints: add the handlers there (as KAN-51 did for `/squad/summary`), and report how many files you touched. For time, use `vi.useFakeTimers({ toFake: ["Date"], now: … })` for "today", and fake the interval timers only where you test polling / the countdown (make sure TanStack and MSW still resolve; if fake intervals fight them, inject the interval / clock instead and say so). At least:

**Helpers (unit)**

- Israel "today" when the UTC date and Israel's date differ (both directions); next-game selection: past unplayed skipped, today's unplayed game is next, played games skipped, none → `null`; instant formatting in `Asia/Jerusalem`; minutes-until rounding (up, min 1).

**Dashboard**

- Next-match card: home and away, time set and `null` ("שעה טרם נקבעה"), venue `null`, no upcoming game, `NOT_MAPPED`, `NO_DATA`, error; the "last updated" line in Israel time; the link to the games tab.
- League-table card: all rows rendered inside the scroll container, own row highlighted and marked accessibly, the container (not the window) scrolled — assert `container.scrollTop` was set and `window.scrollTo` / `scrollIntoView` were not called; `mappingProblem` warning shown with data; link to `?tab=table`; `NOT_MAPPED`, `NO_DATA`, error.
- One card failing doesn't break the other or the squad row.

**Matches screen**

- Sidebar: "משחקים" is a live link and navigates to `/app/matches`; the others stay "coming soon".
- Tabs: default games; `?tab=table` opens the table; switching updates the URL; an unknown value → games; Back restores.
- Games: played (score + badge), upcoming, next highlighted, past without result → "אין תוצאה"; **score DOM order for a home and an away game** (decision 3).
- Table: full columns, own row, round, sideways scroll container.
- States: `NOT_MAPPED`, `NO_DATA`, `mappingProblem`, error.

**Refresh control**

- Visibility: `ADMIN` and `EDIT_FULL` see it; `EDIT_PARTIAL` and `VIEW_ONLY` don't **and no `GET /matches/refresh` is sent** (no handler registered: the unmocked-request guard proves it); `NOT_MAPPED` status → hidden.
- Click → POST sent once; `202 STARTED` → "סורק…", polling GETs every 3 s while `RUNNING`; GET → `SUCCEEDED` → polling stops **and both data endpoints are requested again** (count requests); GET → `FAILED PARSE_ERROR` → the failure message **and** both data endpoints requested again.
- Double click while the POST is in flight → one POST.
- `ALREADY_RUNNING` → polling; `COOLDOWN` → disabled with "בעוד X דקות", enabled when the time passes (no extra request at zero); `SCRAPER_UNAVAILABLE` → message, button enabled, data unchanged; POST 500 / network error → generic message, data unchanged.
- Each `failureReason` → its sentence; no raw enum value visible.
- Status text enters an existing `role="status"` region (`watchLiveInsertion`).
- Opening the screen while a run is already `RUNNING` (another user's) → polling starts.

**E2E (one scenario, added to the suite)**

The E2E club is **unmapped** — keep it that way. **Do not map the E2E club and do not change the seeder or the E2E backend env for this**: the backend jar loads the repo-root `.env` (`spring.config.import` in `application.yml`), which on Gal's machine contains a real `SCRAPER_URL` and token, so a mapped E2E club plus a running local scraper would make a local E2E run ask the real football.org.il. Scenario: log in as the seeded admin → dashboard shows the not-mapped text on both cards → sidebar "משחקים" → matches screen shows the not-mapped state on both tabs and **no refresh button**. This checks the real response shape end to end.

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. render the score as one `"x–y"` string;
2. invalidate the data queries only on `SUCCEEDED` (not on `FAILED`);
3. compute "today" with the browser's local date instead of Israel's;
4. use `scrollIntoView` on the own row;
5. show the refresh control (and its GET) to `EDIT_PARTIAL`;
6. keep polling after `RUNNING` ends.

Report each and whether it was caught.

Run from `frontend/`: `npm run lint`, `npm run format:check`, `npx tsc -b`, `npm test` (report the count before — take it from master before your first change — and after), `npm run build` (report the matches chunk is separate from the main bundle), and `npm run e2e` if the local stack is up (otherwise say so; CI runs it).

**Manual check (only if Gal's local stack is up; otherwise say so):** as an `ADMIN` of the Sderot club (mapped 7617 / 62 / 28) on the dev server: dashboard cards show real data with "last updated"; the matches screen's two tabs; own row highlighted and centred in the dashboard card. **At most one real refresh**, and only if the button is enabled (no cooldown): watch "סורק…" → finished → data refetched. Never loop against the live site. Also view the screens as a `VIEW_ONLY` user if one exists (no button). Report what you saw, plus a screenshot description of the dashboard on a phone width (~390 px).

## Docs

- **`README.md`** (CLAUDE.md rule 7, same commit as the code): the frontend section — the matches screen, the dashboard cards, the refresh control (who sees it, polling), Israel time display; the E2E section if the new scenario needs a note (the club stays unmapped, and why).
- **`CLAUDE.md`:** the status line (KAN-69 done: the screens and the refresh button); one short note where frontend conventions live: match times are always shown and compared in `Asia/Jerusalem`; scores are never one string (RTL); the E2E club must stay unmapped (the root `.env` may hold a real `SCRAPER_URL`). Nothing else.
- **Code comments** in the existing style: why Israel time, why per-element scores, why invalidate on `FAILED`, why no refetch at countdown zero, why only the container scrolls.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words) and what's wrong. I expect at least: the status line (top) and §13 (KAN-69 done; the first scraping slice complete); §02 (the frontend: the `/app/matches` route); §07 (how the data and the refresh are shown: "last updated" on every value, Israel time, the refresh control only from `EDIT_FULL`, its states); §08 (next match and league table are real cards now, read-only, linking to the matches screen; next training and the weekly schedule stay placeholders). Also include the carried-over spec wording notes from KAN-68 (comment 10103: the lock is taken just before the call and kept only if accepted; "a team" = (team, league, season); `SCRAPER_URL` may carry a port and a trailing `/`) — confirm or reword them so Gal can fold them in. Add any I missed.

## Commits

Small, focused commits, for example:

- `feat(frontend): matches data layer, Israel-time helpers and test handlers (KAN-69)`
- `feat(frontend): next-match and league-table dashboard cards (KAN-69)`
- `feat(frontend): matches screen with games and table tabs (KAN-69)`
- `feat(frontend): refresh control with status polling (KAN-69)`
- `test(e2e): matches screen for an unmapped club (KAN-69)`

README / CLAUDE.md with the code they describe (rule 7). Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions verified and **how** you checked each, with the finding for every item in "Verify against reality" (evidence, not assumptions) — including a raw JSON sample of each of the three responses as the backend sends it.
3. The design: the `lib/matches/` module (types, query keys, helpers), components, the route, how the tab lives in the URL, the polling / invalidation logic, the countdown.
4. Tests: the new tests (name + one line each), the existing test files you added handlers to (count), the mutation checks and their results, the test count before and after, and the results of lint, format, tsc, build (chunk names / sizes), e2e.
5. Manual check result, or why it wasn't run.
6. Docs changed (README, CLAUDE.md, comments), one line each.
7. Spec: every out-of-date place (section + quoted words + what's wrong).
8. Deviations from this prompt, and why.
9. Open ends and risks (KAN-70, cron, Phase 6, anything you noticed).
10. Print in full, at branch HEAD:
    - the refresh control component and its hook(s);
    - the Israel-time / next-game helpers;
    - the dashboard league-table card (incl. the scroll logic);
    - the game row component (the scoreline).
