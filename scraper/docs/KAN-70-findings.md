# KAN-70: player-statistics spike — findings

Investigated on 2026-10-13 (09:16–09:23 Israel time) against Sderot FC (מ.כ. שדרות), Liga Alef South: `team_id=7617`, `season_id=28`, `league_id=62`. No production code. The trimmed sample response is in `../test/fixtures/team-players-stats-7617-s28.xml` (see its README). Method and policy as in `KAN-64-findings.md`.

## Summary

- **The player-statistics table is loaded later, by one XHR**: `GET /Components.asmx/GetTeamPlayersStatisticsList?teamId={team}&seasonId={season}&isFemale=%22False%22`. The team page's HTML holds only an empty placeholder. The response is one HTML table with **every registered player** (34 today, 7 of them with 0 minutes), each row linking to `/players/player/?player_id=…&season_id=…`, with all 10 columns Gal described.
- **It needs no team-page load.** Called with `fetch` from inside the team-games page we already load, with exactly the production `fetchInPage` headers (`x-requested-with` only), it answers the same `ResponseData` XML envelope as `LeagueTable`. Its `HtmlData` is byte-identical to the JSON the site's own jQuery gets.
- **Cloudflare: passed 3 of 3** verification scrapes (≥ 60 s apart), using the production route rule extended only by this one exact URL. 0 blocks over the whole investigation.
- **Cost: +1 request per scrape** (4 instead of 3), about 14 s per scrape in total, well inside the 90 s cap.
- Layer B (game details) is in the game page's HTML, one request per game: lineups, bench, sub minutes, per-game shirt numbers, goals, cards, competition and round. Layer C (player card) holds birth month/year in its HTML; its per-game list is one more XHR per player. Both use the same `player_id`.

## Method

- **Browser:** the Playwright installed for `scraper/`: `playwright` 1.64.0 (`scraper/package.json` `^1.64.0`, lockfile `node_modules/playwright-core` 1.64.0, `npx playwright --version` in `scraper/` → `Version 1.64.0`). Chromium build `chromium-1248` from the local Playwright cache; the requests' `sec-ch-ua` reported `HeadlessChrome` 156. Investigation scripts lived in a scratch folder outside the repo and imported Playwright and Cheerio from `scraper/node_modules` by absolute path. No install, no lockfile or `node_modules` change.
- **Setup:** headless, User-Agent `SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)`, locale `he-IL`, a context `route` handler that logged every response (method, URL, status, headers, XHR bodies) and aborted everything else:
  - **"docs" mode** (production rule): same-origin documents only, plus one exact `fetch` URL when we call it.
  - **"site" mode** (discovery only, 2 runs): same-origin scripts and XHRs allowed too, so the site's own requests show; images, fonts, media, stylesheets, SVGs and every third-party host still aborted.
- **Volume:** 49 requests to football.org.il, 0 blocks (see 10).

Key commands (scripts not committed):

```js
// Verified setup = scraper/src/browserSource.js's rule; allowedFetch is set just before each in-page fetch.
const statsUrl = new URL(
  `${ORIGIN}Components.asmx/GetTeamPlayersStatisticsList?teamId=7617&seasonId=28&isFemale=${encodeURIComponent('"False"')}`,
).href; // → …&isFemale=%22False%22, equal to the URL the browser reports
await page.goto(`${ORIGIN}team-details/team-games/?team_id=7617&season_id=28`, { waitUntil: "domcontentloaded", timeout: 30000 });
// wait 3–5 s
allowedFetch = statsUrl;
const xml = await page.evaluate(async (u) => (await fetch(u, { headers: { "x-requested-with": "XMLHttpRequest" } })).text(), statsUrl);
// wait 3–5 s, then the league page and LeagueTable exactly as today
```

## Verification of the proposed setup

