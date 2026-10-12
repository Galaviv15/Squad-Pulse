# KAN-67: Scraper service (Node): trigger endpoint, fixtures and standings parsers, push results to the backend, tests, CI

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow something you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `589c068`, the KAN-66 merge, PR #49). If it doesn't, stop and report. Then create the branch `feature/KAN-67-scraper-service`.

## Context

Jira KAN-67, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-64 (done: research, findings, fixtures) and KAN-66 (done: the backend ingest endpoint and the contract). Blocks KAN-68 (the backend refresh trigger, locks and scrape status, which will call the trigger endpoint built here).

Today `scraper/` is a skeleton: `package.json` (`playwright ^1.47.2`, `cheerio ^1.0.0`, no lockfile, only a `start` script), a stub `src/index.js`, `docs/KAN-64-findings.md` and `test/fixtures/` (three trimmed captures + README). This ticket turns it into a small, always-running internal Node service:

1. The backend asks it to scrape one (team, league, season) through an authenticated trigger endpoint.
2. It answers immediately and scrapes in the background, one scrape at a time.
3. It sends the parsed result, or a failure report, to the backend's `POST /internal/scrape-results`.

It never touches MongoDB and has no DB credentials.

**Read first, fully:**

- `scraper/docs/KAN-64-findings.md`. In particular: "Summary and recommendation", "Verification of the recommended setup" (the exact route rule and steps), sections 4, 5, 6, 8 and 9, and "Open questions and risks → KAN-67".
- `docs/scraper-backend-contract.md`: the contract you post against. It is the source of truth for the payload. Don't change the backend.
- `scraper/test/fixtures/README.md`.

**Carried over from earlier tickets** (Jira KAN-64 comment 10092, KAN-66 comment 10098):

- Log every block, so the real block rate is visible.
- Send `null`, never `""`. `kickoffTime` is `HH:mm`. No properties beyond the contract (the backend rejects unknown ones with `400`).
- `fetchedAt` comes from the scraper's clock and must not be more than 5 minutes ahead of the backend's clock.
- Normalize non-breaking spaces.
- Reuse `SCRAPER_SERVICE_TOKEN`.

**Already handled by the backend; don't duplicate it here:**

- Jira comment 10095 on KAN-67 asks the scraper to report a configured `team_id` that's missing from the standings. KAN-66 moved that to the backend: it returns the `TEAM_NOT_IN_STANDINGS` warning and stores the resolved team name.
- The scraper sends all standings rows as parsed and logs that warning at WARN when the backend returns it.
- Fixture team-name → id resolution is also the backend's job. The scraper sends names only.

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Trigger endpoint (backend → scraper)

`POST /scrape`, `Content-Type: application/json`, `Authorization: Bearer <SCRAPER_SERVICE_TOKEN>`.

```
{
  "runId":     string | null,   // ≤ 100 chars, no control characters; opaque, echoed into the result (KAN-68 sets it)
  "teamId":    int,             // ≥ 1
  "leagueId":  int,             // ≥ 1
  "seasonId":  int,             // ≥ 1
  "fixtures":  boolean,         // scrape the team's games page
  "standings": boolean          // scrape the league table
}
```

All six properties are required (`runId` may be `null`). At least one of `fixtures` / `standings` must be `true`. Unknown properties → `400`.

| Status | When | Body |
|---|---|---|
| `202` | Accepted and queued | `{"status": "QUEUED", "runId": <the request's runId>}` |
| `202` | A scrape for the same `(teamId, leagueId, seasonId)` is already queued or running. Nothing is added or changed. | `{"status": "ALREADY_QUEUED", "runId": <the existing run's runId>}` |
| `400` | Invalid JSON, a wrong type, a missing or out-of-range property, an unknown property, both parts `false` | fixed message, no echo of the input |
| `401` | No, malformed or wrong bearer token | fixed message |
| `413` | Body over 4 KB, with or without `Content-Length` | |
| `415` | `Content-Type` isn't `application/json` | |
| `503` | The queue is full | |
| `404` / `405` | Any other path / method | |

Rules:

- **Token comparison is constant-time**, e.g. `crypto.timingSafeEqual` over SHA-256 digests of both values, so lengths never leak.
- **The token is never logged**, nor any header.
- **Error bodies never echo the request.** Use a small fixed JSON shape, e.g. `{"error": "..."}`.
- Built on `node:http`. No framework.

