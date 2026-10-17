# KAN-76: cup-games spike — findings

Investigated on 2026-10-17 (15:37–15:39 Israel time) against Sderot FC (מ.כ. שדרות / מועדון כדורגל שדרות), Liga Alef South: `team_id=7617`, `season_id=28`. No production code. The two cup lists are in `../test/fixtures/natcup-7617-s28.xml` and `../test/fixtures/totocup-7617-s28.xml` (see its README). Method and policy as in `KAN-70-findings.md` and `KAN-75-findings.md`; this replaces KAN-70 §9, which described the cup calls without saving them.

## Summary

- **The cup lists give every cup game of the team for the season, played or not**, from two in-page calls: `GetTeamNatCupData` (State Cup) and `GetTeamTotoCupData` (Toto Cup). Per game: the round as the cup names it (`ה' - ליגות א' דרום`, `ו`), date, home and away (**full club names**), venue, kick-off time, the result (**away-first**, like the team-games page), extra-time and penalties cells, and the `game_id` link. State Cup today: 2 played games; Toto: empty (`Games: null`).
- **Format, new against KAN-70 §9:** with `fetchInPage`'s headers both answer `text/xml`, but **not** the `ResponseData` envelope: `<string xmlns="http://tempuri.org/">` whose text is a JSON object `{StandingTable, Comments, Games}`, `Games` an HTML string or `null`. `asmx.js`'s `loadHtmlData` can't read it; a small sibling reader can.
- **No team id in a cup row.** Our side has to come from a name. Our full club name is in **no page a scrape loads today** (team-games, stats, league page, `LeagueTable` all use the short `מ.כ. שדרות`). It **is** the team page's `h1` (`<span class='big'>מועדון כדורגל שדרות</span>`). Proposed: load the team page and make both cup calls from it, then pick our side by that exact name.
- **Cloudflare: passed 10 of 10** requests, production route rule, 0 blocks, 0 challenges.
- **Cost: +3 requests per scrape** (team page + 2 calls; the calls take 58–164 ms), about +9 s with the pauses. Proposed sequence measured at ~23 s without the team page and game pages; ~27 s with the team page, ~36 s with 2 game pages, 9 requests at most.

## Method

