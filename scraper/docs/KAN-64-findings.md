# KAN-64: football.org.il scraping spike — findings

Investigated on 2026-10-11 against Sderot FC (מ.כ. שדרות), Liga Alef South: `team_id=7617`, `season_id=28`, `league_id=62`. No production code. Trimmed sample responses are in `../test/fixtures/` (see its README).

## Summary and recommendation

- **Neither data source needs JavaScript rendering.** Fixtures are in the team-games page's initial HTML. Standings come from one `GET` to an ASMX endpoint (`/Components.asmx/LeagueTable`) returning XML that wraps an HTML fragment. It needs no token, no session and no special header. The HTML a plain HTTP client received when it got through was byte-identical to what the browser received.
- **But the whole site is behind Cloudflare, which blocks non-browser clients.** `curl` and Node `fetch` were answered `403` "Sorry, you have been blocked" 9 of 11 times. Headless Chromium (Playwright) with the same identifiable User-Agent was let through 4 of 5 times. No JS challenge ran in the browser loads; Cloudflare decides on the first request. The deciding signal isn't visible from outside.
- **Recommendation: Playwright (headless Chromium) for both sources, as the HTTP client only.** Load the team-games document, load the league page document (to read the current standings round), and call `LeagueTable` with `fetch` from inside the page. Parse everything with Cheerio. Block every non-document resource. One scrape = 3 requests.
- **We must not try to defeat the protection.** That means no browser-UA spoofing, no "stealth" plugins and no proxy rotation. If the honest headless browser is blocked, the scrape fails with a "source blocked" error. Before relying on this in production, Gal should decide whether to ask the IFA (ההתאחדות לכדורגל) for permission or a data feed (see Risks).

## Method

- **Browser:** Playwright 1.64.0 with Chromium, installed in a temporary folder outside the repo (deleted afterwards). It ran headless with User-Agent `SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)` and locale `he-IL`. A `page.route` handler aborted third-party requests, images, fonts, media, stylesheets and SVGs, and logged every request and response (method, URL, status, request and response headers, XHR bodies).
- **Plain HTTP:** `curl` and Node 22 `fetch`, with the same User-Agent and normal `Accept` / `Accept-Language` headers. No browser User-Agent was tried, on purpose.
- **Volume:** **84 requests** to football.org.il in total, including the static scripts the browser loaded in its first runs. Requests were sequential, at least 5 s apart, and 10 s apart in the final block-rate check.

Key commands, to repeat the investigation (scripts not committed):

```sh
# robots.txt and a plain-HTTP attempt
UA='SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)'
curl -sS -A "$UA" -D - https://www.football.org.il/robots.txt
curl -sS -A "$UA" -D - -o games.html 'https://www.football.org.il/team-details/team-games/?team_id=7617&season_id=28'

# Browser: install outside the repo
mkdir /tmp/pw && cd /tmp/pw && npm init -y && npm i playwright@1.64.0 && npx playwright install chromium
```

```js
// The essence of the browser run (Playwright, ESM)
const ctx = await (await chromium.launch()).newContext({ userAgent: UA, locale: "he-IL" });
const page = await ctx.newPage();
await page.route("**/*", (r) => r.request().resourceType() === "document" ? r.continue() : r.abort());
const games = await page.goto("https://www.football.org.il/team-details/team-games/?team_id=7617&season_id=28");
const gamesHtml = await games.text();
await page.goto("https://www.football.org.il/leagues/league/?league_id=62&season_id=28");
const round = await page.getAttribute("section.league-standing-table", "data-table-round");
const xml = await page.evaluate(async (u) => (await fetch(u, { headers: { "x-requested-with": "XMLHttpRequest" } })).text(),
  `/Components.asmx/LeagueTable?league_id=62&season_id=28&box=20&round_id=${round}&componentTitle=${encodeURIComponent("טבלת ליגה")}`);
```

## Answers

### 1. Where each piece of data comes from

