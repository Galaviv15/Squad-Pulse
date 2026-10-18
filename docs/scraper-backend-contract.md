# Scraper ↔ backend contract (KAN-66, KAN-67, KAN-72, KAN-79, KAN-80, KAN-84)

The two calls between the backend and the scraper service (`scraper/`):

1. **Trigger, backend → scraper** (KAN-67): the backend asks the scraper to scrape one (team, league, season). See [Trigger: backend → scraper](#trigger-backend--scraper). The scraper's validation (`scraper/src/server.js`) is the source of truth for it.
2. **Ingest, scraper → backend** (KAN-66): the scraper hands the result of that scrape to the backend, everything from [Endpoint and authentication](#endpoint-and-authentication) on. The request and response records in `backend/src/main/java/com/squadpulse/scrapingintegration` (`ScrapeResultRequest`, `ScrapeResultResponse`) are the source of truth for it; this document describes them. Change both together.

Both directions use the same shared secret, `SCRAPER_SERVICE_TOKEN`.

## Trigger: backend → scraper

`POST /scrape` on the scraper (default `http://127.0.0.1:8090`; `SCRAPER_HOST` / `SCRAPER_PORT`), `Content-Type: application/json`, `Authorization: Bearer <SCRAPER_SERVICE_TOKEN>`. The scraper answers at once and scrapes in the background; the result arrives later, as its own request to `POST /internal/scrape-results` (below). Nothing in the `202` says how the scrape went.

```
{
  "runId":     string | null,
  "teamId":    int,
  "leagueId":  int,
  "seasonId":  int,
  "fixtures":  boolean,
  "standings": boolean,
  "playerStats": boolean,  // optional, absent = false
  "gameDetails": boolean,  // optional, absent = false (KAN-79; sent since KAN-80)
  "knownGameIds": [int],   // optional, absent = []; only with gameDetails: true (KAN-79; sent since KAN-80)
  "cupGames": boolean      // KAN-84: defined, NOT sent yet (KAN-85 teaches the scraper and turns it on)
}
```

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `runId` | string or `null` | required; `null` or 1–100 characters, no control characters | Opaque. Echoed as the result's `runId` and in the scraper's log lines. The backend always sends a random UUID. |
| `teamId` | int | required, 1..2147483647 | The football.org.il team whose fixtures and player statistics to scrape. |
| `leagueId` | int | required, 1..2147483647 | Its league (the standings). |
| `seasonId` | int | required, 1..2147483647 | The season. |
| `fixtures` | boolean | required | Scrape the team's games page. |
| `standings` | boolean | required | Scrape the league table. |
| `playerStats` | boolean | optional; absent = `false`; if present, a boolean (`null` is a `400`) | Scrape the team's player statistics (KAN-72, KAN-73). |
| `gameDetails` | boolean | optional; absent = `false`; if present, a boolean (`null` is a `400`) | Fetch the details of up to 2 finished games not in `knownGameIds` (KAN-79, KAN-80). |
| `cupGames` | boolean | **not accepted by the scraper yet** (KAN-85); once it is: optional, absent = `false` | Load the team page and fetch the team's cup lists (KAN-84 / KAN-85). Needs only `teamId` and `seasonId`. |
| `knownGameIds` | int array | optional; absent = `[]`; only with `gameDetails: true` (present otherwise, even empty, is a `400`); ≤ 200 entries, each an integer 1..2147483647, no duplicates; any order | Games the scraper must not fetch: those whose details the backend stores **and** those it gave up on (see [Game details part](#game-details-part-ok-kan-79)). The scraper doesn't need to know which is which. |

The first six properties are required (`runId` may be `null`); `playerStats`, `gameDetails` and `knownGameIds` are optional. At least one of `fixtures` / `standings` / `playerStats` / `gameDetails` must be `true`. Unknown properties are rejected. A part that's `false` is sent to the backend as `null` (not attempted).

**`playerStats` (KAN-72, sent since KAN-73).** The backend sends it only when `true` (its trigger record leaves a `false` out of the JSON), and sets it from one constant, `ScrapeTriggerService.REQUEST_PLAYER_STATS`, which is `true`: every trigger asks for the stats. The scraper accepts the property as optional, but the backend now always sends it as `true`, and a scraper without KAN-73 rejects it as an unknown property (`400`, so every refresh is `SCRAPER_UNAVAILABLE`). So a backend with this change needs a scraper with this change: **deploy the scraper first, or together with the backend.**

**`gameDetails` and `knownGameIds` (KAN-79, sent since KAN-80).** The backend sets `gameDetails` from one constant, `ScrapeTriggerService.REQUEST_GAME_DETAILS`, which is `true` since KAN-80, and leaves a `false` `gameDetails` and an empty `knownGameIds` out of the JSON (its trigger record: `NON_DEFAULT` / `NON_EMPTY`). A scraper without KAN-80 rejects both properties as unknown (`400`, so every refresh would be `SCRAPER_UNAVAILABLE`), so, as for `playerStats`: **deploy the scraper first, or together with the backend.** The backend builds `knownGameIds` from the triggering club's own data only (club-scoped reads inside its `ClubContext`, the HTTP and a scheduled path alike), for the club's current team and season: its stored games and its given-up games, ascending, without duplicates. With more than 200 such ids it doesn't ask for `gameDetails` at all for that trigger (a WARN with ids and counts): a cut list would make the scraper fetch stored games again. 200 ten-digit ids fit the 4 KB body limit with everything else. The run record keeps the flag (`gameDetails` `"1"` / `"0"`; `"1"` for every trigger since KAN-80, `"0"` only when the club has more than 200 known games).

**`cupGames` (KAN-84): defined, not sent yet.** The backend's trigger record has the property, sets it from one constant, `ScrapeTriggerService.REQUEST_CUP_GAMES`, which is **`false`**, and leaves a `false` out of the JSON (`NON_DEFAULT`), so today's trigger body is unchanged. Today's scraper (`scraper/src/server.js`) rejects it as an unknown property (`400`, so every refresh would be `SCRAPER_UNAVAILABLE`). KAN-85 teaches the scraper the property and flips the constant. The run record keeps the flag (`cupGames` `"0"` today; a record without it, i.e. written before KAN-84, means not requested). **Deploy order for the flip:** turning it on puts cup games into `GET /matches/fixtures`, and today's frontend knows nothing of cup games (no competition or round, no extra time or penalties, and a tie won on penalties shows its level 90-minute score next to a `WIN`). So: the frontend (KAN-86) first, or together; then the scraper (KAN-85); then the backend with the constant `true` — or all together.

A trigger with game details and two known games. The scraper's tests check `parseTrigger` accepts this block; `ScraperClientTest` checks the backend's body for the same inputs has exactly these properties and values:

<!-- contract-example-trigger -->
```json
{ "runId": "3f2b6c1e-8a4d-4b7e-9c51-2d6f0e8a7b13", "teamId": 7617, "leagueId": 62, "seasonId": 28,
  "fixtures": true, "standings": true, "playerStats": true,
  "gameDetails": true, "knownGameIds": [1122634, 1122670] }
```

**What the backend sends (KAN-68).** `POST <SCRAPER_URL>/scrape` from `scrapingintegration.ScraperClient`. `SCRAPER_URL` is the scraper's origin only (`http(s)://host[:port]`, an optional trailing `/` is dropped); one with a path, user-info, a query or a fragment stops the backend from starting. It's sent only after the backend took the per-team lock (below), with a new UUID as `runId`, the club's own mapping as the ids, `fixtures: true` always, and `standings: true` only if it also took the per-league lock — otherwise `false`, because another club of the same league and season had the table scraped within the cooldown. `playerStats: true` always, and `gameDetails: true` with the club's `knownGameIds` (KAN-80; above) unless the club has more than 200 of them. The stats and the game pages share the per-team lock (same scrape, same team); the league lock doesn't concern them. Connect timeout 2 s, read timeout 5 s, no redirects followed, no retry. It treats the answers as follows:

| Answer | Backend |
|---|---|
| `202 QUEUED` with its own `runId` | Started: the locks stay for the cooldown (even if the scrape later fails), the run is `RUNNING`. |
| `202 ALREADY_QUEUED` with a `runId` of one of this club's runs | Started: that run is the club's current run; the locks stay. |
| `202 ALREADY_QUEUED` with any other `runId`, or `null` | Started: its own run stays `RUNNING` and times out (`NO_RESULT`) unless a result with its `runId` arrives. Logged at WARN: it can only happen after Redis lost the locks, or after a manual trigger. |
| `400`, `401`, `413`, `415` | Unavailable, locks released, logged at ERROR (a backend bug or a token mismatch). |
| `503`, any other status, a malformed or oversize (> 1 KB) `202`, `QUEUED` with another `runId`, no connection, a timeout | Unavailable, locks released: the site was never asked. |

Releasing deletes a lock only if it still holds that trigger's `runId`. The answer body is never logged.

**Locks (Redis).** `scrape:lock:team:{teamId}:{leagueId}:{seasonId}` (the key the queue dedupes by) and `scrape:lock:league:{leagueId}:{seasonId}`, each `SET NX PX <cooldown>` (default 10 minutes) with the `runId`. They are the primary limit on how often football.org.il is asked.

| Status | When | Body |
|---|---|---|
| `202` | Accepted and queued. | `{"status": "QUEUED", "runId": <this request's runId>}` |
| `202` | A scrape for the same `(teamId, leagueId, seasonId)` is already queued or running. Nothing is added or changed (not even its parts). | `{"status": "ALREADY_QUEUED", "runId": <the existing run's runId>}` |
| `400` | Invalid JSON, a wrong type (incl. a `playerStats` that isn't a boolean), a missing or out-of-range property, an unknown property, all four parts `false`, `knownGameIds` without `gameDetails: true`, more than 200 of them, one that isn't an integer in 1..2147483647, a duplicate. | `{"error": "<fixed message>"}` |
| `401` | No, malformed or wrong bearer token. Sent with `WWW-Authenticate: Bearer`. | `{"error": "unauthorized"}` |
| `404` | Any other path. | `{"error": "not found"}` |
| `405` | Any other method on `/scrape`. Sent with `Allow: POST`. | `{"error": "method not allowed"}` |
| `413` | Body over 4 KB (4,096 bytes), with or without `Content-Length` (chunked too). | `{"error": "body over 4096 bytes"}` |
| `415` | `Content-Type` isn't `application/json` (a `charset` parameter is fine). | `{"error": "Content-Type must be application/json"}` |
| `503` | The queue is full (5 waiting behind the running scrape), or the scraper is shutting down. Try again later. | `{"error": "queue full"}` / `{"error": "shutting down"}` |

Checked in that order: path, method, token, `Content-Type`, size, body. Error messages are fixed texts: they never repeat the request (not a property name, not a value). The token is compared in constant time (SHA-256 digests of both values, so lengths don't leak) and never logged.

**Queue.** One scrape runs at a time, in-process, with a FIFO of at most 5 waiting. Dedupe is by `(teamId, leagueId, seasonId)` across waiting and running; once a scrape has finished (its result posted or dropped), the same ids can be queued again. A deduplicated trigger changes nothing, not even `knownGameIds`: the queued job keeps its own list. That's harmless: a game fetched although another club already stored it is stored for every mapped club (per game, newer `fetchedAt` wins), and a given-up game is at worst fetched once more and counted again. This is a second line of defence: the backend's per-team and per-league locks (above) are the primary one. The queue lives in memory: a restart drops whatever was waiting or running, with no result posted.

**What one scrape does** (scraper README for the details): at most 6 requests to football.org.il, in this order: the team's games page (if `fixtures`, `playerStats` or `gameDetails`), the player statistics (`GetTeamPlayersStatisticsList`, an in-page fetch from that page, if `playerStats`), the league page and `LeagueTable` (if `standings`), then **last** up to 2 game pages (`/leagues/games/game/?game_id=…`, documents, if `gameDetails`). 3–5 s apart, at most one retry per scrape, shared by every request (a timeout or a 5xx, after 10 s), 90 s overall. The statistics are fetched only from a games page that loaded: if it didn't (a timeout, a 4xx or 5xx, a network error, the time limit), they're `FAILED` with that request's reason and the detail `not attempted: the team games page didn't load (…)`, and the page is never loaded a second time. A block stops the scrape at once with no retry: the blocked part and every part not yet attempted are `FAILED SOURCE_BLOCKED`.

**Game details (KAN-80).** The team games page is parsed once; the games come from its rows only. A row qualifies when it has a `gameId`, both scores, a kickoff at least **24 h** before the scraper's clock (the row's date and time in `Asia/Jerusalem`, DST included; a `null` time counts as 23:59 of that date) and a `gameId` not in `knownGameIds`. They're sorted by date, then kickoff time (`null` as 23:59), then `gameId` (ids aren't chronological), and the first **2** are fetched, oldest first. A game page is started only if at least **15 s** of the 90 s remain; a game not started (out of time, or after a block) is **absent** from both `games` and `failures`. Per game: a block → its `failures` entry `SOURCE_BLOCKED`, and the scrape stops; a timeout (also one cut by the 90 s cap) or a source error (incl. a `404`) → its `failures` entry with that reason, and the next game is tried; a page that doesn't parse, or isn't the requested, finished game of our team with the row's names and score → `PARSE_ERROR` (own goals and penalties included, until their markup is pinned). The part is `OK` (possibly with both lists empty, e.g. nothing qualifies) unless the games page didn't load (`FAILED` "not attempted", like the statistics), didn't parse (`FAILED PARSE_ERROR` "not attempted: the team games page didn't parse (…)"), or an earlier request was blocked (`FAILED SOURCE_BLOCKED`). A game-details failure never changes the other parts.

## Endpoint and authentication

- `POST /internal/scrape-results`, `Content-Type: application/json`, one request per scrape.
- `Authorization: Bearer <token>`, where the token is the backend's `SCRAPER_SERVICE_TOKEN` environment variable (at least 32 characters, no whitespace). The scraper reads the same variable.
- The token is a shared service secret, not a user's access token. A user's access token (even an `ADMIN`'s) is refused on `/internal/**`, and the service token is refused everywhere else.
- If the backend has no `SCRAPER_SERVICE_TOKEN`, every `/internal/**` request gets `401`.
- `/internal/**` is for service-to-service calls only. In production it must not be reachable from the internet (block it at the reverse proxy, Phase 6).

## Conventions

- **Home / away:** every game is home team first. The site shows results **away-first**; the scraper swaps them before posting.
- **For / against:** standings goals are for, then against. The site shows them **against-for**; the scraper swaps them before posting.
- **Israel local time:** `date` and `kickoffTime` are Israel local (`Asia/Jerusalem`), exactly as the site shows them, with no timezone. The backend stores them unchanged.
- **Instants** (`fetchedAt`) are ISO-8601 with an offset, e.g. `2026-10-11T09:00:00Z`: when the scraper fetched the page, by its own clock.
- **Unknown properties are rejected** (`400`) anywhere in the body, so a typo fails loudly.
- **No control characters** in any string (`runId`, team names, `venue`, player names, `detail`): any character where Java's `Character.isISOControl` is true (U+0000–U+001F, U+007F–U+009F, so tabs and line breaks too) is a `400`. The backend builds a game's key by joining the two team names with U+001F. Leading and trailing spaces are trimmed; a control character is never trimmed away silently. Quotes, apostrophes, dots and commas are fine (`הפועל ניר רמה"ש`, `הפ' מחנה יהודה מאיה,אלירן`).
- **No `clubId`**, ever. The backend finds the clubs from `teamId` / `leagueId` / `seasonId`.

## Request

```
{
  "runId":     string | null,
  "teamId":    int, "leagueId": int, "seasonId": int,
  "fixtures":    null | FixturesPart,
  "standings":   null | StandingsPart,
  "playerStats": null | PlayerStatsPart,
  "gameDetails": null | GameDetailsPart,
  "cupGames":    null | CupGamesPart
}
```

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `runId` | string or `null` | ≤ 100 chars | The trigger's `runId`, echoed. Sets that run's status (see [Run status](#run-status-kan-68)); `null` or unknown → no status changes. |
| `teamId` | int | ≥ 1, required | The football.org.il team whose fixtures were scraped. |
| `leagueId` | int | ≥ 1, required | Its league. |
| `seasonId` | int | ≥ 1, required | The season. |
| `fixtures` | object or `null` / absent | | `null` / absent = not attempted. |
| `standings` | object or `null` / absent | | `null` / absent = not attempted (e.g. KAN-68's per-league lock). |
| `playerStats` | object or `null` / absent | | `null` / absent = not attempted (KAN-72; requested by every trigger since KAN-73). |
| `gameDetails` | object or `null` / absent | | `null` / absent = not attempted (KAN-79; requested by every trigger since KAN-80, unless the club has more than 200 known games). |
| `cupGames` | object or `null` / absent | | `null` / absent = not attempted (KAN-84; not requested by any trigger until KAN-85). |

At least one of `fixtures`, `standings`, `playerStats`, `gameDetails` and `cupGames` must be present (`cupGames` alone is a valid request).

### A part: `OK` or `FAILED`

Each part has `"status": "OK" | "FAILED"`.

- **`OK`**: carries its data (below) and **no** `reason` / `detail`.
- **`FAILED`**: carries `reason` (required) and `detail` (optional), and **no** data fields.

| Field | Type | Rules |
|---|---|---|
| `reason` | `SOURCE_BLOCKED` \| `TIMEOUT` \| `SOURCE_ERROR` \| `PARSE_ERROR` | required when `FAILED` |
| `detail` | string or `null` | ≤ 500 chars; free text for the operator. The backend never logs it above DEBUG. |

`SOURCE_BLOCKED`: the site refused the request (e.g. bot protection). `TIMEOUT`: no answer in time. `SOURCE_ERROR`: the site answered with an error. `PARSE_ERROR`: the page didn't have the expected shape.

### Fixtures part (`OK`)

| Field | Type | Rules |
|---|---|---|
| `fetchedAt` | instant | required; at most 5 minutes ahead of the backend's clock |
| `games` | array of Game | 1–100, in page order |

**Game:**

| Field | Type | Rules | `null` means |
|---|---|---|---|
| `gameId` | int or `null` | ≥ 1 | Unscheduled (the site's `-1`). An attribute only, never the key: it may change after a reschedule. |
| `date` | string | `yyyy-MM-dd`, a real date, required | |
| `kickoffTime` | string or `null` | `HH:mm` | Not set yet (the site's `00:00` placeholder). |
| `homeTeamName` | string | 1–100 chars after trimming, required | |
| `awayTeamName` | string | 1–100 chars after trimming, required, ≠ `homeTeamName` | |
| `venue` | string or `null` | 1–200 chars after trimming | None shown. Send `null`, not `""`. |
| `homeScore` | int or `null` | 0–99; both scores or neither | Not played. |
| `awayScore` | int or `null` | 0–99; both scores or neither | Not played. |

Team names are sent exactly as the site shows them (trimmed). There are no team ids on the fixtures page; the backend resolves them (see below).

### Standings part (`OK`)

| Field | Type | Rules |
|---|---|---|
| `fetchedAt` | instant | required; at most 5 minutes ahead |
| `round` | int | ≥ 1, required: the `round_id` the scraper asked for (the table doesn't say) |
| `rows` | array of Row | 2–40 |

**Row:**

| Field | Type | Rules |
|---|---|---|
| `position` | int | 1..row count |
| `teamId` | int | ≥ 1, unique in the table |
| `teamName` | string | 1–100 chars after trimming, unique in the table |
| `played`, `won`, `drawn`, `lost` | int | 0–99 each, and `won + drawn + lost == played` |
| `goalsFor`, `goalsAgainst` | int | 0–999 each |
| `points` | int | −99..999, as published (deductions are possible; never recomputed) |

There is no goal-difference field: the backend derives it.

### Player statistics part (`OK`, KAN-72)

The team's season totals for every registered player, **all competitions together** (league, State Cup, Toto Cup), as one table on the team's page. Every registered player is sent, including those with 0 games / 0 minutes.

| Field | Type | Rules |
|---|---|---|
| `fetchedAt` | instant | required; at most 5 minutes ahead |
| `players` | array of PlayerStats | 1–80, none `null`, in page order; `playerId` unique in the list |

**PlayerStats** (every field required):

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `playerId` | int | ≥ 1, unique in the list | football.org.il's `player_id` (from the row's link). Never leaves the backend. |
| `name` | string | 1–100 chars after trimming | As the site shows it in this table: **surname first**, 1–3 given names. Never logged. |
| `games` | int | 0–200, and `games == starts + cameOnAsSub` | Games played. |
| `goals` | int | 0–200 | |
| `yellowCardsLeagueAndCup` | int | 0–200 | Yellow cards in the league and the State Cup. |
| `yellowCardsToto` | int | 0–200 | Yellow cards in the Toto Cup. |
| `redCards` | int | 0–200 | |
| `starts` | int | 0–200 | Games started. |
| `cameOnAsSub` | int | 0–200 | Games come on as a substitute. |
| `subbedOff` | int | 0–200 | Games substituted off. |
| `minutes` | int | 0–20000 | Minutes played. **Never** checked against `games` (added time, extra time). |

`games == starts + cameOnAsSub` is the only cross-field check. A duplicate `playerId` or a failed check is a `400` for the whole request, like the standings table's own checks: nothing from it is written, not even its other parts.

### Game details part (`OK`, KAN-79)

The details of played games from their game pages, **our side only**: the opponent is a name and a score; its lineup and scorers are never sent, stored or shown. Each game page has both lineups, the shirt number worn in that game, goals, cards and substitution minutes; there are no minutes played per player.

| Field | Type | Rules |
|---|---|---|
| `fetchedAt` | instant | required; at most 5 minutes ahead |
| `games` | array of GameDetails | 0–10, none `null`. **An empty list is a valid `OK`** (nothing to fetch, or every attempt failed). |
| `failures` | array of GameFailure | 0–10, none `null`. Only games the scraper actually requested; a game it never started (out of time, after a block) is simply absent. |

A `gameId` appears at most once across `games` and `failures` together. `games` and `failures` are both required with `OK` (empty is fine) and both absent with `FAILED`.

**GameDetails** (every field present; `null` only where stated):

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `gameId` | int | ≥ 1 | football.org.il's `game_id` (the team-games row's id). Never leaves the backend. |
| `date` | string | `yyyy-MM-dd`, a real date | From the team-games row (Israel local, like the fixtures). |
| `kickoffTime` | string or `null` | `HH:mm` | From the row. |
| `competition` | string | 1–100 chars after trimming | The `h1` before ` מחזור N`, e.g. `ליגה א' דרום`. |
| `round` | int | 1–99 | `N` from the `h1`. |
| `ownSide` | `HOME` \| `AWAY` | | Our side on the page (picked by the header's `team_id`). |
| `homeTeamName`, `awayTeamName` | string | 1–100 chars after trimming, different | Short names, as the page shows them. |
| `homeScore`, `awayScore` | int | 0–99 each | Home first. A finished game always has both. |
| `players` | array of Appearance | 1–40, none `null`, `playerId` unique in the list | **Our side only.** |

**Appearance:**

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `playerId` | int | ≥ 1 | football.org.il's `player_id`, the same id as the player statistics' (so it reaches the club's own player through the KAN-72 links). Never leaves the backend. |
| `name` | string or `null` | 1–100 chars after trimming | **First name first** (unlike the statistics). `null` = masked by the site (`*****`). Never logged. |
| `shirtNumber` | int or `null` | 0–99 | The number worn in **this** game. `null` if missing. Never the club's `Player.jerseyNumber`, and never written to it. |
| `role` | `STARTER` \| `SUBSTITUTE` \| `UNUSED` | | `SUBSTITUTE` = came on; `UNUSED` = on the bench, never came on. |
| `goalkeeper`, `captain` | boolean | | `(GK)`, `(C)`. |
| `events` | array of Event | 0–10, none `null` | Any role may have any event. |

**Event:** `kind` is one of `GOAL`, `YELLOW`, `SECOND_YELLOW`, `RED`, `SUB_ON`, `SUB_OFF`; `minute` an int 0–150 or `null` (unknown, the site's `-1`). Minutes are plain numbers as the site shows them (93, not 90+3). No other kind: own goals and penalties are unseen on the site and come with their own ticket (KAN-80 fails such a game, as a `PARSE_ERROR` failure).

**GameFailure:** `gameId` int ≥ 1; `reason` a `FailureReason` (required); `detail` string or `null`, ≤ 500 chars (never logged above DEBUG).

**No count checks**, on purpose: not starters = 11, not one goalkeeper, not our goals = our score, not subs on = subs off, not events against the role. Real pages break each of them (the scraper logs them as warnings, never with a name). Everything else above is checked, and any violation is a `400` for the whole request: nothing from it is written, not even its other parts.

**The scraper's own check (KAN-80).** Before posting, the scraper checks the payload against these rules (`scraper/src/contract.js`). A game in `games` that breaks one is **moved to `failures`** as `PARSE_ERROR` with the detail `contract check: <path>: <rule>` (the path relative to that game, never a value or a name); the other games stay, and the backend counts the moved one as a failed attempt like any other. Only a part-level violation (`fetchedAt`, a list missing or over 10, a bad `failures` entry, a `gameId` twice across the two lists, a game without a valid `gameId`) makes the whole part `FAILED PARSE_ERROR`, which would fail the run and lose its good games.

### Cup games part (KAN-84)

The team's **State Cup** games, from the team's cup list on football.org.il (the scraper's sequence is KAN-85's). Unlike the other parts, it carries **a status per competition**, so one competition's failure never touches another's games (nor the league's).

```
"cupGames": {
  "status": "OK",
  "fetchedAt": "<instant>",
  "competitions": [
    { "competition": "STATE_CUP", "status": "OK", "games": [ CupGame, … ] }
  ]
}
```

- **The part is `FAILED` only when the team page failed** (no `ownName`, so no row could be read): `reason` (+ optional `detail`), no `fetchedAt`, no `competitions`.
- **`OK`**: `fetchedAt` (at most 5 minutes ahead) and `competitions` required, no `reason` / `detail`. `competitions`: 1–5 entries, none `null`, **each `competition` at most once** (`400` otherwise).

**CupCompetition entry:**

| Field | Type | Rules |
|---|---|---|
| `competition` | `STATE_CUP` | required. The only value today, designed to grow (the regional cup, KAN-87). Any other value is a `400` (there's no `TOTO_CUP`: Liga Alef and below don't play one). |
| `status` | `OK` \| `FAILED` | required |
| `games` | array of CupGame | `OK` only, required: 0–50, none `null`. **Empty is a valid `OK`** (the site's `Games: null`). |
| `reason`, `detail` | as for a part | `FAILED` only: `reason` required, `detail` optional (≤ 500 chars, never logged above DEBUG). |

**CupGame** (every field present; `null` only where stated):

| Field | Type | Rules | `null` means |
|---|---|---|---|
| `gameId` | int or `null` | ≥ 1 | No link (or the site's `-1`, unseen). **An attribute, never the key.** Never leaves the backend. |
| `round` | string | 1–100 chars after trimming | — As the cup list shows it (free text: `ו`, `ה' - ליגות א' דרום`). Never the game page's `מחזור N`. |
| `date` | string | `yyyy-MM-dd`, a real date | — |
| `kickoffTime` | string or `null` | `HH:mm` | Not set (the site's `00:00`). |
| `homeTeamName`, `awayTeamName` | string | 1–100 chars after trimming, different | — **Full club names**, as the list shows them (not the league's short names). |
| `ownSide` | `HOME` \| `AWAY` | required | — Decided by the scraper (exact match to the team page's `h1`). |
| `venue` | string or `null` | 1–200 chars after trimming | None shown. |
| `homeScore`, `awayScore` | int or `null` | 0–99; both or neither; **home first** (the list shows away-first: the scraper swaps it) | Not played. The score **after 90 minutes** (the list's result cell). |
| `extraTimeHomeScore`, `extraTimeAwayScore` | int or `null` | 0–99; both or neither; **home first** (the list's extra-time cell is away-first `a-b`, like the result: the scraper swaps it) | No extra time. The score **after extra time, cumulative** (it includes the 90 minutes). |
| `penaltiesHomeScore`, `penaltiesAwayScore` | int or `null` | 0–99; both or neither; **home first** (the scraper swaps the list's cell like the others) | No shootout. The **shootout tally only**, never added to any score. |

- **No team ids and no name matching for cups.** A cup row has no team id, and its full names can't be matched against the standings: the backend stores the names as sent and takes our side from `ownSide`. Cup games get no ownership check (no `INVALID`, no `OWN_TEAM_UNKNOWN`) and no `UNRESOLVED_TEAM_NAMES` warning.
- **Extra time and penalties** are numbers, never text. The extra-time cell's format was seen on game `1123493` (list `2-2`, extra time `2-3`, away-first; its game page `2 : 2`, `הארכה : 3 : 2`, home first); the penalties cell's isn't yet (KAN-85 pins it with a fixture). Parsing and swapping are the scraper's job, as for the result. A cell's `sr-only` label (`הארכה`, `ב.הכרעה`) is never a value: an empty cell (only its label) is `null`, and a cell that isn't a score is that competition's `PARSE_ERROR`, never a guess.
- **`400` rules for them:** each pair both or neither; 0–99; a number, never a string (`"2-3"` is a `400` naming the field); either pair set while `homeScore` / `awayScore` are `null` (a game that went to extra time or a shootout was played). Penalties without extra time are accepted (unseen, but the competition's rules aren't assumed). **Nothing else is checked across them** — not extra time ≥ the 90-minute score, not an unequal shootout: any violation is a `400` for the whole request, and real data breaks "obvious" rules.
- **On the backend's read API** (`GET /matches/fixtures`, `GET /matches/games/{id}`), `result` is who went through: the shootout, else extra time, else the 90 minutes, with `decidedBy`.
- **Identity:** `(competition, round, homeTeamName, awayTeamName)` within the club's cup snapshot for `(teamId, seasonId)`. `gameId` is an attribute only (`game_id` is unique across the site, league and cup, so game details keyed by it can't collide).
- **Not a `400`: a duplicate key within one competition** (the same round, home and away twice). That competition is `REJECTED DUPLICATE_GAME` instead, and the request's other parts and competitions are processed: a `400` would lose its league fixtures, standings, statistics and game details too. The same pairing in two different rounds is two games.
- Every other rule above is checked as a whole before anything is written: a violation is a `400`, and nothing from the request is written.

## What the backend does

Standings are processed first, then fixtures, then player statistics, then cup games, then game details. The parts are independent: one rejected doesn't block another. Each part is a **snapshot**: a stored one is replaced as a whole, never merged.

- **Standings** are stored once per `(leagueId, seasonId)`, shared by every club in that league, and only if at least one club is currently mapped to that league and season.
- **Fixtures** are stored per club, for every club currently mapped to exactly `(teamId, leagueId, seasonId)`. Normally that's one club. A game missing from a later scrape disappears.
- **Player statistics** (KAN-72) are stored the same way: per club, for every club currently mapped to exactly `(teamId, leagueId, seasonId)`, one snapshot per `(club, teamId, seasonId)`, the whole list replaced. No such club → `REJECTED NOT_MAPPED`. A player missing from a later scrape disappears from the snapshot with their stats; a club's link of that player to one of its own players stays (and shows again when they reappear). The backend gives each player an **opaque id of its own** (a random UUID), kept across replaces for as long as the `playerId` stays in the table; a new `playerId` gets a new one. That opaque id is the only id the backend's own API shows: `playerId` (like the team, league and season ids) never leaves the backend. The ingest never writes the club's own players.
- **Game details** (KAN-79) are stored per club, for every club currently mapped to exactly `(teamId, leagueId, seasonId)`; no such club → `REJECTED NOT_MAPPED`, otherwise `STORED` (also with no games, and with games skipped as not newer). **Per game, not a snapshot:** one record per `(club, teamId, seasonId, gameId)`; a game creates its record, or replaces **its own record only** if its `fetchedAt` is newer than the stored one's (otherwise that game is skipped silently). Other games' records are never touched. Each record gets an **opaque id of the backend's own** (a random UUID), kept when it's replaced; two clubs mapped to the same team get different ids. That id is the only one the backend's API shows: `gameId` and `playerId` never leave the backend. The ingest never writes the club's own players (nor their jersey numbers).
- **Failed games** (the `failures` of an `OK` game-details part) are counted per club and game: `PARSE_ERROR` and `SOURCE_ERROR` (the page itself is broken) count as a failed attempt; `TIMEOUT` and `SOURCE_BLOCKED` (the site or the network) don't, they only update the last reason and time, so an outage can't burn healthy games. After **3** counted attempts the game is **given up**: it goes into the trigger's `knownGameIds` and shows as unavailable. Giving up lasts **3 days from the game's last counted failure** (not its last failure of any kind, so a `TIMEOUT` on a retry doesn't extend it): then the game drops out of `knownGameIds` and shows as pending, so the scraper tries it again. A retry that fails with a counting reason gives it up for another 3 days from that failure; one that succeeds stores the details. A failure for a game whose details are stored is ignored; one whose `fetchedAt` isn't newer than the game's last recorded attempt is ignored too (a replay counts once). Storing a game's details deletes its attempt count. The count itself is never reset (it keeps growing across retries).
- **Cup games** (KAN-84) are stored per club, for every club currently mapped to exactly `(teamId, leagueId, seasonId)` (no such club → every `OK` competition, and the part, `REJECTED NOT_MAPPED`), one **snapshot per `(club, teamId, seasonId, competition)`** — no `leagueId`: a cup belongs to the team and season. **Per competition, independently:** a competition the scraper reported `FAILED` writes nothing (logged at INFO with its reason and the ids; `detail` at DEBUG), so its stored games stay; a duplicate key → `REJECTED DUPLICATE_GAME`; **an empty list while the club's stored snapshot of that competition has games → `REJECTED EMPTY_REPLACES_GAMES`**, the stored games stay (within a season cup games never leave the list — played games stay listed after an elimination — so this is a site glitch, not news; checked per club, after staleness); an empty list over nothing, or over an empty snapshot, is stored as an empty snapshot. Otherwise the whole list replaces that competition's snapshot (`STORED` if written for at least one club, else `STALE`). A cup competition never touches the league snapshot, and the reverse. Logged with ids, competition and counts only: never a team name, never a round. `gameId` never leaves the backend.
- **Team names → ids**: each fixture name is matched exactly against the same league's standings: this request's if it has an accepted standings part, otherwise the stored ones. A name with no match keeps a `null` id (warning `UNRESOLVED_TEAM_NAMES`). The scrape doesn't fail for it.
- **Our team** is the standings row with the request's `teamId`. Without standings (none in the request, none stored), it's the one name that appears in every game.
- **Staleness:** a part whose `fetchedAt` isn't newer than the stored snapshot's is ignored (`STALE`); for cup games, per competition, by the part's `fetchedAt`. For game details staleness is per game (above): the part is never `STALE`. So an old scrape arriving late, or a replayed request, never overwrites newer data.
- **Failed parts** are logged (reason and ids) and change nothing: the last good snapshot stays. They do fail the run's status (below).
- **Validation** covers the whole request before anything is written. A `400` writes nothing.

### Run status (KAN-68)

After the ingest itself has succeeded, the outcomes it computed set the status of the run named by `runId` (`scrapingintegration.ScrapeRunStatusService`), shown on `GET /matches/refresh`. So a `400`, `409` or `5xx` never changes a status (the scraper's retry will).

- `runId` `null` or unknown (the run record expired after 7 days, or a manual trigger): no status change. A known `runId` whose run was for other team / league / season ids: no status change, logged at WARN.
- Only the parts the run requested count (the run record keeps `fixtures`, `standings`, from KAN-72 `playerStats`, from KAN-79 `gameDetails` and from KAN-84 `cupGames`; a record without `playerStats`, `gameDetails` or `cupGames` means not requested; no trigger requests `cupGames` until KAN-85; every trigger since KAN-73 requests `playerStats`, and since KAN-80 `gameDetails` too, unless the club has more than 200 known games): `STORED` or `STALE` is fine; `FAILURE_RECORDED` fails the run with the scraper's reason (`TIMEOUT` → `SOURCE_TIMEOUT`); `REJECTED` → `REJECTED`; `NOT_SENT` for a requested part → `NO_RESULT`. All fine → `SUCCEEDED`; otherwise `FAILED` with the first failed part's reason, in the order fixtures, standings, playerStats, cupGames, gameDetails. **Per-game `failures` never fail a run** (the part is `STORED`): otherwise one broken page would turn every refresh red. A part the run didn't request is ignored, whatever the result says about it. The `TEAM_NOT_IN_STANDINGS` warning doesn't fail a run.
- **Cup games** (KAN-84), when requested: the part `FAILURE_RECORDED` → the scraper's reason, `REJECTED` → `REJECTED`, `NOT_SENT` → `NO_RESULT`, as for the other parts. A processed part also fails the run when **a competition the backend expects is missing** from it (`ScrapeRunStatusService.EXPECTED_CUP_COMPETITIONS`, today `STATE_CUP`) → `NO_RESULT`, and per competition, in the request's order: `FAILURE_RECORDED` → its scraper reason, `REJECTED` (any reason, incl. `DUPLICATE_GAME` and `EMPTY_REPLACES_GAMES`) → `REJECTED`. `STORED` / `STALE` competitions are fine. Unlike the game details' per-game failures, a failed competition fails the refresh: a name mismatch must be visible, not silent.
- The same result twice gives the same status. A result after the run timed out (`NO_RESULT`) replaces that. A result only ever changes its own run.
- Recording the status never changes this response: if it fails (e.g. Redis is down), the `200` with the outcomes stands and a WARN is logged.

## Response

`200` with:

```
{
  "fixtures":    { "outcome": Outcome, "reason": RejectionReason | null },
  "standings":   { "outcome": Outcome, "reason": RejectionReason | null },
  "playerStats": { "outcome": Outcome, "reason": RejectionReason | null },
  "gameDetails": { "outcome": Outcome, "reason": RejectionReason | null },
  "cupGames":    { "outcome": Outcome, "reason": RejectionReason | null,
                   "competitions": [ { "competition": "STATE_CUP", "outcome": Outcome,
                                       "reason": RejectionReason | null } ] },
  "clubsUpdated": int,
  "warnings": [ { "code": WarningCode, "count": int | null } ]
}
```

`clubsUpdated` is how many clubs' fixtures were written (not the player statistics or game details). The response never names a club. `playerStats` is new in KAN-72, `gameDetails` in KAN-79 (never `STALE`), `cupGames` in KAN-84; the scraper's `backendClient.js` logs the outcome of each part in its own list (`PARTS`, four since KAN-80), `clubsUpdated` and `warnings`, and ignores any other field (so it ignores `cupGames` until KAN-85 adds it).

**`cupGames`** (KAN-84): `outcome` is `STORED` once the part was processed, whatever each competition's own outcome (like `gameDetails`; never `STALE`), `REJECTED NOT_MAPPED` when no club is mapped, `FAILURE_RECORDED` when the scraper sent the whole part `FAILED`, `NOT_SENT` when absent; in the last two `competitions` is `[]`. Otherwise `competitions` lists every competition of the request, in its order: `STORED`, `STALE`, `REJECTED` (+ `reason`) or `FAILURE_RECORDED` (the scraper sent that competition `FAILED`).

**Outcome**

| Value | Meaning |
|---|---|
| `STORED` | Written: replaced the stored snapshot, or was the first one. Game details: the part was processed (each game newer, or skipped). |
| `REJECTED` | Refused, nothing written. See `reason`. |
| `STALE` | Not newer than the stored snapshot. Nothing written. |
| `FAILURE_RECORDED` | The scraper sent `FAILED`. Nothing written. |
| `NOT_SENT` | The part was `null` / absent. |

**RejectionReason** (only with `REJECTED`)

| Value | Part | Meaning |
|---|---|---|
| `NOT_MAPPED` | all | No club is currently mapped to these ids (e.g. the mapping changed during the scrape). Standings: no club in that league and season. Fixtures, player statistics and game details: no club with exactly that team, league and season. |
| `DUPLICATE_GAME` | fixtures, cup competition | Fixtures: the same home / away pairing appears twice. The page should be league-only; a playoff stage or a cup game could repeat a pairing, so this is rejected rather than overwritten silently. A cup competition (KAN-84): the same round, home and away twice in its list (that competition only). |
| `OWN_TEAM_UNKNOWN` | fixtures | No standings available, and not exactly one name appears in every game. |
| `INVALID` | fixtures | A game doesn't have our team on either side. |
| `EMPTY_REPLACES_GAMES` | cup competition | KAN-84: the list came empty while the club's stored snapshot of that competition has games. The stored games stay. |

**WarningCode**

| Value | `count` | Meaning |
|---|---|---|
| `TEAM_NOT_IN_STANDINGS` | `null` | The standings don't contain `teamId`. The data is stored anyway; the club's mapping is probably wrong. Also logged at WARN, and shown on the read endpoints as `mappingProblem`. |
| `UNRESOLVED_TEAM_NAMES` | distinct names | Fixture team names with no exact match in the standings. Their ids stay `null`. |

A `200` is returned even when every part is rejected: those are data outcomes for the scraper to log, not client errors.

### Status codes

| Status | When |
|---|---|
| `200` | The request was valid. See the outcomes. |
| `400` | Invalid request: bad JSON or types, a missing or out-of-range field, a broken cross-field rule (incl. a duplicate `playerId`, a `gameId` twice across `games` and `failures`, an unknown event kind, an unknown cup `competition`, a competition twice — but **not** a duplicate cup game), an unknown property, `fetchedAt` more than 5 minutes in the future, or no part present. Standard error body, with one `"path: problem"` entry per problem in `details` (e.g. `"fixtures.games[0].date: invalid value"`, `"standings.rows[2].recordConsistent: won + drawn + lost must equal played"`). Nothing written. |
| `401` | No, or the wrong, `Authorization: Bearer` token, or no token configured on the backend. |
| `413` | Body over **256 KB** (262,144 bytes), with or without `Content-Length` (chunked too). A real scrape is about 15 KB. |
| `409` | Another write to the same snapshot won a race twice in a row (code `CONCURRENT_MODIFICATION`). Nothing from this request was written for that part. Retry once; with KAN-68's locks this shouldn't happen. |
| `415` | `Content-Type` isn't `application/json`. |
| `5xx` | A server error. Retry later. |

## Example

Request (cut down to three games and two rows; a real league has 18 teams and 34 games). A test posts this exact block to the real backend (`ContractExampleIntegrationTest`):

<!-- contract-example-request -->
```json
{
  "runId": "2026-10-11T09:00:00Z-7617",
  "teamId": 7617,
  "leagueId": 1010,
  "seasonId": 28,
  "fixtures": {
    "status": "OK",
    "fetchedAt": "2026-10-11T09:00:00Z",
    "games": [
      { "gameId": 1122601, "date": "2026-09-05", "kickoffTime": "20:00",
        "homeTeamName": "מ.כ. צעירי טירה", "awayTeamName": "מ.כ. שדרות",
        "venue": "טירה", "homeScore": 4, "awayScore": 1 },
      { "gameId": null, "date": "2026-10-03", "kickoffTime": null,
        "homeTeamName": "מ.כ. שדרות", "awayTeamName": "הפ' חדרה ש. שוורץ",
        "venue": null, "homeScore": null, "awayScore": null },
      { "gameId": 1143937, "date": "2026-10-03", "kickoffTime": "16:00",
        "homeTeamName": "מ.כ. שדרות", "awayTeamName": "מ.כ. צעירי טירה",
        "venue": "שדרות", "homeScore": null, "awayScore": null }
    ]
  },
  "standings": {
    "status": "OK",
    "fetchedAt": "2026-10-11T09:00:05Z",
    "round": 3,
    "rows": [
      { "position": 1, "teamId": 7001, "teamName": "מ.כ. צעירי טירה",
        "played": 3, "won": 2, "drawn": 1, "lost": 0, "goalsFor": 7, "goalsAgainst": 2, "points": 7 },
      { "position": 2, "teamId": 7617, "teamName": "מ.כ. שדרות",
        "played": 3, "won": 1, "drawn": 1, "lost": 1, "goalsFor": 4, "goalsAgainst": 6, "points": 4 }
    ]
  }
}
```

Response (Hadera isn't in this cut-down table, so one name stays unresolved). The same test checks the real answer equals this block:

<!-- contract-example-response -->
```json
{
  "fixtures": { "outcome": "STORED", "reason": null },
  "standings": { "outcome": "STORED", "reason": null },
  "playerStats": { "outcome": "NOT_SENT", "reason": null },
  "gameDetails": { "outcome": "NOT_SENT", "reason": null },
  "cupGames": { "outcome": "NOT_SENT", "reason": null, "competitions": [] },
  "clubsUpdated": 1,
  "warnings": [ { "code": "UNRESOLVED_TEAM_NAMES", "count": 1 } ]
}
```

A scrape where the site blocked the fixtures page and the standings weren't attempted:

```json
{ "runId": null, "teamId": 7617, "leagueId": 1010, "seasonId": 28,
  "fixtures": { "status": "FAILED", "reason": "SOURCE_BLOCKED", "detail": "403 from Cloudflare" } }
```

```json
{ "fixtures": { "outcome": "FAILURE_RECORDED", "reason": null },
  "standings": { "outcome": "NOT_SENT", "reason": null },
  "playerStats": { "outcome": "NOT_SENT", "reason": null },
  "gameDetails": { "outcome": "NOT_SENT", "reason": null },
  "cupGames": { "outcome": "NOT_SENT", "reason": null, "competitions": [] },
  "clubsUpdated": 0, "warnings": [] }
```

A player statistics part alone (KAN-72; names and ids made up). The scraper's tests check its local contract check accepts this block, and `ContractExampleIntegrationTest` posts it to the real backend and checks the answer below:

<!-- contract-example-player-stats -->
```json
{ "runId": null, "teamId": 7617, "leagueId": 1010, "seasonId": 28,
  "playerStats": { "status": "OK", "fetchedAt": "2026-10-11T09:00:10Z", "players": [
    { "playerId": 900001, "name": "כהן דניאל", "games": 10, "goals": 2,
      "yellowCardsLeagueAndCup": 1, "yellowCardsToto": 0, "redCards": 0,
      "starts": 8, "cameOnAsSub": 2, "subbedOff": 3, "minutes": 712 },
    { "playerId": 900003, "name": "מזרחי אבירם", "games": 0, "goals": 0,
      "yellowCardsLeagueAndCup": 0, "yellowCardsToto": 0, "redCards": 0,
      "starts": 0, "cameOnAsSub": 0, "subbedOff": 0, "minutes": 0 } ] } }
```

<!-- contract-example-player-stats-response -->
```json
{ "fixtures": { "outcome": "NOT_SENT", "reason": null },
  "standings": { "outcome": "NOT_SENT", "reason": null },
  "playerStats": { "outcome": "STORED", "reason": null },
  "gameDetails": { "outcome": "NOT_SENT", "reason": null },
  "cupGames": { "outcome": "NOT_SENT", "reason": null, "competitions": [] },
  "clubsUpdated": 0, "warnings": [] }
```

A game-details part (KAN-79; names and ids made up; cut down to four of our players): one game stored, one game the scraper couldn't parse. `ContractExampleIntegrationTest` posts it to the real backend and checks the answer below:

<!-- contract-example-game-details -->
```json
{ "runId": null, "teamId": 7617, "leagueId": 1010, "seasonId": 28,
  "gameDetails": { "status": "OK", "fetchedAt": "2026-10-11T09:00:20Z",
    "games": [
      { "gameId": 1122601, "date": "2026-09-05", "kickoffTime": "20:00",
        "competition": "ליגה א' דרום", "round": 1, "ownSide": "AWAY",
        "homeTeamName": "מ.כ. צעירי טירה", "awayTeamName": "מ.כ. שדרות",
        "homeScore": 4, "awayScore": 1,
        "players": [
          { "playerId": 920001, "name": "עומר גבאי", "shirtNumber": 1, "role": "STARTER",
            "goalkeeper": true, "captain": true, "events": [] },
          { "playerId": 920002, "name": "נועם אזולאי", "shirtNumber": 9, "role": "STARTER",
            "goalkeeper": false, "captain": false,
            "events": [ { "kind": "GOAL", "minute": 38 }, { "kind": "RED", "minute": 40 } ] },
          { "playerId": 920004, "name": null, "shirtNumber": 22, "role": "SUBSTITUTE",
            "goalkeeper": false, "captain": false,
            "events": [ { "kind": "SUB_ON", "minute": 57 }, { "kind": "YELLOW", "minute": null } ] },
          { "playerId": 920005, "name": "רועי שלום", "shirtNumber": 30, "role": "UNUSED",
            "goalkeeper": false, "captain": false, "events": [ { "kind": "YELLOW", "minute": 93 } ] }
        ] }
    ],
    "failures": [ { "gameId": 1122670, "reason": "PARSE_ERROR", "detail": "unknown event icon" } ] } }
```

<!-- contract-example-game-details-response -->
```json
{ "fixtures": { "outcome": "NOT_SENT", "reason": null },
  "standings": { "outcome": "NOT_SENT", "reason": null },
  "playerStats": { "outcome": "NOT_SENT", "reason": null },
  "gameDetails": { "outcome": "STORED", "reason": null },
  "cupGames": { "outcome": "NOT_SENT", "reason": null, "competitions": [] },
  "clubsUpdated": 0, "warnings": [] }
```

A cup-games part alone (KAN-84; names, rounds, ids and scores made up in the shape of the KAN-76 capture): two State Cup games, the second level after 90 minutes and extra time and won on penalties (all home first). `ContractExampleIntegrationTest` posts it to the real backend and checks the answer below:

<!-- contract-example-cup-games -->
```json
{ "runId": null, "teamId": 7617, "leagueId": 1010, "seasonId": 28,
  "cupGames": { "status": "OK", "fetchedAt": "2026-10-11T09:00:30Z",
    "competitions": [
      { "competition": "STATE_CUP", "status": "OK", "games": [
        { "gameId": 1123491, "round": "ה' - ליגות א' דרום", "date": "2026-08-27",
          "kickoffTime": "19:00", "homeTeamName": "מועדון כדורגל שדרות",
          "awayTeamName": "שמשון פזטל תל אביב", "ownSide": "HOME", "venue": "שדרות אצטדיון שדרות",
          "homeScore": 1, "awayScore": 0, "extraTimeHomeScore": null, "extraTimeAwayScore": null,
          "penaltiesHomeScore": null, "penaltiesAwayScore": null },
        { "gameId": 1142007, "round": "ו", "date": "2026-10-12", "kickoffTime": "15:00",
          "homeTeamName": "הפועל רהט", "awayTeamName": "מועדון כדורגל שדרות", "ownSide": "AWAY",
          "venue": "רהט הפועל רהט", "homeScore": 1, "awayScore": 1,
          "extraTimeHomeScore": 2, "extraTimeAwayScore": 2,
          "penaltiesHomeScore": 4, "penaltiesAwayScore": 5 } ] } ] } }
```

<!-- contract-example-cup-games-response -->
```json
{ "fixtures": { "outcome": "NOT_SENT", "reason": null },
  "standings": { "outcome": "NOT_SENT", "reason": null },
  "playerStats": { "outcome": "NOT_SENT", "reason": null },
  "gameDetails": { "outcome": "NOT_SENT", "reason": null },
  "cupGames": { "outcome": "STORED", "reason": null,
    "competitions": [ { "competition": "STATE_CUP", "outcome": "STORED", "reason": null } ] },
  "clubsUpdated": 0, "warnings": [] }
```