- **Node** 24.21.0 (`~/.nvm/versions/node/v24.21.0/bin/node --version`; the shell's default is 22, not used). **Playwright** 1.64.0 (`scraper/package.json` `^1.64.0`, lockfile `node_modules/playwright-core` 1.64.0, `npx playwright --version` in `scraper/` under Node 24 → `Version 1.64.0`), **Chromium** build `chromium-1248` / `chromium_headless_shell-1248` from the local Playwright cache (`~/Library/Caches/ms-playwright`). Cheerio from `scraper/node_modules`. Scripts lived in a scratch folder outside the repo and imported Playwright, Cheerio and `scraper/src/parseStandings.js` (`parseLeaguePage`, to build the `LeagueTable` URL as production does) by absolute path. No install, no lockfile or `node_modules` change.
- **Setup:** headless, User-Agent `SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)`, locale `he-IL`, the production route rule: same-origin documents, plus a `fetch` only when its URL equals the one exact URL about to be fetched in-page, everything else aborted (61 + 145 + 73 subresources aborted). In-page fetches sent exactly `fetchInPage`'s header (`x-requested-with: XMLHttpRequest`, nothing else). `waitUntil: "domcontentloaded"`, 30 s timeout. Pauses random 3–5 s.
- **Runs:**
  - **1:** team-games page → `GetTeamNatCupData` → `GetTeamTotoCupData`.
  - **2** (one simulated full scrape): team-games page → player stats → NatCup → Toto → league page → `LeagueTable`. No game page.
  - **3:** the team page alone (question 3: where our full club name is).
- Every body was saved; cup rows were dumped cell by cell with Cheerio; run 1's and run 2's cup bodies were compared byte for byte.

Key commands (scripts not committed):

```js
// Serialized by URL so each equals, character for character, what the route rule compares against.
const natUrl = new URL(`${ORIGIN}Components.asmx/GetTeamNatCupData?team_id=7617&season_id=28`).href;
const totoUrl = new URL(`${ORIGIN}Components.asmx/GetTeamTotoCupData?teamId=7617&seasonId=28`).href;
await page.goto(`${ORIGIN}team-details/team-games/?team_id=7617&season_id=28`, { waitUntil: "domcontentloaded", timeout: 30000 });
// wait 3–5 s
allowedFetch = natUrl;
const r = await page.evaluate(async (u) => {
  const res = await fetch(u, { headers: { "x-requested-with": "XMLHttpRequest" } });
  return { status: res.status, headers: Object.fromEntries(res.headers.entries()), body: await res.text() };
}, natUrl);
// reading it:
const json = JSON.parse(load(r.body, { xml: true })("string").text()); // {StandingTable, Comments, Games}
const $ = load(json.Games);
```

## Requests

Times UTC (Israel = UTC+3). Size in characters. All `200`.

| Run | Sent at | Request | Type | Size | Time | `cache-control` / `cf-cache-status` |
|---|---|---|---|---|---|---|
| 1 | 12:37:05.2 | team-games `?team_id=7617&season_id=28` | `text/html` | 167,258 | 361 ms | `private` / `DYNAMIC` |
| 1 | 12:37:10.2 | `GetTeamNatCupData?team_id=7617&season_id=28` | `text/xml` | 4,042 | 164 ms | `private, max-age=0` / `DYNAMIC` |
| 1 | 12:37:14.3 | `GetTeamTotoCupData?teamId=7617&seasonId=28` | `text/xml` | 136 | 93 ms | `private, max-age=0` / `DYNAMIC` |
| 2 | 12:38:05.0 | team-games | `text/html` | 167,258 | 951 ms | `private` / `DYNAMIC` |
| 2 | 12:38:09.9 | `GetTeamPlayersStatisticsList?teamId=7617&seasonId=28&isFemale=%22False%22` | `text/xml` | 41,136 | 145 ms | `private, max-age=0` / `DYNAMIC` |
| 2 | 12:38:14.9 | `GetTeamNatCupData` | `text/xml` | 4,042 | 111 ms | `private, max-age=0` / `DYNAMIC` |
| 2 | 12:38:19.4 | `GetTeamTotoCupData` | `text/xml` | 136 | 58 ms | `private, max-age=0` / `DYNAMIC` |
| 2 | 12:38:23.8 | league `?league_id=62&season_id=28` | `text/html` | 172,819 | 85 ms | `private` / **`HIT`** |
| 2 | 12:38:27.3 | `LeagueTable?league_id=62&season_id=28&box=20&round_id=6&componentTitle=…` | `text/xml` | 18,761 | 78 ms | `private, max-age=0` / `DYNAMIC` |
| 3 | 12:38:53.8 | team page `/team-details/?team_id=7617&season_id=28` | `text/html` | 195,553 | 674 ms | `private` / `DYNAMIC` |

Run 1: 9.6 s from browser start to the last body. Run 2: **22.6 s**, 6 sent / 145 aborted. Run 3: 0.8 s.

## Answers

### 1. Response format

- Both calls, with `fetchInPage`'s headers, answer `text/xml; charset=utf-8`:

  ```
  <?xml version="1.0" encoding="utf-8"?>\r\n<string xmlns="http://tempuri.org/">{"StandingTable":null,"Comments":null,"Games":"<h2 class='white_title'>רשימת המשחקים</h2>…"}</string>
  ```

  So **KAN-70 §9 is half right**: the payload is the `{StandingTable, Comments, Games}` object, but with our headers it isn't JSON with a `d` key (that was the site's own call with `content-type: application/json`, as for the stats). It's a .NET `string` return value serialized as XML: the element's text is the JSON, and inside the JSON the HTML's `<`, `>`, `'` are `<`, `>`, `'`, so the XML needs no escaping. HTML entities (`&nbsp;`) are left as entities inside `Games`.