**Fixtures / results: in the initial HTML.**
- The request is `GET /team-details/team-games/?team_id=7617&season_id=28` → `200`, `text/html; charset=utf-8`, Brotli, 176,521 bytes.
- The browser made no data XHR on this page: its 16 XHRs were all SVG icons (`/files/Footer/*.svg`), plus 5 scripts and 3 stylesheets.
- The table is `section.games_table`, one `<a class="table_row link_url" href="/leagues/games/game/?game_id=…">` per game:

```html
<a class="table_row link_url" title="קישור לעמוד משחק" href="/leagues/games/game/?game_id=1122631">
  <div class="table_col align_content"><span class="sr-only">תאריך</span>03/09/2026</div>
  <div class="table_col align_content"><span class="sr-only">משחק</span>מ.כ. צעירי טירה - מ.כ. שדרות</div>
  <div class="table_col align_content new-desktop-only"><span class="sr-only">אצטדיון</span>חדרה מתחם אימונים סינטטי</div>
  <div class="table_col align_content"><span class="sr-only">שעה</span>21:15</div>
  <div class="table_col align_content"><span class="sr-only">תוצאה</span>4-1</div>
</a>
```

**Standings: loaded later, by one XHR.**
- The league page's HTML (`GET /leagues/league/?league_id=62&season_id=28`, 248 KB) holds only placeholders:

  ```html
  <section class='placeholder league-standing-table' data-table-index='20' data-table-title='טבלת ליגה' data-table-round='6' data-table-type='table'>
    <a href='javascript:;' class='league-get-data-btn clickMe' role='button'>clickMe</a>
    <div class='bindData'></div>
  </section>
  ```

