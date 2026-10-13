# SquadPulse scraper

A small internal Node service (KAN-67). The backend asks it to scrape one football.org.il (team, league, season); it fetches that team's fixture list, the team's player season statistics (KAN-73) and the league table, parses them, and posts the result to the backend's `POST /internal/scrape-results`.

What it does:

- Listens for `POST /scrape` from the backend, authenticated with the shared `SCRAPER_SERVICE_TOKEN`.
- Answers `202` at once and scrapes in the background, one scrape at a time (at most 5 waiting, deduplicated by team / league / season).
- Sends the parsed data, or a failure report per part (`SOURCE_BLOCKED`, `TIMEOUT`, `SOURCE_ERROR`, `PARSE_ERROR`), to the backend.

What it doesn't do:

- No database: it never touches MongoDB or Redis and has no credentials for them.
- No scheduling (no cron), no user-facing trigger, locks or scrape status (those are the backend's, KAN-68: `POST` / `GET /matches/refresh`), no lineups, game details or player cards (KAN-75 and later; KAN-70 located them).
- No team-name → id resolution, and no check that the configured team is in the table: the backend does both (`UNRESOLVED_TEAM_NAMES`, `TEAM_NOT_IN_STANDINGS`).
- No images or logos, ever.

The contract for both calls (trigger and result) is [`docs/scraper-backend-contract.md`](../docs/scraper-backend-contract.md). The research behind the scraping setup is [`docs/KAN-64-findings.md`](docs/KAN-64-findings.md), and behind the player statistics [`docs/KAN-70-findings.md`](docs/KAN-70-findings.md).

## Prerequisites

- **Node 24** (the current LTS line). `engines` says `>=24`, but npm doesn't enforce it, so the service itself refuses to start on an older Node (exit code 1). `scraper/.nvmrc` holds `24`: with nvm, `nvm use` in `scraper/` (or below it) picks it up, since nvm looks for `.nvmrc` from the current directory upwards.
- **Chromium for Playwright.** `npm install` / `npm ci` doesn't download browsers (Playwright 1.64's `playwright` package has no install script), so once per machine, after installing:

  ```sh
  cd scraper
  npm install
  npx playwright install chromium   # Linux servers / CI: npx playwright install --with-deps chromium
  ```

  The frontend's E2E suite uses the same Playwright minor (1.64), so on a machine that already has its Chromium, this finds it installed.

## Configuration

Environment variables only. The service refuses to start (exit code 1, naming the variable but never its value) if one is invalid.

| Variable                | Required | Default                 | Rule                                                                    |
| ----------------------- | -------- | ----------------------- | ----------------------------------------------------------------------- |
| `SCRAPER_SERVICE_TOKEN` | yes      |                         | ≥ 32 characters, no whitespace. The same value as the backend's.        |
| `BACKEND_URL`           | no       | `http://localhost:8080` | an `http(s)` URL; results go to `<BACKEND_URL>/internal/scrape-results` |
| `SCRAPER_PORT`          | no       | `8090`                  | 1–65535                                                                 |
| `SCRAPER_HOST`          | no       | `127.0.0.1`             | where the trigger listens; internal only, never exposed to browsers     |

Locally, put them in **`scraper/.env`** (git-ignored; start from [`.env.example`](.env.example)). `npm start` loads it with Node's own `--env-file-if-exists` flag, and real environment variables win over the file. It's deliberately **not** the root `.env`, which holds the MongoDB and Redis credentials the scraper must not have; only `SCRAPER_SERVICE_TOKEN` is shared, by value.

The source site's address (`https://www.football.org.il/`) isn't configurable: production config can't redirect the scraper. Tests inject a local origin through code.

## Running it next to the backend

1. Set the same `SCRAPER_SERVICE_TOKEN` in the root `.env` (backend) and in `scraper/.env`, then start the backend as usual (README, "Local development").
2. `cd scraper && npm start`. It logs `listening host=127.0.0.1 port=8090`.
3. Trigger a scrape (KAN-68 will make the backend do this):

   ```sh
   curl -sS -X POST http://127.0.0.1:8090/scrape \
     -H "Authorization: Bearer $SCRAPER_SERVICE_TOKEN" -H 'Content-Type: application/json' \
     -d '{"runId":null,"teamId":7617,"leagueId":62,"seasonId":28,"fixtures":true,"standings":true,"playerStats":true}'
   ```

   This calls the real site (4 requests). Don't loop it.

Stop it with Ctrl+C (or `SIGTERM`): it stops accepting, aborts the running scrape, closes the browser and logs the dropped runs by `runId`. Nothing is posted for them.

Checks: `npm run lint`, `npm run format:check` (`npm run format` fixes), `npm test` (`node:test`; needs Chromium for the one browser test, never calls the live site).

There's no container for it yet (Phase 6): the backend runs on the host in development, and Chromium makes the image heavy.

## Trigger API (brief)