Each scrape: a fresh browser, the production route rule (same-origin `document`s + a `fetch` equal to the one URL we're about to request), the four requests below in order with a random 3–5 s pause between them. Times UTC; status / body length in characters.

| Run | Team games | Players stats (new) | League page (round, box) | `LeagueTable` | Total | Sent / aborted |
|---|---|---|---|---|---|---|
| 1 | 06:18:53 — 200 / 167,203, 34 rows | 06:18:57 — 200 / 40,040, `text/xml`, 34 rows | 06:19:01 — 200 / 238,799 (6, 20) | 06:19:08 — 200 / 18,763, 18 rows | 14.8 s | 4 / 142 |
| 2 | 06:20:10 — 200 / 167,203, 34 rows | 06:20:15 — 200 / 40,040, `text/xml` | 06:20:20 — 200 / 238,799 (6, 20) | 06:20:24 — 200 / 18,763, 18 rows | 14.3 s | 4 / 142 |
| 3 | 06:21:27 — 200 / 167,203, 34 rows | 06:21:31 — 200 / 40,040, `text/xml` | 06:21:35 — 200 / 238,799 (6, 20) | 06:21:40 — 200 / 18,763, 18 rows | 13.3 s | 4 / 141 |

- 3 of 3 complete, 0 blocks. The players-stats fetch took 125–192 ms.
- Run 1's stats body was parsed in full (34 rows); runs 2 and 3 returned the same length (my row counter misread their XML; not re-parsed).
- The team-games page is now 167,203 characters (176,521 in KAN-64), still 34 game rows. Not investigated; KAN-73 should re-check `team-games-7617-s28.html` against the live page.

## Answers

### 1. Delivery

- The team page (`GET /team-details/?season_id=28&team_id=7617` → 200, `text/html; charset=utf-8`, 195,736 characters) has only a placeholder:

  ```html
  <section class="statistic_table container players-statistics-container">
      <h2 class="campaign_name container " …>נתונים סטטיסטיים</h2>
      <h2 class='white_title'>סטטיסטיקות שחקנים</h2>
      <div class="players-statistics playersStatisticsDatajs" data-isFemale="False"></div>
  </section>
  ```

- `team.min.js?v=2.0.5.14` fills it with:
  - `GET /Components.asmx/GetTeamPlayersStatisticsList?teamId=7617&seasonId=28&isFemale=%22False%22`
  - Request headers from the site: `accept: application/json, text/javascript, */*; q=0.01`, **`content-type: application/json; charset=utf-8`**, `x-requested-with: XMLHttpRequest`, `referer: <team page>`, cookies `ASP.NET_SessionId` and `__cflb`.
  - → 200, `application/json; charset=utf-8`, Brotli, `cache-control: private, max-age=0`, 54,483 characters: `{"d":{"__type":"ResponseData","HtmlData":"<…>","HaveLink":false}}`.
- **Parameter names differ from `LeagueTable`:** camelCase `teamId` / `seasonId`, and `isFemale` is a JSON-encoded string (`"False"` with quotes, i.e. `%22False%22`). The other team-page calls are inconsistent too (`TeamStatistics?team_id=…&season_id=…`, `GetTeamTotoCupData?teamId=…&seasonId=…`).
- **Parameters:** `teamId` and `seasonId` are the club's mapping. `isFemale` comes from the placeholder's `data-isFemale` on the team page. It's not on the team-games page, so the proposal sends the constant `"False"` (see risks).
- **Content negotiation:** without `content-type: application/json` (our `fetchInPage`), the same URL answers `text/xml`, the `ResponseData` envelope exactly like `LeagueTable`, 40,040 characters. Its unescaped `HtmlData` is **byte-identical** to the JSON variant's (checked). So KAN-73 can reuse the `LeagueTable` envelope reader and needs no header change.
- **Trigger:** the XHR fired about 4 s after `domcontentloaded`, together with the page's other 8 `Components.asmx` calls (`TeamStatistics` × 6, `GetTeamNatCupData`, `GetTeamTotoCupData`). My run scrolled at about the same moment, so on-load vs on-scroll wasn't separated (`team.min.js` wasn't saved). It doesn't matter: we call it ourselves, and the site's scripts never run under the production rule.

### 2. Cloudflare and the route rule

- **Passed 3 of 3** (table above), with `browserSource.js`'s rule extended only by this exact URL. Since `fetchInPage` already sets `allowedFetch` to the URL it fetches, **the rule's code needs no change**, just a second call.
- Over the whole investigation (5 browser sessions, 1 plain `curl` for `robots.txt`): no `403`, no challenge.

### 3. Request budget

- **No separate team-page load.** The fetch runs from the team-games page that step 1 already loaded. `Referer` is then the team-games page; it didn't matter.
- Proposed sequence (one fresh context, as today):
  1. `GET /team-details/team-games/?team_id={team}&season_id={season}` (document, 30 s).
  2. Wait 3–5 s. In-page `fetch` `GET /Components.asmx/GetTeamPlayersStatisticsList?teamId={team}&seasonId={season}&isFemale=%22False%22` (20 s). **New.**
  3. Wait 3–5 s. `GET /leagues/league/?league_id={league}&season_id={season}` (document, 30 s).
  4. Wait 3–5 s. In-page `fetch` `LeagueTable` (20 s).
- It goes second because the stats are a per-team part like the fixtures, and step 1's page is still loaded. If the stats are ever requested without the fixtures, they still need some same-origin document first; the team-games page is the natural one.
- **Time:** 13–15 s measured. Worst case without retries: 30 + 5 + 20 + 5 + 30 + 5 + 20 = 115 s of timeouts. That's already possible today (85 s with 3), and the existing 90 s cap and the "one retry per scrape" rule handle it. The scraper's `request()` already shrinks a timeout to the time left.
- With the 10-minute lock: at most 24 requests per club per hour (18 today).

### 4. Structure

The fragment (inside `HtmlData`):

```html
<div class='scroll_table mobile-scroll'><div class='table_view table_sorter full_view results-grid results-home teams-table league-games'>
  <div class='table_header_row head-blue sorter'>
    <div class='table_col align_content' data-sort='PlayerName' data-asc='False' aria-sort='ascending'><a … class='sorter_but'><span class='sr-only'>לחץ כאן כדי למיין לפי</span> שם השחקן</a></div>
    …
  </div>
  <a class='table_row link_url' title='קישור לעמוד שחקן' href='/players/player/?player_id=48702&season_id=28'>
    <div class='table_col align_content'><span class='sr-only'>שם השחקן</span> אבוחצירה ירדן משה</div>
    <div class='table_col'><span class='sr-only'>מס. משחקים</span> 8</div>
    …
  </a>
  …
</div></div>
```

| Column (cell `span.sr-only` label) | Header `data-sort` | Format | Proposed field |
|---|---|---|---|
| `שם השחקן` | `PlayerName` | `" <surname> <given names>"`, leading space | `name` |
| `מס. משחקים` | `GamesCount` | `" 8"`, integer | `games` |
| `שערים` | `Goals` | integer | `goals` |
| `כ. צהובים ליגה/גביע` | `YellowCardsLeague` | integer | `yellowCardsLeagueAndCup` |
| `כ. צהובים טוטו` | `YellowCardsToto` | integer | `yellowCardsToto` |
| `כ. אדומים` | `RedCards` | integer | `redCards` |
| `הרכב פותח` | `Opened` | integer | `starts` |
| `נכנס כמחליף` | `Substitue` (sic) | integer | `cameOnAsSub` |
| `הוחלף` | `OutOfGame` | integer | `subbedOff` |
| `דקות משחק` | `TotalMin` | integer | `minutes` |

- **Robust selectors:** rows `a.table_row[href*="player_id="]`. Within a row, identify each `div.table_col` by its `span.sr-only` text (remove it, then trim the rest). The header's `data-sort` keys are a cross-check. Fail loudly on an unknown or missing label.
- **Name cell:** the name is the cell's text after the `sr-only` label, with a leading space. The link is on the row, **relative**: `/players/player/?player_id={id}&season_id={season}`, no other params. Read `player_id` with `URLSearchParams`, not a regex on position.
- **Empty values:** none today. Every numeric cell is a number, and players who never played show `0` everywhere (not empty, not `-`). A parser should still reject anything that isn't `^\d+$` after trimming.
- **Paging / cut:** none. One table, 34 rows, no "show more", no hidden rows. The only other element is the loading animation `aside.loader` (removed in the fixture).
- **Order:** by name ascending (`PlayerName`, `aria-sort='ascending'`), Hebrew alphabetical. Sorting is client-side (`table_sorter`, `sorter_but`). Don't rely on the order.

### 5. Gal's facts, checked on today's data (34 rows)

| Fact | Result |
|---|---|
| The columns listed | **Confirmed**, in that order (right to left = source order). |
| Covers all competitions | **Confirmed.** Sderot has played 6 league games (fixtures page) plus 2 State Cup games (`GetTeamNatCupData`: 27/08 vs שמשון פזטל ת"א, 12/10 vs הפועל רהט); the most games in the table is 8. Toto cup: none (`GetTeamTotoCupData` → `Games: null`). |
| Every registered player, also 0 minutes | **Confirmed:** 34 rows, 34 distinct `player_id`s, 7 with 0 games / 0 minutes (`105826`, `246425`, `222522`, `203484`, `206327`, `181014`, `231487`). Not cross-checked against the squad page (not fetched, as agreed). |
| `games = starts + came on` | **Confirmed on all 34 rows.** Also `subbedOff ≤ games` everywhere. |
| Surname first | **Confirmed** in this table: player `85286` is `אביב גל` here and `גל אביב` on his own card; `41607` is `חידריאן ליאור` here and `ליאור חידריאן` on the game page. **11 of 34 names have three words** (e.g. `אבוחצירה ירדן משה`, `צור שדי יואב`), so surname vs given names can't be split reliably. |
| Minutes > 90 × games | **Confirmed** on 7 rows, e.g. `158410` and `188824`: 1 game, 96 minutes; `120427`: 8 games, 769. |
| `player_id` stable across seasons | **Not verifiable from one season's data.** Supported: the card has a season selector going back to 2008/09 under one `player_id`, and substitutes' links on the game page omit `season_id` altogether. |
| Sort chevrons → client-side, JS-loaded | **Confirmed:** JS-loaded (1), sorted client-side (4). |
| Per-game shirt number in game details | **Confirmed** (7). |

No row breaks any rule.

### 6. Quirks

- **`sr-only` glue, again:** every cell starts with its hidden label (`<span class='sr-only'>שם השחקן</span> אבוחצירה…`), and each header cell starts with `לחץ כאן כדי למיין לפי`. Text extraction without removing `.sr-only` glues them together.
- **Leading spaces** in every cell: trim.
- Names today: no geresh, gershayim, dots, commas, Latin letters or double spaces, but 1–3 given names. Don't assume two words, and never split on punctuation (club names do contain `"` and `'`).
- Numeric cells have no `ltr` class here (unlike `LeagueTable`).
- `Substitue` is misspelled in the header's `data-sort`; don't "fix" it if it's used as a key.
- Encoding: UTF-8; the HTML is escaped once inside `HtmlData` (XML) or a JSON string (JSON).
- The `"False"` in the query is percent-encoded (`%22`). Building the URL with `new URL(…).href` gives the exact string the browser reports, which the exact-match route rule needs.

### 7. Game details page (layer B, located)

- `GET /leagues/games/game/?game_id=1122631` (Tira–Sderot, 4-1 to Sderot, round 1) → 200, `text/html`, 266,878 characters. **Everything is in the initial HTML.** No data XHR fired (site mode); `game.min.js` only reloads the page for live games (`data-onlive`).
- Cloudflare: passed (1 of 1, site mode).
- **Present:**
  - **Competition and round** in the `h1`: `ליגה א' דרום מחזור 1`.
  - Teams, result, half-time score, date, time and venue.
  - **Both lineups**, as groups under `section.game-details .players-cont`: `div.home.Active` / `div.guest.Active` (הרכב פותח, 11 each), `div.home.Replacement` / `div.guest.Replacement` (מחליפים, 5 each), `div.home.Bench` / `div.guest.Bench` (לא שותפו, 4 each), plus the coaches.
  - Per player `a[href*="player_id="] > div.player`:
    - `span.number`: **the shirt number in that game**, as `מס' 1` (strip the prefix).
    - `span.name > b`: the name, **first name first** (opposite of the stats table).
    - A position only for the goalkeeper (`- (GK)`).
    - `span.moves`: `span.yellow` (yellow card + minute), `change-down` (subbed off + minute), `change-up` (came on + minute), and goals as a bare `<span class="sr-only">שער</span><span class="sr-only">דקה</span>36` with no wrapper class.
  - A timeline (`node goal team1/team2 …`) with goals and scorers.
- **Not present:** minutes played per player. They'd be derived from sub minutes and an assumed game length, so they won't match the stats table's minutes (added time).
- **Same `player_id`** as the stats table. But starters' links carry `&season_id=28` and substitutes' / bench links don't (`/players/player/?player_id=128092`).
- **Cost: 1 document request per game.** About 40 games per season at Liga Alef, mostly once each.
- **Data of other clubs:** the page holds both teams' players. KAN-75 should keep only our team's group (home or guest).

### 8. Player card (layer C, located)

- `GET /players/player/?player_id=85286&season_id=28` → 200, `text/html`, 154,045 characters. Passed Cloudflare (1 of 1).
- **In the HTML:** name (first name first), **birth month and year** (`תאריך לידה: 08/1998`), citizenship, current team and league (`מ.כ. שדרות (א דרום)`), a season selector (2008/09 onwards). **No position** found.
- **Per-game list: loaded later.** `player.min.js` calls `GET /Components.asmx/GetPlayerGames?player_id=85286&season_id=28` once the window scrolls ≥ 400 px (`contentType: application/json`) → 200, `application/json`, `d` = an HTML string (4,047 characters). One `a.table_row` per game, linking to `/leagues/games/game/?season_id=28&game_id=…`, with `sr-only` labels: תאריך, **מסגרת (competition**: `גביע המדינה ווינר`, `ליגה א' דרום`), משחק, תוצאה, שערים, cards, חילופים (`יצא 87`).
- **Cost: 2 requests per player** (document + XHR; the XHR also needs some same-origin page loaded). Too much to do per scrape for 34 players; nothing in layer A needs it.

### 9. Cup games (for KAN-76, noted only)

- The team page loads them itself: `GET /Components.asmx/GetTeamNatCupData?team_id=7617&season_id=28` (State Cup) and `GetTeamTotoCupData?teamId=7617&seasonId=28` (Toto; `Games: null` today). The response is JSON whose `d` is a **JSON string** containing `{StandingTable, Comments, Games}`, `Games` being HTML.
- State Cup today: 2 games, each linking to `/leagues/games/game/?game_id=…`, with columns סיבוב (round), תאריך, משחק, אצטדיון, שעה, תוצאה, הארכה (extra time), ב.הכרעה (penalties). Rows:
  - `1123491`: 27/08/2026, `מועדון כדורגל שדרות - שמשון פזטל תל אביב`, result `0-1`, round `ה' - ליגות א' דרום`
  - `1142007`: 12/10/2026, `הפועל רהט - מועדון כדורגל שדרות`, result `4-1`, round `ו`
- **Quirk:** the `sr-only` labels are shifted by one (the round cell is labelled `תאריך`, the date cell `סיבוב`), and the game cell's text glues the home name twice (`מועדון כדורגל שדרות - מועדון כדורגל שדרות - …`). Here team names are the **full club names**, not the short names of the league pages. Don't label cells by `sr-only` in this response.
- Round / stage / competition per game is also shown on the game page's `h1` (7) and in the player card's `מסגרת` column (8).

### 10. Good citizen

- **robots.txt** (06:23 UTC, `curl`, honest UA): `200`, Cloudflare `HIT`, `last-modified: Mon, 13 Oct 2014`, `User-agent: * / Allow: /`. Unchanged since KAN-64. (`cache-control: max-age=691200` now shown.)
- **Caching:** unchanged. The ASMX responses are `cache-control: private, max-age=0`, `cf-cache-status: DYNAMIC`, no validators.
- **Cloudflare:** same edge (`TLV`), no block or challenge in 5 browser sessions + 1 `curl`.
- **Requests: 49 in total**, 0 blocks:

  | When (UTC) | Run | Requests |
  |---|---|---|
  | 06:16:28 | Team page, production rule | 1 |
  | 06:17:35 | Team page, site mode (document, 8 static scripts, 9 XHRs) | 18 |
  | 06:18:53–06:21:40 | Verification, 3 scrapes × 4 | 12 |
  | 06:21:57–06:22:18 | Game page + player card, site mode (documents, scripts, 1 XHR) | 17 |
  | 06:23:03 | `robots.txt` | 1 |

- **Delays:** separate runs 67 s, 78 s, 77 s, 77 s apart, and **once only 17 s** (the layer B/C run started 17 s after verification run 3's last request; a mistake). Within runs: 3–5 s between the verification's requests (the production pauses), 16 s between the two layer B/C documents. In the two site-mode runs, the site's own scripts sent their XHRs in parallel, as a normal visit does.

## Proposed parsed output (a proposal; KAN-72 decides the contract)

```ts
// One scrape of one team's player season statistics (all competitions)
type PlayerStatsResult = {
  source: { teamId: number; seasonId: number; fetchedAt: string /* ISO-8601 instant */ };
  players: Array<{
    playerId: number;              // football.org.il player_id from the row's link; stable across seasons (unverified)
    name: string;                  // as shown, trimmed: surname first, 2–3+ words
    games: number;                 // = starts + cameOnAsSub (the parser may check, and fail loudly if not)
    goals: number;
    yellowCardsLeagueAndCup: number;
    yellowCardsToto: number;
    redCards: number;
    starts: number;
    cameOnAsSub: number;
    subbedOff: number;
    minutes: number;               // may exceed 90 × games
  }>;                              // every registered player, including 0-minute ones; order not meaningful
};
// Key: (teamId, seasonId, playerId). A scrape replaces the team's season list as a whole.
```

All fields required; no optional fields today. Deliberately left out: per-competition splits (not in this table), shirt number and position (not in this table; layer B), any photo.

## Proposed change to the scrape sequence

- Requests: team games → **players stats (new)** → league page → `LeagueTable` (3.3 above). Same 3–5 s pauses, 30 s / **20 s** / 30 s / 20 s timeouts, same 90 s cap and one retry per scrape. A block stops everything that's left, as today.
- Route rule: unchanged code. `allowedFetch` is set to the stats URL for the duration of that one `fetchInPage` call, then to the `LeagueTable` URL. Never widen it to all `fetch` / `xhr`.
- Headers: unchanged (`x-requested-with` only) → XML envelope, same reader as `LeagueTable`.
- The stats are their own part of the result (like `fixtures` / `standings`), so a parse failure there doesn't fail the fixtures.

## Open questions and risks

- **KAN-72 (backend):**
  - **Linking:** names are surname first here, first name first on game pages and cards, with 1–3 given names. "Word order ignored" means comparing word sets; expect partial matches (a middle name present on one side only). Link by `playerId` once confirmed by a user; the name is a display value that may change.
  - `playerId` is a football.org.il id: store it, never show it (spec §09's rule for the mapping should extend to it).
  - Totals cover all competitions and won't add up to the league fixtures: label them so.
  - A player who leaves may disappear from the next scrape. Decide whether stored stats for an unlisted player are dropped or kept, and what happens to a manual link pointing at it.
  - The yellow-card split is "league + State Cup" vs "Toto", not league vs cups.
  - The rows hold other people's personal data (names and activity) of our own team only; no birth dates, no photos.
- **KAN-73 (scraper):**
  - `isFemale` is sent as the constant `"False"`. A women's team would need `"True"`, read from the team page (`data-isFemale`, one more document) or from a new mapping field. Untested what `"True"` returns for a men's team.
  - Parameter names (`teamId`/`seasonId`, JSON-quoted `isFemale`) are the fragile part, as `box` / `componentTitle` were for `LeagueTable`.
  - Pin in tests: `sr-only` removal, leading spaces, `^\d+$` cells, `games = starts + cameOnAsSub`, unknown-label failure, 0-minute rows kept.
  - Re-check `team-games-7617-s28.html` against today's page (its size changed since KAN-64).
- **KAN-75 (per-game data):**
  - 1 document per game; scrape each played game once (it doesn't change afterwards), never on every refresh.
  - Minutes per game aren't on the page; derive from sub minutes only if needed, and they won't match the season total.
  - Keep only our team's group; the other team's players are another club's data.
  - Player links on the game page drop `season_id` for substitutes and bench: read `player_id` only.
- **KAN-76 (cup games):**
  - Source: `GetTeamNatCupData` / `GetTeamTotoCupData` from inside a same-origin page, +1–2 requests per scrape.
  - Their `sr-only` labels are misaligned and the game cell is glued: parse by position there, and pin it in a test.
  - Team names are full club names (`מועדון כדורגל שדרות`), unlike the short league names: no name match against the standings.
  - The cup's round strings (`ה' - ליגות א' דרום`, `ו`) aren't numbers.
  - The game page's `h1` (`<competition> מחזור N`) gives the competition and round for any game id.