- **Not `ResponseData`:** no `HtmlData`, so `asmx.js`'s `loadHtmlData` throws `no HtmlData…`. Needed: a sibling reader, e.g. load as XML, require exactly one root `string` element, `JSON.parse` its text, require an object with a `Games` key that is a string or `null`, then `load(Games)`. A `JSON.parse` failure or a missing key is a `PARSE_ERROR`.
- **`Games`:** the State Cup's is the HTML list (below). The Toto Cup's is `null` (question 4).
- **`StandingTable` / `Comments`:** `null` in both today. A Toto group stage could fill `StandingTable` (its name suggests it); unseen, so not parsed. Proposed: ignore both, but log (never fail on) a non-`null` value, so the first one is noticed and can be captured.
- No difference between calling from the team-games page and the site's own call from the team page apart from the envelope: both worked from the team-games page at once, so the team page wasn't needed for the calls (it was loaded for question 3 only).

### 2. Rows

`Games` = `<h2 class='white_title'>רשימת המשחקים</h2><div class='scroll_table' …><div class='table_view full_view'>` + one header row + one `<a class='table_row link_url' title='קישור לעמוד משחק' href='/leagues/games/game/?game_id=…'>` per game. Nothing else inside `table_view`.

- **Header row:** `<div class='table_header_row js-sticky-init' role='presentation' aria-hidden='true'>`, 8 cells: סיבוב, תאריך, משחק, אצטדיון, שעה, תוצאה, הארכה, ב.הכרעה. Skip it by class.
- **Cells by position** (every row has exactly 8 `div.table_col` children; read by position, never by label):

  | # | Content | `sr-only` label | `1123491` | `1142007` |
  |---|---|---|---|---|
  | 0 | round, as the cup names it | `תאריך` (**wrong**) | `ה' - ליגות א' דרום` | `ו` |
  | 1 | date `dd/MM/yyyy` (4-digit year, like the team-games page) | `סיבוב` (**wrong**) | `27/08/2026` | `12/10/2026` |
  | 2 | game: home, away | the home name + `&nbsp;- ` | `מועדון כדורגל שדרות` / `שמשון פזטל תל אביב` | `הפועל רהט` / `מועדון כדורגל שדרות` |
  | 3 | venue | `אצטדיון` | `שדרות אצטדיון שדרות` | `רהט הפועל רהט` |
  | 4 | kick-off `HH:mm` | `שעה` | `19:00` | `15:00` |
  | 5 | result, **away-first** | `תוצאה` | `0-1` | `4-1` |
  | 6 | extra time | `הארכה` | empty | empty |
  | 7 | penalties | `ב.הכרעה` | empty | empty |