`POST /scrape` with `{"runId", "teamId", "leagueId", "seasonId", "fixtures", "standings", "playerStats"}` (the first six required, `runId` may be `null`; `playerStats` optional, absent = `false`; at least one of the three parts `true`) → `202 {"status": "QUEUED" | "ALREADY_QUEUED", "runId"}`. `400` invalid body, `401` token, `413` over 4 KB, `415` not JSON, `503` queue full, `404` / `405` otherwise. Full table: [contract, "Trigger"](../docs/scraper-backend-contract.md#trigger-backend--scraper).

**Deploy order (KAN-73).** The backend now always sends `"playerStats": true`, and a scraper older than KAN-73 rejects it as an unknown property (`400`, so every refresh is `SCRAPER_UNAVAILABLE`). Deploy the scraper first, or together with the backend.

## How it talks to football.org.il

Exactly the setup verified in KAN-64 ([findings](docs/KAN-64-findings.md), "Verification of the recommended setup" and section 8). The site is behind Cloudflare, which blocks plain HTTP clients; headless Chromium is let through, so it's used **as an HTTP client only**:

- A fresh headless Chromium per scrape, closed when the scrape ends, with the honest User-Agent `SquadPulse-scraper/<version> (+https://github.com/Galaviv15/Squad-Pulse)` and locale `he-IL`. No other header changes.
- Only these requests are let through (`context.route`): a `document` on the site's origin, and the one in-page `fetch` (the player statistics or `LeagueTable`) whose exact URL the scraper built just before calling it. Everything else (the site's scripts, styles, images, fonts, its own XHRs, every third party) is aborted.
- At most 4 requests per scrape, in order and only for the requested parts (KAN-70 findings section 3):
  1. the team's games page (30 s timeout), if `fixtures` or `playerStats`; with `playerStats` alone it's loaded but not parsed;
  2. the player statistics (20 s), if `playerStats`: an in-page fetch of `Components.asmx/GetTeamPlayersStatisticsList?teamId=…&seasonId=…&isFemale=%22False%22` from the games page;
  3. the league page (30 s), if `standings`, to read the standings round and box;
  4. the in-page `LeagueTable` fetch (20 s), if `standings`.
- The statistics need the games page: they're fetched only if it loaded (not blocked, status < 400; a fixtures parse failure doesn't matter). If it didn't, they're `FAILED` with the same reason and the detail `not attempted: the team games page didn't load (…)`. The page is never loaded a second time for them.
- `isFemale` is always `"False"`: **men's teams only**. A women's team would need `"True"` (from the team page's `data-isFemale`, or a mapping field); not supported.
- A random 3–5 s pause between requests, 90 s per scrape at most.
- At most one retry per scrape, shared by all its requests: a timeout or a 5xx, after 10 s. Never after a block. So a statistics timeout that used the retry leaves none for the standings (accepted).
- Budget: 4 requests per scrape (5 with the retry); with the backend's 10-minute per-team lock that's at most 24 requests per club per hour.
- **A block** (a `403`, or Cloudflare's block page whatever the status) stops the scrape at once: that part and every part not yet attempted are `FAILED SOURCE_BLOCKED`; the backend keeps the old data. It's logged at WARN, so the real block rate is visible.
- **No evasion** (Gal's decision): no browser-UA spoofing, no stealth plugins, no proxies, no CAPTCHA solving. If the site blocks us, the scrape fails.
- **No logos or images** are fetched or sent.
- The IFA (ההתאחדות לכדורגל) has been asked for permission or a data feed. That doesn't block development; a feed would replace this.

Parsing quirks pinned by the tests: results are shown **away-first** (`"4-1"` = away 4, home 1) and standings goals **against-for** (`"8-12"` = 12 for, 8 against); the scraper swaps both. Every string is normalized (U+00A0 → space, whitespace collapsed, trimmed); punctuation in names is kept. Before posting, each `OK` part is checked against every rule of the contract; a part that breaks one is sent as `FAILED PARSE_ERROR` instead, so it can't make the backend reject the other parts.

Player statistics (`src/parsePlayerStats.js`): every cell is identified by its hidden label and cross-checked against the header's `data-sort` keys (the site's `Substitue` spelling included); every row needs a `player_id`, unique in the table, and `games = starts + cameOnAsSub`; minutes are never checked against games (added and extra time). Names are kept exactly as shown, **surname first**, never split or reordered. Players with 0 games are kept. The table covers all competitions together.

**Season start (accepted, Gal's decision).** Every refresh asks for the statistics, so a refresh succeeds only if they're stored too. At a season start the site's table can be empty: that's a `PARSE_ERROR` (the contract needs at least 1 player), so every refresh is `FAILED` until the site registers players, even though the fixtures are stored.

## Logging

One line per event on stdout (WARN / ERROR on stderr): time, level, `run=<runId>`, the event, `key=value` fields. Logged: each trigger accepted / deduplicated / rejected (rejections by status only), each source request (path, status, ms), every block, each part's result, and the backend's answer (outcomes; `REJECTED` and `TEAM_NOT_IN_STANDINGS` at WARN).

Never logged: the token, any header, page HTML, the payload, team names, player names, a `player_id`, or a part's `detail`. A parse error's detail names a statistics row by its index, never by a player's name or id (the detail does reach the backend).

## Clock

`fetchedAt` comes from this machine's clock, and the backend rejects a value more than 5 minutes ahead of its own (`400`). Keep both machines on NTP; a scraper clock that runs fast loses every scrape.