- `league.min.js?v=2.0.5.14` "clicks" each placeholder once it scrolls into view (`fillBindData` → `isOnScreen`). That's why a load without scrolling made no data request at all.
- For `data-table-type='table'` it requests:
  - `GET /Components.asmx/LeagueTable?league_id=62&season_id=28&box=20&round_id=6&componentTitle=טבלת%20ליגה`
  - `box` = the placeholder's `data-table-index`; `round_id` = its `data-table-round`, the last completed round (6). The page's round selector shows the current round, 7.
  - Request headers from the browser: `accept: */*`, `x-requested-with: XMLHttpRequest`, `referer: <league page>`, and the cookie `__cflb` (Cloudflare's load-balancer affinity cookie, not an auth token). No CSRF token, no session id needed.
- **Response:**
  - `200`, `text/xml; charset=utf-8`, gzip, `cache-control: private, max-age=0`.
  - An ASMX envelope with the HTML escaped inside:

    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <ResponseData xmlns="http://tempuri.org/" …>
      <HtmlData>&lt;div class='league-table table-w-playoff' …&gt;…</HtmlData>
      <HaveLink>false</HaveLink>
    </ResponseData>
    ```

  - The site's own JS reads `e.all[1].textContent`, i.e. the unescaped `HtmlData` text. Parse the XML, take `HtmlData`'s text, load it into Cheerio.
- **Other endpoints the same script uses** (same envelope; not needed for this scope):
  - `LeagueGamesList` (`box=10`, one round's games of the whole league; each row has `data-team1` / `data-team2` team ids but no game link)
  - `LeagueGamesHomeOut` (home/away ranking)
  - `League_AllTables` (when the round selector changes)
  - `LeagueStatistics` (see 7)

### 2. Can the requests be called directly?

- **Technically yes; in practice Cloudflare blocks it.** Every block was a `403` with Cloudflare's HTML block page (`Attention Required! | Cloudflare`, "Sorry, you have been blocked"), `cache-control: private, max-age=0, no-store…`, no `Set-Cookie`.
- Plain HTTP attempts (`curl` / Node `fetch`, same honest User-Agent, no cookies):

  | Time (UTC) | Client | Target | Result |
  |---|---|---|---|
  | 11:49 | curl | team-games page | 403 block |
  | 11:50 | Node fetch | team-games page | 403 block (twice) |
  | 11:52 | Node fetch | `LeagueTable` | 403 block |
  | 11:53 | Node fetch | `LeagueTable` | 403, 403, then **200** (18,763 bytes, identical to the browser's) |
  | 11:53 | Node fetch | team-games page | **200**, byte-identical to the browser's 176,521 bytes |
  | 11:54–11:55 | fetch / curl alternating | both | 6 × 403 block |

- The two that passed came within seconds of a browser visit from the same IP; that's not a usable rule.
- **No cookie or token is needed by the endpoints themselves.** The 200s above were sent without any cookie. Playwright's `context.request` (Node's HTTP stack with the browser context's cookies) also got a 200 once.
- The pages set `ASP.NET_SessionId` (session cookie) and `__cflb` (expires after 1 hour). Neither is needed by the data endpoints.

### 3. Is a headless browser required?

- **Team games: yes**, but only to get past Cloudflare, not for rendering. The data is in the document.
- **Standings: yes**, for the same reason. The data is one `GET` returning XML, called with `fetch` from inside a page on www.football.org.il so it goes out from the browser.
- Neither needs the site's own JavaScript. My runs that captured the data let the site's scripts load. Loading the documents with scripts blocked (the recommended setup) is expected to work, since Cloudflare's decision comes before any script, but KAN-67 must confirm it.

### 4. Fields available

**Fixtures** (team-games page, 5 columns, labelled by the hidden `span.sr-only`: תאריך, משחק, אצטדיון, שעה, תוצאה):

| Field | Source | Notes |
|---|---|---|
| game id | `href` `game_id=` | `-1` for every unscheduled game (28 of 34 today) |
| date | col 1, `dd/MM/yyyy` | present on all rows, including unscheduled ones (the planned round date) |
| home, away | col 2, `"<home> - <away>"` | names only; **no team ids on this page** |
| venue | col 3 (`new-desktop-only`) | empty for unscheduled games; sometimes just a city (`דימונה`) |
| time | col 4, `HH:mm` | `00:00` placeholder for unscheduled games |
| result | col 5 | empty when not played; **away goals first**, see 6 |

- **Not shown:** round / matchday, competition (league vs cup), and status (postponed, cancelled, abandoned).
- The 34 rows are the 17 opponents home and away, in chronological order, which today is also round order. But nothing labels the round: rescheduled games could reorder rows, and a cup game, if the page lists them, would have no marker.

**Identifying a `game_id=-1` game:**
- In a double round-robin each ordered pair (home, away) occurs once per season, so the stable key is **`(season_id, home team, away team)`**, never `game_id` or date.
- Once a game is scheduled it gets a real id. Not observed (it needs two scrapes days apart); KAN-67 should log it.
- Ids aren't strictly sequential: the 01/10/2026 game is `1143937`, among `11226xx` neighbours, possibly re-created after a reschedule. So even a real id may change. Upsert by the key, and store the id as an attribute.

**Standings** (`LeagueTable`, one `<a class='table_row link_url …' href='/team-details/?season_id=28&team_id=…'>` per team):

| Column (sr-only label) | Class | Example | Notes |
|---|---|---|---|
| מיקום position | `table_col place` | `12` | |
| קבוצה team | `table_col team_name` | `מ.כ. שדרות` | **team id in the row's `href`**, for all 18 rows |
| משחקים played | `table_col ltr` | `6` | |
| ניצחונות won | `table_col ltr` | `2` | |
| תיקו drawn | `table_col ltr` | `1` | |
| הפסדים lost | `table_col ltr` | `3` | |
| שערים goals | `table_col ltr goals-col` | `8-7` | **against-for**, see 6 |
| נקודות points | `table_col ltr` | `7` | |

- There's no goal-difference column (derive it).
- Row classes `team-up` (on 1st) and `team-down` (on 17th, not 18th) look like promotion / relegation line markers. Meaning not verified; leave them out of v1.
- The table's own round isn't in the response; it's the `round_id` we asked for.

### 5. Team identity across pages

- **Standings:** every row carries `team_id` in its link. Sderot is `team_id=7617`, matching the club's configured id. No row lacks an id.
- **Team-games page:** no ids at all, only names. All 17 opponent names on Sderot's page match a standings name exactly (character for character). So fixtures can resolve names to ids through the standings of the same league and season.
- Our own club is identified by the configured `team_id`. Its display name (`מ.כ. שדרות`) is what appears in its own fixture list.
- `LeagueGamesList` rows carry `data-team1` / `data-team2` ids, but only per round (34 requests a season). That's not worth it for v1.

### 6. Formats and quirks

- **Encoding:** UTF-8 everywhere (`charset=utf-8` in headers and XML prolog). The HTML has a few entities (`&nbsp;` in `LeagueGamesList` names); the XML has HTML escaped once inside `HtmlData`.
- **Dates and times:**
  - `dd/MM/yyyy` and `HH:mm`, with no timezone given. Treat them as Israel local time (`Asia/Jerusalem`).
  - `00:00` means "not set" on unscheduled games. `LeagueGamesList` writes `טרם נקבע` ("not yet set") instead.
- **Results, team-games page: the first number is the away team's goals** (in source / reading order, the string is `"<away>-<home>"`). Verified against the standings:
  - Read home-first, "מ.כ. צעירי טירה - מ.כ. שדרות 4-1" would be a Tira win, but Tira has 0 wins. Likewise "מ.כ. שדרות - מכבי קרית מלאכי עירוני 2-1" would be a Kiryat Malachi loss, but they have 0 losses.
  - Read away-first, Sderot's six games give W2 D1 L3, goals 7 for / 8 against: exactly its standings row.
- **Goals in the standings: `"<against>-<for>"`.** Dimona, 5 wins and 1 loss, shows `8-12`; Nordia, 6 losses, shows `18-2`.
- **Separators:**
  - The game cell is `"<home> - <away>"` with a space-hyphen-space separator. No team name today contains `" - "`, but split on the first `" - "` only and check that both halves are known names.
  - Names contain ASCII `"` and `'` as Hebrew gershayim / geresh (`הפועל ניר רמה"ש`, `הפ' חדרה ש. שוורץ`), dots and commas (`הפ' מחנה יהודה מאיה,אלירן`). Never split names on punctuation.
- **The "concatenated venue" seen with a text-only tool:**
  - Every cell starts with a visually hidden label (`<span class="sr-only">תאריך</span>…`), and the venue is a separate `new-desktop-only` column. Text extraction glues them together.
  - Fix: drop `.sr-only` (or use it to identify the column) and read each `div.table_col` separately.
- **Empty values:** empty cells (venue, result) are empty strings, not dashes.
- **RTL:** numeric columns in the standings have class `ltr`. Don't rely on visual order: parse the source order with the rules above.

### 7. Per-player statistics (out of scope, located only)

- **Source:** `GET /Components.asmx/LeagueStatistics?league_id=62&season_id=28&round_id=6&language_id=-1`. It's loaded when the user expands "עוד נתונים סטטיסטיים", not on scroll. Same XML envelope, 30 KB.
- **One request for the whole league, not per team.**
- **Contents:** short top-N lists only: scorers (כובשי שערים), yellow and red cards, per-team averages, fair-play table, title history. Rows link to `/players/player/?player_id=…&season_id=28`, and each list has a "more" link to `/leagues/league/details/?league_id=62&season_id=28&page_type=player-goals` (full list, not fetched).
- **Not in it:** minutes or starts. Those are presumably on player or game pages (`/leagues/games/game/?game_id=…`, `/players/player/…`). Not investigated (KAN-70); probably one request per game or per player, which matters for the request budget.

### 8. Good-citizen data

- **robots.txt today** (`200`, Cloudflare cache HIT, `last-modified: Mon, 13 Oct 2014`):

  ```
  User-agent: *
  Allow: /
  ```

  Fully open, no Crawl-delay; unchanged from what spec section 07 says.
- **Caching headers:**
  - Pages: `cache-control: private`, no `ETag` / `Last-Modified`, `cf-cache-status: DYNAMIC`.
  - ASMX endpoints: `cache-control: private, max-age=0`, no validators.
  - So conditional requests aren't possible. Our own 10-minute lock (KAN-68) is the cache.
- **Protection hit:** Cloudflare (`server: cloudflare`, edge `TLV`). It blocks non-browser clients with a `403` block page (not a challenge), with no rate-limit headers and no CAPTCHA. One headless-browser page load was apparently blocked too (its cookie jar came back empty). The site also loads Google Tag Manager, the Facebook pixel and a third-party widget; we abort all of them.
- **Proposed budget for one scrape** (one team's fixtures + its league's standings), one fresh browser context, only same-origin `document` requests and our own `fetch` allowed:
  1. `GET /team-details/team-games/?team_id={team}&season_id={season}`, timeout 30 s.
  2. Wait 3–5 s. Then `GET /leagues/league/?league_id={league}&season_id={season}`, timeout 30 s, to read `section.league-standing-table[data-table-round]` and `data-table-index` (the `box`).
  3. Wait 3–5 s. Then in-page `fetch` `GET /Components.asmx/LeagueTable?…&box={box}&round_id={round}&componentTitle=…`, timeout 20 s.
- That's **3 requests**, about 15 s, a 90 s overall cap. With the 10-minute lock per team and per league + season, that's at most 18 requests per club per hour.
- On a `403` block page: stop, report "source blocked", **no retry** until the next manual refresh. On a timeout or 5xx: one retry after 10 s at most.

### 9. Stability

- **Platform:** ASP.NET WebForms (`ScriptResource.axd`, `WebResource.axd`, `Components.asmx`, `tempuri.org` namespace), a design by Pionet.
- **Assets** are versioned by a hand-set number (`prod.min.js?v=2.0.5.14`), not build hashes. The `.axd` URLs carry encrypted per-deploy parameters, but we never request them.
- **Class names are semantic and hand-written**, not generated: `games_table`, `table_row`, `table_col`, `place`, `team_name`, `goals-col`, `league-standing-table`. Likely stable, but it's someone else's markup.
- **Most robust selectors:**
  - Team games: `section.games_table a.table_row[href*="game_id="]`. Within a row, identify each `div.table_col` by its `span.sr-only` label (תאריך / משחק / אצטדיון / שעה / תוצאה) rather than by position.
  - Standings: `a.table_row[href*="team_id="]`. Within it, the `sr-only` labels (מיקום, קבוצה, משחקים, ניצחונות, תיקו, הפסדים, שערים, נקודות), with `place` / `team_name` / `goals-col` classes as a cross-check.
  - Round and box: `section.league-standing-table[data-table-round][data-table-index]`.
- **Fragile:**
  - The score orientation and the against-for goals order. A parser test must pin both.
  - The ASMX parameter names and the `box` value (20 today).
  - The `componentTitle` text. It only labels the fragment, and it's unknown whether the server needs it; send it as the page does.
- The parser should fail loudly (no rows, an unknown label, an unknown team name) rather than ingest partial data.

## Proposed parsed output (a proposal; KAN-66 decides the contract)

```ts
// One scrape of one team's fixture list
type FixturesResult = {
  source: { teamId: number; seasonId: number; fetchedAt: string /* ISO-8601 instant */ };
  games: Array<{
    gameId: number | null;        // null when the site shows game_id=-1
    date: string;                 // "2026-09-03", Israel local date
    kickoffTime: string | null;   // "21:15"; null for the "00:00" placeholder
    homeTeamName: string;         // as shown, trimmed
    awayTeamName: string;
    homeTeamId: number | null;    // resolved by exact name via the same league's standings; null if not found
    awayTeamId: number | null;
    venue: string | null;         // null when empty
    homeScore: number | null;     // both null when not played
    awayScore: number | null;
  }>;
};
// Stable key for upserts: (seasonId, homeTeamName/Id, awayTeamName/Id); gameId is an attribute, not the key.

// One scrape of one league's standings
type StandingsResult = {
  source: { leagueId: number; seasonId: number; round: number /* the round_id requested */; fetchedAt: string };
  rows: Array<{
    position: number;
    teamId: number;               // always present today
    teamName: string;
    played: number;
    won: number;
    drawn: number;
    lost: number;
    goalsFor: number;
    goalsAgainst: number;
    points: number;               // as published (don't recompute; deductions possible)
  }>;
};
```

Deliberately left out of v1: round number per game (not on the page), status (not on the page), the `team-up` / `team-down` markers (meaning unverified), goal difference (derived).

## Node version and libraries (verified 2026-10-11)

| | Version | How checked |
|---|---|---|
| Node on this machine | v22.23.2 | `node --version` |
| Current Node LTS | **v24.21.0 "Krypton"** (Active LTS); 22.x "Jod" is in maintenance; v26.11.1 is Current, not yet LTS | `https://nodejs.org/dist/index.json` (`lts` field) |
| playwright | **1.64.0** (published 2026-10-07; `engines.node >=20`) | `npm view playwright version` / `time` / `engines` |
| cheerio | **1.2.0** (published 2026-01-23; `engines.node >=20.18.1`) | `npm view cheerio version` / `time` / `engines` |
| undici | 8.11.2 (not recommended, see below) | `npm view undici version` |

- **Node:** 24 LTS for KAN-67 (`"engines": { "node": ">=24" }`, CI on 24). Node 26 should become LTS in late October 2026 by Node's usual schedule (not verified); revisit then.
- **Libraries:**
  - **Playwright, Chromium only** (`npx playwright install chromium`; the Docker image needs Chromium's system dependencies) as the HTTP client to football.org.il.
  - **Cheerio** to parse the HTML and the unescaped `HtmlData`. Node's built-in `fetch` is enough for posting results to the backend.
  - No extra HTTP client (undici, got, axios) and no XML library: the envelope has one text element, and Cheerio's `xmlMode` (or a single regex plus entity decoding) reads it.
  - The skeleton's `playwright ^1.47.2` is stale. 1.64.0 is four days old; KAN-67 may prefer the previous minor if 1.64 shows problems.

## Open questions and risks

- **KAN-65 (ids on `Club`):**
  - `season_id` changes every season (28 = 2026/27).
  - `league_id` changes if the team is promoted or relegated, and possibly per season.
  - `team_id` looks stable across seasons (the team page takes both ids), but this is unverified.
  - The system owner will need to update season and league each summer. Consider showing the configured ids' resolved names (team name from the standings) so a wrong id is visible.
- **KAN-66 (storage and contract):**
  - Upsert games by `(season, home, away)`, not `gameId` (−1 for unscheduled games, and ids may change).
  - No round, competition or status data. A postponed game just moves date and loses its time; a cancelled one may disappear. Decide whether a game missing from a new scrape is deleted or kept.
  - Times are Israel local; store a local date + time or convert with `Asia/Jerusalem` explicitly.
  - Scores are stored home/away after the away-first fix in the parser.
- **KAN-67 (scraper):**
  - **Bot protection is the main risk.** Cloudflare blocks non-browser clients today and blocked the honest headless browser once in five loads. It may tighten at any time; nothing we may legitimately do avoids that.
  - The scraper needs a distinct "blocked" outcome (fixture `cloudflare-block-403.html`), and the UI (KAN-69) should show "last updated at …" with the old data.
  - **Policy:** the site's owner protects it against automated clients; robots.txt allows everything, and the reviewed terms have no anti-scraping clause. Gal to decide whether to contact the IFA for permission or a feed before production, and to confirm the no-evasion rule (honest User-Agent, no stealth plugins, no proxies).
  - Confirm that documents load with scripts blocked, and that `LeagueTable` works without the league page (it needs `round_id` and `box`; caching them per league + season would save a request).
  - Pin both parser quirks (away-first results, against-for goals) in tests.
  - Chromium makes the service image larger and slower to start, and each scrape needs a fresh context (no shared cookies between clubs).
- **KAN-70:** minutes and starts aren't in the league statistics; they likely need one request per game or per player, a much bigger budget than 3.