Document this trigger as a new section of `docs/scraper-backend-contract.md` ("Trigger: backend → scraper"), next to the existing ingest description. Update the title and intro so the doc covers both directions. KAN-68 builds its client against it.

### 2. Concurrency

- **One scrape at a time**, in-process: one running, plus a FIFO queue of at most **5** waiting.
- A full queue → `503`.
- Dedupe by `(teamId, leagueId, seasonId)` across queued and running (see the table above).

This is a second line of defence: KAN-68's Redis locks are the primary one.

### 3. Talking to football.org.il: exactly the verified setup

Follow the findings' "Verification of the recommended setup" and section 8 exactly.

**Browser:**

- Playwright, headless Chromium, used as the HTTP client only.
- A fresh browser launch per scrape, closed in `finally`.
- User-Agent `SquadPulse-scraper/<package version> (+https://github.com/Galaviv15/Squad-Pulse)`, locale `he-IL`.
- No other header changes, no stealth plugins, no proxies, no browser-UA spoofing.

**Route rule** (`page.route("**/*")`): continue a request only if it is

- a `document` whose URL starts with the source origin, or
- a `fetch` whose URL equals, exactly, the one `LeagueTable` URL built just before calling it.

Abort everything else. Don't widen it to all `fetch` / `xhr` requests.

**Requests, in order, only those needed for the requested parts:**

1. If `fixtures`: `GET /team-details/team-games/?team_id={team}&season_id={season}`, timeout 30 s.
2. If `standings`: `GET /leagues/league/?league_id={league}&season_id={season}`, timeout 30 s. Read `section.league-standing-table` `data-table-round`, `data-table-index` (the `box`) and `data-table-title`.
3. If `standings`: an in-page `fetch` of `GET /Components.asmx/LeagueTable?league_id=…&season_id=…&box=…&round_id=…&componentTitle=<encoded data-table-title>`, timeout 20 s (enforce it with an `AbortController` inside the page), header `x-requested-with: XMLHttpRequest`, as the findings do.

**Pacing and limits:**

- A random 3–5 s pause between consecutive requests.
- An overall cap of 90 s per scrape. Parts not finished by then → `FAILED TIMEOUT`.
- At most 3 requests per scrape, plus the one retry below.
- Every URL is built only from the validated integers and the attributes read from the page. Never from free text in the trigger.

**Source origin:** `https://www.football.org.il/`, hard-coded as the production default. Make it a constructor / function parameter of the fetching module so tests can point it at a local server. **Not an env var**: production config can't redirect the scraper.

### 4. Failures, blocks and retries

**How each result maps to a part's outcome:**

- **Block → `SOURCE_BLOCKED`.** A response with status `403`, or any response whose body is Cloudflare's block page (match the markers in `test/fixtures/cloudflare-block-403.html`), even with status 200.
  - Stop the scrape at once. **No retry.**
  - The blocked part is `FAILED SOURCE_BLOCKED`.
  - Every requested part not yet attempted is also `FAILED SOURCE_BLOCKED`, with `detail` "not attempted: an earlier request was blocked".
  - Log the block at WARN with the URL path and status (never the body).
- **Timeout → `TIMEOUT`.** A navigation or fetch timeout, or the 90 s cap.
- **Other errors → `SOURCE_ERROR`.** Another status ≥ 400, or a network error.
- **Retry:** on a timeout or a 5xx (not a block), retry **that one request once**, after 10 s, within the 90 s cap.
- **Independent parts:** a fixtures failure that isn't a block doesn't stop the standings, and the other way round.
- **`PARSE_ERROR`:** the page didn't have the expected shape (see 5), including the local contract check (see 6).

**`detail`:**

- ≤ 500 chars, no control characters, short and factual (e.g. `"unknown column label: …"`, `"403 from source"`).
- Never raw HTML. Never the token.

**Season-start edge case:** `data-table-round` / `data-table-index` missing, non-numeric or `< 1` → the standings part is `FAILED PARSE_ERROR` with `detail` `"no standings round on the league page"`. The contract requires `round ≥ 1`, and sending `0` would make the backend `400` the whole request, losing the fixtures too.