- **`sr-only` labels:** only cells 0 and 1 are swapped (KAN-70 said "shifted by one cell": **contradicted**, it's a swap of the first two). Cell 2's label is the home name, not `משחק`. Cells 3–7 are labelled correctly. Still: by position.
- **The game cell** (KAN-70's "glues the home name twice": **confirmed, and explained**): `<span class='sr-only'>הפועל רהט&nbsp;- </span><span class='team-name-text'>הפועל רהט&nbsp;- </span><span class='team-name-text'>מועדון כדורגל שדרות</span>`. `.text()` on the cell gives the home name twice because the `sr-only` copy is included. **Split rule:** take the cell's `span.team-name-text` elements, require exactly 2, strip a trailing `U+00A0 - ` (regex ` -\s*$`) from the first, trim both. Never split the text on `-`, so a name with a hyphen is safe (none in today's rows).
- **Venue:** the same format as the team-games page (`שדרות אצטדיון שדרות` is in both). Empty → `null`, by analogy (unseen here).
- **Time:** real times on both rows. The team-games page's `00:00` placeholder (unset) wasn't seen in a cup row; treat `00:00` as `null` the same way.
- **Result, score order:** **away-first, proven.** The list's `1142007` shows `4-1`; its game page (`game-1142007.html`) shows `<div class='total'><i class='sr-only'>הפועל רהט</i>1 : <i class='sr-only'>מ.כ. שדרות</i>4</div>`, home first: Rahat 1, Sderot 4. So list `a-b` = away `a`, home `b`. `1123491` agrees: `0-1` = Sderot (home) 1, Shimshon 0. Sderot won and played the next round, which a home-first reading (Sderot losing 0-1) would contradict. Same convention as `parseFixtures.js`.
- **Extra time / penalties:** both cells contain only their `sr-only` label today. A game decided in extra time or on penalties wasn't seen, so their format is **unknown**.
- **`game_id`:** a relative link on every row (2 of 2), `game_id` the only parameter. No `-1` seen; whether a drawn but unscheduled cup tie is listed, and with what id, is unknown. **No future cup game** existed to capture: both rows are played.
- **Empty list:** `Games: null` (Toto). An empty but present list (header row, no games) wasn't seen.

### 3. Our side

Checked in the prompt's order:

1. **A team id or link in the row: none.** The row's only link is the game's. No `team_id`, no `data-*` attribute, no class marking a side.
2. **Our full club name from a page a scrape already loads: none.** `מועדון כדורגל שדרות` occurs 0 times in the team-games page, the stats response, the league page and `LeagueTable` (run 2's bodies, searched). They all use the short `מ.כ. שדרות`; `LeagueTable` links it to `team_id=7617`, but with the short name only.
3. **The team-games page: no** (as 2).
4. **The team page: yes.** `GET /team-details/?team_id=7617&season_id=28`, server-rendered:

   ```html
   <h1 class="page_main_title container clearfix"><span>בוגרים 2026/2027</span><span class='big'>מועדון כדורגל שדרות</span></h1>
   ```

   Exactly one `h1.page_main_title span.big`. It is the page **of our `team_id`**, so the name is tied to our id, not guessed. The same full names appear on that page's fair-play table (`תחרות הקבוצה ההוגנת`: `מועדון כדורגל צעירי טירה - מועדון כדורגל שדרות`, league games only, so it isn't a cup source). The game pages' timelines also use full names, but not on every page (a 0-0 without cards has no timeline team), so they aren't a source.

**Proposed rule:** load the team page; `ownName` = the trimmed text of `h1.page_main_title span.big` (exactly one, non-empty, else `PARSE_ERROR`). In each cup row, exactly one of home / away must equal `ownName` exactly → `ownSide` `HOME` / `AWAY`. A row with neither or both rejects that competition's list (`PARSE_ERROR`): the list is our team's own, so such a row means the page isn't what we think. Make the two cup calls **from the team page** (the site does the same, so the `Referer` is natural), so the extra document costs nothing else.

**How sure:** high for today. Both rows match exactly, from one capture of each page. The `h1` is tied to the `team_id` we asked for. **Failure mode:** if the site ever shows a different full name in the `h1` than in the cup list (a rename, a sponsor name, a typo fixed in one place), the cup part fails visibly as `PARSE_ERROR` and the stored cup games stay: never a wrong side.

**Cheaper alternative (not recommended):** no team page; `ownName` = the one name present in every cup row of both lists. That's 1 request less. It fails with a single cup row (both names qualify, e.g. at the season's first cup round, which is exactly when the list is short) and with two rows of the same pairing. Then the scraper can't tell, and the part would have to fail until a second game appears.

### 4. Competitions

- **State Cup:** `GetTeamNatCupData` (`team_id` / `season_id`, snake_case). It already includes the **regional stage**: `1123491`'s round is `ה' - ליגות א' דרום` (round 5, the Liga Alef South section). That's the State Cup's preliminary round for this league, not a separate regional cup.
- **Toto Cup:** `GetTeamTotoCupData` (`teamId` / `seasonId`, camelCase: **the two styles are confirmed**). Empty today, exactly:

  ```
  <?xml version="1.0" encoding="utf-8"?>\r\n<string xmlns="http://tempuri.org/">{"StandingTable":null,"Comments":null,"Games":null}</string>
  ```

  Parse `Games: null` as "no games" (`OK`, empty list), never as an error. Whether Liga Alef plays a Toto Cup at all this season is unknown: the site's menu lists only `גביע הטוטו ליגת WINNER` and `גביע הטוטו לאומית` for season 28.
- **Regional cup (`גביע אזורים`): no source found.** It exists as a competition (`/national-cup/?national_cup_id=782&season_id=28`, in the site-wide menu on every page), but the team page makes no per-team call for it: its `Components.asmx` calls are `TeamStatistics` × 6, `GetTeamNatCupData`, `GetTeamTotoCupData` and the stats (KAN-70 §1). I didn't browse the competition page (the prompt allowed it only if it fitted; a per-team source is what's needed, and that would mean scraping a whole competition's games to find ours). It's most likely a lower-league competition; unverified.

### 5. Identity

| Competition | Round (list) | `game_id` | Home | Away | Result (home–away) |
|---|---|---|---|---|---|
| State Cup | `ה' - ליגות א' דרום` | `1123491` | מועדון כדורגל שדרות | שמשון פזטל תל אביב | 1–0 |
| State Cup | `ו` | `1142007` | הפועל רהט | מועדון כדורגל שדרות | 1–4 |

- **Round strings are stable between fetches:** run 1's and run 2's NatCup bodies are byte-identical (51 s apart, which is weak evidence; over days, unknown). They are free text, not numbers (`ה' - ליגות א' דרום` vs `ו`), and they **disagree with the game page** (`1142007`: `ו` here, `מחזור 4` in its `h1`). Never join the two by round.
- **Can a pairing repeat within one cup?** Not in today's data. The State Cup is single-leg (one game per round, 2 rounds, 2 games). A two-legged tie would reverse home and away, so it would still give distinct (home, away) keys. A **replay** (an abandoned game replayed in the same round) could repeat the exact key; unseen. A Toto group stage (if Liga Alef has one) could pair the same teams twice in different rounds, which the round in the key separates.
- **Proposed key:** `(competition, round, homeTeamName, awayTeamName)`, as Gal proposed, within the club's cup snapshot for (team, season); `gameId` kept as an attribute. A duplicate key in one competition's list rejects that competition (like `DUPLICATE_GAME`), never merges.
  - **Why not `game_id`:** it would be simpler and would survive a renamed round, and both cup rows have one. But nothing shows that a cup tie is never listed before it has an id (league rows are `-1` until scheduled, KAN-64), or that the id never changes on a reschedule. Keying cups like league games keeps one rule for both snapshots. **Trade-off:** if the site renames a round label, the game gets a new key: one replace step shows it once, under the new label (the snapshot is replaced whole, so no duplicate remains). Only something keyed on the old key (an opaque id kept across replaces) would be lost.
  - Game details stay keyed by `gameId` (as today: `knownGameIds`, `GameDetails`). `game_id` is unique across the site, league and cup alike, so the two can't collide.

### 6. Game details for cup games

From `game-1142007.html` (not fetched again) and the code as it is:

- **`selectGames`** takes team-games rows only. A cup row would work **unchanged** once it's in the same shape (`gameId`, `date` `yyyy-MM-dd`, `kickoffTime` `HH:mm` | `null`, `homeScore`, `awayScore`, home-first): the filter, the 24 h rule and the sort use nothing else. Needed: feed it league rows + cup rows together, so "oldest first, at most 2" spans all competitions. `knownGameIds` needs no change.
- **`parseGameDetails`**:
  - Our side by the header `team_id` works for cups (`7617` is the guest in `1142007`).
  - The `h1` parses with the existing pattern: `גביע המדינה ווינר מחזור 4` → competition `גביע המדינה ווינר`, round 4. Only one cup `h1` was seen: `1123491`'s page (a preliminary round) wasn't fetched, and its `h1` might not have the `מחזור N` form; then that game fails as a `PARSE_ERROR` (counted, given up after 3), not the part.
  - The scores check works (page `1 : 4` = the cup row's home 1, away 4, once the row is read away-first).
  - **The names check breaks:** the page's header shows short names (`מ.כ. שדרות`), the cup row full names (`מועדון כדורגל שדרות`), so `homeTeamName differs from the team-games row` (or the away one) throws for every cup game where our name differs. Today that's our side; `הפועל רהט` happens to be the same in both.
- **What a cup row must carry so it works:** `gameId`, date, kick-off, home-first scores, and its `ownSide`. The check for a cup row should be: the page's `ownSide` (by `team_id`) equals the row's `ownSide`, and both scores equal; **no name comparison** (there's no short name in the cup list to compare). `date` / `kickoffTime` come from the cup row, as for league rows. The `GameDetails` sent keeps the page's short names, `competition` and `round` from the `h1`, unchanged.

### 7. Request budget

- **Measured (run 2):** team-games 951 ms, stats 145 ms, NatCup 111 ms, Toto 58 ms, league page 85 ms, `LeagueTable` 78 ms; pauses 3.96, 4.84, 4.40, 4.34, 3.51 s. **22.6 s** from browser start to the last body. Team page (run 3): 674 ms. The cup calls are tiny (4 KB and 136 B).
- **Proposed position:** after `LeagueTable`, before the game pages: team-games → stats → league page → `LeagueTable` → **team page → NatCup → Toto** → game pages.
  - The existing parts come first, so a slow cup step can never cost them; under the cap, cups lose before standings, and game details (lowest priority, picked again next time) stay last.
  - The cup calls run in-page from the team page they need anyway for our name.
  - Played cup games enter the game-detail selection only if the cup lists parsed in this scrape. If they failed, the selection uses the league rows alone, as today.
- **Timeouts:** team page 30 s (a document), each cup call 20 s (as the other fetches); `request()` already shrinks them to the time left, and they share the one retry.
- **Typical:** ~22.6 s measured (6 requests) + team page (~4 s pause + 0.7 s) ≈ **27 s**; + 2 game pages (2 × ~4.6 s) ≈ **36 s**, well inside 90 s.
- **Worst case: no, it doesn't fit, and it doesn't today either.** Every request at its timeout, longest pauses: team-games 30 + 5 + stats 20 + 5 + league 30 + 5 + table 20 = 115 s before anything new (today's worst case already exceeds 90 s). With the cup step: + 5 + 30 + 5 + 20 + 5 + 20 = 200 s before the game pages. What bounds a scrape is the 90 s cap, not the count. Inside the worst case's first 90 s only team-games, stats and the league page fit. The 15 s guard then simply never starts a game page. With the order above, the cap cuts the cup part (`FAILED TIMEOUT`, the stored cup games stay) and the game pages, never an existing part. Proposed: give the cup step the same guard as the game pages (start the team page only with ≥ 15 s of the cap left), so a nearly exhausted scrape doesn't send a request it can't finish.
- **Volume:** at most 9 requests per scrape (+ 1 retry): 6 today + 3. With the 10-minute lock: at most **54 requests per team per hour** (36 today). The two cup calls are tiny; the team page is ~196 KB, like the other documents.

### 8. Good citizen

- **Caching:** documents `cache-control: private`, ASMX `private, max-age=0`; no `ETag` / `Last-Modified` (none in the logged headers), so nothing to revalidate with. New: the league page came back **`cf-cache-status: HIT`** this time (Cloudflare served it from cache); every other response was `DYNAMIC`. Edge `TLV` throughout.
- **Cloudflare:** 10 of 10 passed, no `403`, no challenge, production route rule (3 browser sessions).
- **Requests: 10 in total** (budget 12), 0 blocks, 0 retries. Run 1: 3, run 2: 6, run 3: 1.
- **Delays within runs:** 4.70, 3.93 s (run 1); 3.96, 4.84, 4.40, 4.34, 3.51 s (run 2), between a response and the next request.
- **Between runs: 50.7 s** (run 1 → 2) and **26.5 s** (run 2 → 3), both short of the 60 s asked; a mistake on my side (I didn't check the clock before starting the next run).
- `robots.txt`: not fetched (unchanged in KAN-70, `Allow: /`).
- No images or logos were requested or saved. The fixtures hold names, venues, scores, rounds and `game_id` links only.

## Proposed cup part (a proposal; KAN-84 decides the contract)

Trigger (same style as `docs/scraper-backend-contract.md`):

```
{ …, "cupGames": boolean }   // optional, absent = false
```

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `cupGames` | boolean | optional; absent = `false`; `null` is a `400` | Scrape the team page and its State Cup and Toto Cup lists (KAN-85). Needs only `teamId` and `seasonId`. |

As with `playerStats` / `gameDetails`: the backend sets it from one constant and a scraper without KAN-85 rejects it, so **deploy the scraper first, or together**.

Result: a fifth part, `cupGames: null | CupGamesPart` (`null` / absent = not attempted).

**CupGamesPart (`OK`):**

| Field | Type | Rules |
|---|---|---|
| `fetchedAt` | instant | required; at most 5 minutes ahead |
| `competitions` | array of CupCompetition | exactly one entry per competition fetched (today `STATE_CUP` and `TOTO_CUP`), each competition at most once |

The part is `FAILED` only when the team page failed (no `ownName`, so no row can be read). Otherwise it's `OK`, and each competition carries its own result, so **a failed Toto call never deletes the State Cup games**, and neither touches the league snapshot.

**CupCompetition:**

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `competition` | `STATE_CUP` \| `TOTO_CUP` | required | `GetTeamNatCupData` / `GetTeamTotoCupData`. The read side adds `LEAGUE` for the league snapshot's games. |
| `status` | `OK` \| `FAILED` | required | As a part: `OK` carries `games`, `FAILED` carries `reason` (+ `detail`). The backend replaces that competition's games only on `OK`. |
| `games` | array of CupGame | 0–50, with `OK` only. **Empty is a valid `OK`** (`Games: null`). |

**CupGame** (every field present; `null` only where stated):

| Field | Type | Rules | `null` means |
|---|---|---|---|
| `gameId` | int or `null` | ≥ 1 | No link / `-1` (unseen). An attribute, never the key. Never leaves the backend. |
| `round` | string | 1–100 chars after trimming | — The cup list's own label, as shown (`ה' - ליגות א' דרום`, `ו`). Never the game page's `מחזור N`. |
| `date` | string | `yyyy-MM-dd`, a real date (from `dd/MM/yyyy`) | |
| `kickoffTime` | string or `null` | `HH:mm` | `00:00` placeholder (unseen in cups). |
| `homeTeamName`, `awayTeamName` | string | 1–100 chars after trimming, different | **Full club names**, as the cup list shows them. |
| `ownSide` | `HOME` \| `AWAY` | | Decided by the scraper: the side whose name equals the team page's `h1` name (question 3). The backend does no name matching for cups. |
| `venue` | string or `null` | 1–200 chars after trimming | None shown. |
| `homeScore`, `awayScore` | int or `null` | 0–99; both or neither; **home first** (the scraper swaps the list's away-first result) | Not played. |
| `extraTime`, `penalties` | string or `null` | 1–20 chars after trimming, as shown; never interpreted | Empty cell (today: always). The format is unseen; see risks. |

**Key (backend):** `(competition, round, homeTeamName, awayTeamName)` within the club's cup snapshot for (team, season); a duplicate within one competition rejects that competition.

## Proposed scrape sequence

1. Team-games page (document, 30 s), if `fixtures`, `playerStats` or `gameDetails`.
2. Player stats (in-page, 20 s), if `playerStats`.
3. League page + `LeagueTable`, if `standings`.
4. **If `cupGames`** and ≥ 15 s of the cap remain: pause, team page `GET /team-details/?team_id={team}&season_id={season}` (document, 30 s); read `ownName`. Then, from that page, pause, `GetTeamNatCupData?team_id={team}&season_id={season}` (in-page, 20 s); pause, `GetTeamTotoCupData?teamId={team}&seasonId={season}` (in-page, 20 s). Each URL through `new URL(…).href`. A failed or unparsable call fails only its competition. The team page failing fails the part, and both calls are "not attempted".
5. If `gameDetails`: select from the team-games rows **plus** this scrape's parsed cup rows (home-first, with `ownSide`), as today; for a cup row, check `ownSide` and scores, not names.
6. A block stops everything left, as today.

## Open questions and risks

- **KAN-84 (backend):**
  - The contract above is a proposal: `competitions` with a per-competition status (my suggestion) vs one flat `games` list.
  - Cup snapshot keyed per club, team, season and competition; replaced per competition only when that competition is `OK`.
  - Merged read: league games (`LEAGUE`, no round from the team-games page) + cup games (`STATE_CUP` / `TOTO_CUP` + the cup `round`), by date then kick-off. The existing ownership check (`INVALID` without the standings name) must not be applied to cups: their names are full names and `ownSide` comes with each game.
  - Team ids for cup opponents: the standings can't resolve full names (and most cup opponents are in other leagues), so cup games carry no team ids; KAN-86 must not need them.
  - `detailsId` / `detailsState` for cup games by `gameId`, as for league games; `knownGameIds` gains the cup games' stored ids, which is still well under 200 per season.
  - Never expose `game_id` (spec §09); our own id per cup game if one is needed.
- **KAN-85 (scraper):**
  - New reader for the `<string>`-wrapped JSON (question 1); parse rows by position; names from the two `span.team-name-text`; result away-first → home-first.
  - Extra time and penalties: the format is unseen. Sending the raw text (as proposed) keeps a decided-on-penalties game from failing the whole State Cup list; pin the format from the first real one and add a fixture. Which score the result cell shows after extra time (90 or 120 minutes) is also unknown.
  - A `game_id` of `-1`, a future (unplayed) cup row and an empty-but-present list are unseen; add fixtures when they appear (the State Cup's next draw).
  - `StandingTable` / `Comments` non-`null` (a Toto group stage?): log, don't fail; capture it.
  - Cup game pages: only one cup `h1` seen (`מחזור 4`); a preliminary round's page may not match `^(.+) מחזור (\d+)$`.
  - The 15 s guard for the cup step, and its place after `LeagueTable`.
  - Pin in tests: the swapped `sr-only` labels (read by position), the `&nbsp;- ` suffix, a name with a hyphen (synthetic), `Games: null`, `ownSide` HOME (`1123491`) and AWAY (`1142007`), the away-first result, the team page `h1`.
- **KAN-86 (frontend):**
  - A competition label per game; a cup round is free text (`ו`, `ה' - ליגות א' דרום`), shown as given, never parsed into a number.
  - Cup games show full club names, league games short ones: the same opponent can appear under two names; no matching between them.
  - The next-match card across all competitions: league and cup games merged by date and kick-off.
- **Regional cup:** no per-team source; nothing to build unless a club in a lower league needs it.
- **Volume:** 54 requests per team per hour at most (was 36); the cup calls are small, the team page is one more ~196 KB document.
