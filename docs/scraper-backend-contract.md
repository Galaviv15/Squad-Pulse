# Scraper ↔ backend contract (KAN-66, KAN-67)

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
  "standings": boolean
}
```

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `runId` | string or `null` | required; `null` or 1–100 characters, no control characters | Opaque. Echoed as the result's `runId` and in the scraper's log lines (KAN-68 sets it). |
| `teamId` | int | required, 1..2147483647 | The football.org.il team whose fixtures to scrape. |
| `leagueId` | int | required, 1..2147483647 | Its league (the standings). |
| `seasonId` | int | required, 1..2147483647 | The season. |
| `fixtures` | boolean | required | Scrape the team's games page. |
| `standings` | boolean | required | Scrape the league table. |

All six properties are required (`runId` may be `null`). At least one of `fixtures` / `standings` must be `true`. Unknown properties are rejected. A part that's `false` is sent to the backend as `null` (not attempted).

| Status | When | Body |
|---|---|---|
| `202` | Accepted and queued. | `{"status": "QUEUED", "runId": <this request's runId>}` |
| `202` | A scrape for the same `(teamId, leagueId, seasonId)` is already queued or running. Nothing is added or changed (not even its parts). | `{"status": "ALREADY_QUEUED", "runId": <the existing run's runId>}` |
| `400` | Invalid JSON, a wrong type, a missing or out-of-range property, an unknown property, both parts `false`. | `{"error": "<fixed message>"}` |
| `401` | No, malformed or wrong bearer token. Sent with `WWW-Authenticate: Bearer`. | `{"error": "unauthorized"}` |
| `404` | Any other path. | `{"error": "not found"}` |
| `405` | Any other method on `/scrape`. Sent with `Allow: POST`. | `{"error": "method not allowed"}` |
| `413` | Body over 4 KB (4,096 bytes), with or without `Content-Length` (chunked too). | `{"error": "body over 4096 bytes"}` |
| `415` | `Content-Type` isn't `application/json` (a `charset` parameter is fine). | `{"error": "Content-Type must be application/json"}` |
| `503` | The queue is full (5 waiting behind the running scrape), or the scraper is shutting down. Try again later. | `{"error": "queue full"}` / `{"error": "shutting down"}` |

Checked in that order: path, method, token, `Content-Type`, size, body. Error messages are fixed texts: they never repeat the request (not a property name, not a value). The token is compared in constant time (SHA-256 digests of both values, so lengths don't leak) and never logged.

**Queue.** One scrape runs at a time, in-process, with a FIFO of at most 5 waiting. Dedupe is by `(teamId, leagueId, seasonId)` across waiting and running; once a scrape has finished (its result posted or dropped), the same ids can be queued again. This is a second line of defence: KAN-68's per-team and per-league locks are the primary one. The queue lives in memory: a restart drops whatever was waiting or running, with no result posted.

**What one scrape does** (scraper README for the details): at most 3 requests to football.org.il (games page if `fixtures`; league page and `LeagueTable` if `standings`), 3–5 s apart, at most one retry (a timeout or a 5xx, after 10 s), 90 s overall. A block stops the scrape at once with no retry: the blocked part and every part not yet attempted are `FAILED SOURCE_BLOCKED`.

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
- **No control characters** in any string (`runId`, team names, `venue`, `detail`): any character where Java's `Character.isISOControl` is true (U+0000–U+001F, U+007F–U+009F, so tabs and line breaks too) is a `400`. The backend builds a game's key by joining the two team names with U+001F. Leading and trailing spaces are trimmed; a control character is never trimmed away silently. Quotes, apostrophes, dots and commas are fine (`הפועל ניר רמה"ש`, `הפ' מחנה יהודה מאיה,אלירן`).
- **No `clubId`**, ever. The backend finds the clubs from `teamId` / `leagueId` / `seasonId`.

## Request

```
{
  "runId":     string | null,
  "teamId":    int, "leagueId": int, "seasonId": int,
  "fixtures":  null | FixturesPart,
  "standings": null | StandingsPart
}
```

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `runId` | string or `null` | ≤ 100 chars | Opaque. Reserved for KAN-68; ignored and not stored today. |
| `teamId` | int | ≥ 1, required | The football.org.il team whose fixtures were scraped. |
| `leagueId` | int | ≥ 1, required | Its league. |
| `seasonId` | int | ≥ 1, required | The season. |
| `fixtures` | object or `null` / absent | | `null` / absent = not attempted. |
| `standings` | object or `null` / absent | | `null` / absent = not attempted (e.g. KAN-68's per-league lock). |

At least one of `fixtures` and `standings` must be present.

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

## What the backend does

Standings are processed first, then fixtures. The two parts are independent: one rejected doesn't block the other. Each part is a **snapshot**: a stored one is replaced as a whole, never merged.

- **Standings** are stored once per `(leagueId, seasonId)`, shared by every club in that league, and only if at least one club is currently mapped to that league and season.
- **Fixtures** are stored per club, for every club currently mapped to exactly `(teamId, leagueId, seasonId)`. Normally that's one club. A game missing from a later scrape disappears.
- **Team names → ids**: each fixture name is matched exactly against the same league's standings: this request's if it has an accepted standings part, otherwise the stored ones. A name with no match keeps a `null` id (warning `UNRESOLVED_TEAM_NAMES`). The scrape doesn't fail for it.
- **Our team** is the standings row with the request's `teamId`. Without standings (none in the request, none stored), it's the one name that appears in every game.
- **Staleness:** a part whose `fetchedAt` isn't newer than the stored snapshot's is ignored (`STALE`). So an old scrape arriving late, or a replayed request, never overwrites newer data.
- **Failed parts** are logged (reason and ids) and change nothing: the last good snapshot stays. Recording a scrape status is KAN-68.
- **Validation** covers the whole request before anything is written. A `400` writes nothing.

## Response

`200` with:

```
{
  "fixtures":  { "outcome": Outcome, "reason": RejectionReason | null },
  "standings": { "outcome": Outcome, "reason": RejectionReason | null },
  "clubsUpdated": int,
  "warnings": [ { "code": WarningCode, "count": int | null } ]
}
```

`clubsUpdated` is how many clubs' fixtures were written. The response never names a club.

**Outcome**

| Value | Meaning |
|---|---|
| `STORED` | Written: replaced the stored snapshot, or was the first one. |
| `REJECTED` | Refused, nothing written. See `reason`. |
| `STALE` | Not newer than the stored snapshot. Nothing written. |
| `FAILURE_RECORDED` | The scraper sent `FAILED`. Nothing written. |
| `NOT_SENT` | The part was `null` / absent. |

**RejectionReason** (only with `REJECTED`)

| Value | Part | Meaning |
|---|---|---|
| `NOT_MAPPED` | both | No club is currently mapped to these ids (e.g. the mapping changed during the scrape). Standings: no club in that league and season. Fixtures: no club with exactly that team, league and season. |
| `DUPLICATE_GAME` | fixtures | The same home / away pairing appears twice. The page should be league-only; a playoff stage or a cup game could repeat a pairing, so this is rejected rather than overwritten silently. |
| `OWN_TEAM_UNKNOWN` | fixtures | No standings available, and not exactly one name appears in every game. |
| `INVALID` | fixtures | A game doesn't have our team on either side. |

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
| `400` | Invalid request: bad JSON or types, a missing or out-of-range field, a broken cross-field rule, an unknown property, `fetchedAt` more than 5 minutes in the future, or neither part present. Standard error body, with one `"path: problem"` entry per problem in `details` (e.g. `"fixtures.games[0].date: invalid value"`, `"standings.rows[2].recordConsistent: won + drawn + lost must equal played"`). Nothing written. |
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
  "clubsUpdated": 0, "warnings": [] }
```