### 5. Parsing (Cheerio)

Follow the findings' sections 4, 6 and 9. The selectors are in "Most robust selectors".

**Text normalization** (all strings):

- U+00A0 → a space.
- Collapse every run of whitespace to one space, then trim.
- After that, any remaining control character (`Character.isISOControl` range: U+0000–U+001F, U+007F–U+009F) → `PARSE_ERROR`.
- Never split names on punctuation (`"`, `'`, `.`, `,` are part of names).

**Fixtures** (`section.games_table a.table_row[href*="game_id="]`):

- Identify each `div.table_col` by its `span.sr-only` label (`תאריך`, `משחק`, `אצטדיון`, `שעה`, `תוצאה`), not by position. A missing or unknown label → `PARSE_ERROR`.
- `gameId`: from `href`. `-1` → `null`; otherwise it must be an integer ≥ 1, else `PARSE_ERROR`.
- `date`: `dd/MM/yyyy` → `yyyy-MM-dd`, and it must be a real date.
- `kickoffTime`: `HH:mm`. `00:00` → `null`.
- Teams: split the game cell on the **first** `" - "` only. Both halves non-empty. **Home is first.**
- `venue`: empty → `null`.
- **Result: the site shows it away-first.** `"a-b"` means `awayScore = a`, `homeScore = b`. Empty → both `null`. Anything else → `PARSE_ERROR`.
- 0 rows, or more than 100 → `PARSE_ERROR`.
- Keep page order. Don't de-duplicate pairings: the backend rejects duplicates (`DUPLICATE_GAME`) on purpose.

**Standings:**

- Parse the XML envelope and take `HtmlData`'s text, which is unescaped HTML. Load that into Cheerio.
- Rows: `a.table_row[href*="team_id="]`. `teamId` comes from `href`.
- Columns by `sr-only` label (`מיקום`, `קבוצה`, `משחקים`, `ניצחונות`, `תיקו`, `הפסדים`, `שערים`, `נקודות`), cross-checked with the `place` / `team_name` / `goals-col` classes.
- **Goals are against-for:** `"x-y"` means `goalsAgainst = x`, `goalsFor = y`.
- `points` as published (may be negative). No goal difference.
- `round` = the `round_id` requested.

**Never extract or send images or logos.** The route rule already aborts them.

### 6. Building and posting the result (scraper → backend)

**Building the payload:**

- Build it exactly per `docs/scraper-backend-contract.md`: `runId`, `teamId`, `leagueId`, `seasonId`, and `fixtures` / `standings` each as an `OK` part, a `FAILED` part, or absent (`null`) when not requested.
- `fetchedAt`: an ISO-8601 instant with `Z`, taken when that part's last response arrived.

**Validate locally first.** Before posting, check each `OK` part against every contract rule:

- ranges and lengths;
- `won + drawn + lost == played`;
- both scores or neither;
- `homeTeamName ≠ awayTeamName`;
- unique `teamId` / `teamName` in the table;
- `position` within 1..row count;
- 1–100 games, 2–40 rows;
- no `""`.

A part that fails is turned into `FAILED PARSE_ERROR`, with the first violated rule as `detail`. A broken parse must never reach the backend as an `OK` part, and never cause a `400` that discards the other part.

**Posting:**

- `POST {BACKEND_URL}/internal/scrape-results` with the bearer token, using Node's built-in `fetch`, timeout 15 s.

**Handling the backend's answer:**

| Response | Action |
|---|---|
| `200` | Log each part's `outcome`. `REJECTED` (with its `reason`) and `TEAM_NOT_IN_STANDINGS` → WARN. Other warnings → INFO. |
| `409`, `5xx`, network error, timeout | One retry after 5 s, then log at ERROR and drop the result. |
| `400`, `401`, `413`, `415` | Log at ERROR (status, and `details` for a `400`), no retry. A `400` here is a scraper bug. |

Never log the payload's team names at INFO or above, nor `detail`, nor the token.

### 7. Configuration (env vars only), runtime and logging

**Env vars:**

| Variable | Required | Default | Rule |
|---|---|---|---|
| `SCRAPER_SERVICE_TOKEN` | yes | | ≥ 32 chars, no whitespace (the backend's rule) |
| `BACKEND_URL` | no | `http://localhost:8080` | a valid `http(s)` URL |
| `SCRAPER_PORT` | no | `8090` | 1–65535 |
| `SCRAPER_HOST` | no | `127.0.0.1` | internal by default; not exposed to browsers |

Anything invalid → refuse to start, exit code 1, with a message naming the variable (never its value).

**Its own env file:** `scraper/.env` (git-ignored by the existing `.env` rule; verify), documented by a new `scraper/.env.example`, loaded with Node's own env-file flag in the `start` script. **Not the root `.env`**: it holds the MongoDB and Redis credentials, and the scraper must not have them. Add a one-line pointer to the root `.env.example` next to `SCRAPER_SERVICE_TOKEN` saying the same value goes in `scraper/.env`. No secret in git.

**Runtime:**

- Plain JavaScript, ESM (`"type": "module"`, already set). No TypeScript, no Express, no test framework dependency: `node:http`, `node:test`, `node:assert`.
- `"engines": { "node": ">=24" }`.
- ESLint (flat config) and Prettier, aligned with the versions and style the frontend uses.
- Scripts: `start`, `test`, `lint`, `format`, `format:check`.
- Commit `package-lock.json`.
- On `SIGTERM` / `SIGINT`: stop accepting, abort the running scrape, close the browser, log the dropped queued runs (by `runId`), exit. KAN-68's status timeout covers them.

**Logging:**

- One line per event to stdout/stderr, with a level and the `runId`.
- Log: trigger accepted / deduped / rejected (status only), each source request (path, status, ms), every block, each part's outcome, and the backend's response.
- Never log the token, headers, raw HTML, or the full payload.

### 8. CI, Dependabot, docker-compose

**CI:** add a `scraper-ci` job to `.github/workflows/ci.yml`, in the style of the existing jobs:

- `ubuntu-24.04`, `working-directory: scraper`.
- `actions/setup-node` with Node 24, npm cache on `scraper/package-lock.json`.
- `npm ci`, lint, `format:check`.
- Install Chromium.
- `npm test`.

For Chromium, reuse the e2e job's pattern: cache `~/.cache/ms-playwright` keyed by the Playwright version read from `scraper/`, `install --with-deps chromium` on a miss, `install-deps chromium` on a hit. Use a cache key distinct from the e2e job's if the versions could differ.

**Never call the live site in CI or in any test.**

**Dependabot:** add an `npm` entry for `/scraper`, exactly like the `/frontend` entry (`open-pull-requests-limit: 0`, prefix `build`, `include: scope`), and update the file's header comment. Update the README's Dependabot line ("`scraper/` isn't covered yet").

**docker-compose:** no scraper service in this ticket (deferred to Phase 6). The backend runs on the host, so a container would need `host.docker.internal`, and Chromium makes the image heavy. Run it locally with `npm start`.

### 9. Out of scope

- The user-facing refresh trigger, locks and scrape status (KAN-68).
- Frontend (KAN-69).
- Lineups and player statistics (KAN-70).
- Scheduling (cron).
- Any backend change. If you find the contract can't be met as written, stop and report; don't change the backend.

## Verify against reality (evidence, not assumptions)

For each item, check against what's actually installed or published today, and report what you found and how:

1. **Node:** the current LTS line (`https://nodejs.org/dist/index.json`, `lts` field). KAN-64 found 24 "Krypton" Active LTS and expected 26 to become LTS in late October 2026. Use 24 unless 26 is already LTS; report which. Check your local `node --version`.
2. **Versions:** the latest `playwright` and `cheerio` (`npm view … version time engines`).
   - Align `playwright`'s minor with the frontend's `@playwright/test` (`^1.64.0` today), so local installs share a Chromium build.
   - Pin exact versions in `package.json` or use `^`: say which and why.
3. **Browser download:** whether `npm install playwright` downloads browsers by itself in that version, or `npx playwright install chromium` is required. Document the real answer in the scraper README.
4. **Cheerio XML API:** how the installed Cheerio parses XML (the `xml` / `xmlMode` option in 1.x), and that `HtmlData`'s text comes back unescaped. Prove it with the fixture.
5. **Node built-ins in the installed version:**
   - the env-file flag you use (e.g. `--env-file-if-exists`);
   - `node:test` timer mocking, if you use it, or another way to make the 3–5 s pauses and 10 s retry fast in tests;
   - `AbortSignal.timeout` with `fetch`.
6. **Playwright behaviour:**
   - `page.route` aborting an in-page `fetch`;
   - `response.text()` on a navigation response;
   - what a navigation timeout throws (to map it to `TIMEOUT`).
7. **Lint tooling:** the ESLint / Prettier versions and config style the frontend actually uses (`frontend/package.json`, `frontend/eslint.config.js`).
8. **Git-ignore:** `scraper/.env` and `scraper/node_modules` are ignored (`git check-ignore -v`).

## Tests (`node:test`; no live site)

**Parsers, against the KAN-64 fixtures:**

- **Games:** 34 games; 6 with a result, 28 unscheduled. An unscheduled row: `gameId` `null`, `kickoffTime` `null`, `venue` `null`, both scores `null`, `date` set.
- **The away-first fix:** the 03/09/2026 game `מ.כ. צעירי טירה - מ.כ. שדרות`, shown `4-1`, gives home Tira `homeScore 1`, away Sderot `awayScore 4`. Assert one more played game from each side (Sderot home and away).
- **Standings:** 18 rows; `teamId` from every row; Sderot `7617`: played 6, W2 D1 L3, `goalsFor 7`, `goalsAgainst 8`, points 7.
- **The against-for fix:** Dimona, `8-12` on the page → `goalsFor 12`, `goalsAgainst 8`.
- **Cross-check:** Sderot's W/D/L and goals for/against, computed from its six parsed results, equal its parsed standings row. Every opponent name in the fixtures matches a standings name exactly.
- **Envelope:** names with `"`, `'`, `.`, `,` survive intact; a name with U+00A0 or repeated spaces is normalized.
- **Malformed inputs** (small synthetic HTML, built in the test): each → `PARSE_ERROR`:
  - a missing label;
  - an unknown label;
  - a score `"x"`;
  - date `31/02/2026`;
  - `game_id=abc`;
  - no rows;
  - no `" - "` separator.
- **Round and box:** extracted from a minimal league-page HTML. Missing / `0` / non-numeric → standings `PARSE_ERROR` with the season-start `detail`.

**Block detection:** `cloudflare-block-403.html` with status 403 and with status 200 → blocked. A normal games page → not blocked.

**Local contract validation:**

- It accepts the request example in `docs/scraper-backend-contract.md`. Read the JSON block after `<!-- contract-example-request -->` from the doc at test time, so a doc change that breaks the scraper fails here.
- It accepts the payload built from the fixtures.
- Each rule from decision 6 rejects a hand-broken copy, turning that part into `FAILED PARSE_ERROR` while the other part stays `OK`.
- No `""` anywhere in a built payload. No property outside the contract.

**Orchestration, with a fake fetcher and fast timers:**

- Only the requests for the requested parts are made: 1 for fixtures-only, 2 for standings-only, 3 for both.
- A block on the games page → both parts `FAILED SOURCE_BLOCKED`, **no further request**, no retry, WARN logged.
- A timeout → one retry, then `TIMEOUT`. A 5xx → one retry, then `SOURCE_ERROR`. A 404 → no retry.
- A fixtures parse failure still attempts the standings.
- The 90 s cap → `TIMEOUT` for unfinished parts.
- The 3–5 s pause is applied between requests.

**Trigger server** (a real `node:http` server on port 0):

- `202 QUEUED`, and the scrape runs.
- `401` for no token, a wrong token, and the right length with one character off.
- `400` for each category: bad JSON, wrong type, missing property, out of range, unknown property, both parts `false`.
- `413` over 4 KB, also chunked. `415`. `404` / `405`.
- Duplicate → `202 ALREADY_QUEUED` with the first `runId`.
- Queue full → `503`.
- Assert the token never appears in any captured log line.

**Backend client** (a local stub server):

- Method, path, `Authorization` and `Content-Type`.
- `200` outcomes logged at the right levels.
- `409` / `500` / network error → exactly one retry. `400` / `401` → none.

**Config:** each invalid env var → exit 1, message names the variable, never the value.

**One real-Chromium integration test** against a local HTTP server that serves the fixtures at the site's paths (origin injected):

- The league page serves a minimal HTML with the placeholder section, plus `<img>`, `<script>`, stylesheet and third-party references.
- Assert the full scrape succeeds, the parts equal the parser tests' results, and **the server received exactly 3 requests** (everything else aborted).
- A second case serves the block page with 403 → `SOURCE_BLOCKED` after 1 request.

Make the pauses short in this test through the same injection.

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. the token check accepts any non-empty token;
2. remove the away-first swap;
3. remove the against-for swap;
4. retry after a block;
5. widen the route rule to every `fetch`;
6. skip the local contract validation;
7. send `""` instead of `null` for an empty venue.

Report each and whether it was caught.

Run `npm run lint`, `npm run format:check` and `npm test` in `scraper/`. Report the test count. The backend and frontend aren't touched; don't run their suites, but confirm with `git diff --stat master` that nothing outside `scraper/`, `docs/scraper-backend-contract.md`, `.github/`, `README.md`, `CLAUDE.md` and `.env.example` changed.

**Optional, only if Gal is present to approve it:** one live manual scrape of Sderot (7617 / 62 / 28) against a locally running backend, at most once, to confirm it works end to end. Report the exact request count and outcome. Don't loop, and don't run it in tests.

## Docs

- **`scraper/README.md`** (new): what the service does and doesn't do, plus:
  - prerequisites (Node 24, Chromium install);
  - env vars and `scraper/.env`;
  - how to run it next to the backend;
  - the trigger API in brief, with a pointer to the contract doc;
  - the good-citizen rules (UA, 3 requests, pauses, no retry on a block, no evasion, no logos) and the IFA note from the findings;
  - what's logged;
  - the clock requirement (within 5 minutes of the backend).
- **`docs/scraper-backend-contract.md`:** the new trigger section (decision 1). Retitle so it covers both directions. Don't change the ingest part.
- **`README.md`** (CLAUDE.md rule 7, same commit as the code):
  - status line ("the scraper is still a skeleton");
  - project-structure comment for `scraper/`;
  - the setup steps (running the scraper, `scraper/.env`);
  - the Dependabot line.
- **`CLAUDE.md`:** status line ("the scraper is still a skeleton"), and the structure line if needed. Nothing else.
- **`scraper/package.json`:** description; replace the stub `src/index.js`.
- **`docs/spec.md`: do NOT edit it.** In the summary, list **every** place that's now out of date, with the section, a few quoted words so it can be found, and what's wrong. I expect at least:
  - **§02:** the scraper as a running service, the trigger flow;
  - **§07:** how scraping works now: headless browser as HTTP client, 3 requests, blocks, no evasion; and anything still saying Playwright renders pages, or that lineups / minutes are scraped now;
  - **§11:** the new CI job and the scraper tests;
  - **§13:** the Phase 5 row;
  - **§10:** the trigger's auth with the same token. Also KAN-66's leftover: §10 doesn't say a token containing whitespace fails startup.

  Confirm or correct each, and add any I missed.

## Commits

Small, focused commits, docs with the code they describe (rule 7). For example:

- `feat(scraper): fixtures and standings parsers with tests (KAN-67)`
- `feat(scraper): headless-browser fetcher, trigger endpoint, queue and backend client (KAN-67)`
- `ci(scraper): lint and test job, Dependabot entry (KAN-67)`

Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions chosen, and **how** you checked each, with the findings for every item in "Verify against reality".
3. The final design: module layout (one line per file), the trigger contract as documented, and the queue / dedupe behaviour.
4. One real payload built from the fixtures (the first 3 games and first 2 rows are enough), and the request count / outcome of each orchestration case.
5. Tests: the new tests (name + one line each), the mutation checks and their results, the test count, and the lint / format / test results (local).
6. Docs changed (scraper README, contract doc, README, CLAUDE.md, `.env.example`, dependabot, CI), one line each.
7. Spec: every out-of-date place (section + quoted words + what's wrong).
8. Deviations from this prompt, and why.
9. Open ends and risks for KAN-68 / KAN-69 / Phase 6.
10. Print in full, at branch HEAD:
    - the trigger server, including the token check;
    - the route rule and the fetch orchestration (blocks, retries, the cap);
    - the two parsers;
    - the local contract validator;
    - the backend client;
    - the new CI job.
