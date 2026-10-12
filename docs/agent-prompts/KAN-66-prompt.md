# KAN-66: Backend: store scraped fixtures and league standings, internal ingest endpoint (service auth), read endpoints

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `650e480`, the KAN-65 merge, PR #48). If it doesn't, stop and report. Then create the branch `feature/KAN-66-scraped-fixtures-standings-ingest`.

## Context

Jira KAN-66, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-65 (done: `Club.footballOrgIl`). Blocks KAN-67 (the Node scraper, which will post to the endpoint built here) and KAN-68 (refresh trigger, locks, scrape status).

The scraper is a separate Node service that never touches MongoDB. It scrapes football.org.il (one team's league fixtures page + its league's standings) and posts the parsed result to the backend. This ticket gives that data a home, defines the scraper ↔ backend contract, and exposes it read-only to the club's users.

This is the most security-sensitive change since auth: it adds the **first non-user caller** of the API and the **first tenant-shared data**. Both touch CLAUDE.md standing rule 4 directly. Treat every decision below with that weight.

Facts from `scraper/docs/KAN-64-findings.md` (read sections 4, 5, 6 and "Open questions and risks → KAN-66"):

- The team-games page lists **league games only** (cups are on a separate page). 34 rows today = 17 opponents × home/away.
- Fixture rows have **team names only, no team ids**. Standings rows carry `team_id`. Names match character for character between the two.
- `game_id` is `-1` for every unscheduled game, and real ids may change after a reschedule. So `game_id` is **an attribute, never the key**. The stable key is `(seasonId, homeTeamName, awayTeamName)`.
- Unscheduled games show time `00:00` → stored as `null` (time not set yet), still listed.
- Dates and times are Israel local (`Asia/Jerusalem`), no timezone given.
- Results on the page are away-first and standings goals are against-for: **the scraper fixes both before posting**. The contract here is always home/away and for/against.
- Standings `points` are as published (deductions possible, never recompute). No goal-difference column (derive it).

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Storage: two snapshot documents

**Fixtures: one document per (club, team, season)**, a `ClubScopedEntity` (normal isolation layer, no exception). Collection name your call (e.g. `league_fixtures`). Fields:

- `teamId`, `leagueId`, `seasonId` (the mapping it was scraped for), `teamName` (our team's name as shown in the standings / fixtures), `fetchedAt` (`Instant`, from the scraper), `storedAt` (`Instant`, server time), `version` (`@Version`, see 5).
- `games`: an array, in the order received, each with: `key` (derived from home + away names, your format), `gameId` (`Integer`, `null` for `-1`), `date` (Israel local date), `kickoffTime` (Israel local time, `null` when not set), `homeTeamName`, `awayTeamName`, `homeTeamId` / `awayTeamId` (resolved, see 3; `null` if unresolved), `venue` (`null` when empty), `homeScore` / `awayScore` (both `null` when not played).
- Unique index on `(clubId, teamId, seasonId)`.

A successful ingest **replaces the whole `games` array** in one single-document write. A game missing from a later scrape simply disappears. A failed or rejected scrape writes nothing, so the last good snapshot stays.

**Standings: one document per (league, season)**, `@NotClubScoped`: **the second use of that annotation ever**, and the first tenant-shared data. Fields: `leagueId`, `seasonId`, `round` (the round the scraper requested), `fetchedAt`, `storedAt`, `version`, and `rows`: `position`, `teamId`, `teamName`, `played`, `won`, `drawn`, `lost`, `goalsFor`, `goalsAgainst`, `points`. Unique index on `(leagueId, seasonId)`. Replaced as a whole on every successful ingest.

The rules that make the shared standings safe (each must hold, and each needs a test):

- **The only write path** is the service-token ingest endpoint. No user-facing endpoint, and no code reachable from one, ever writes it.
- It's stored **only if at least one club is currently mapped to that (league, season)**. Otherwise the standings part is rejected (see 4). So no caller can fill the collection with arbitrary leagues.
- **Reads take league and season only from the caller's own `Club.footballOrgIl`**, never from a request parameter, body or path. There is no endpoint that takes a league id.
- Nothing club-owned is ever stored in it.

Document this as the fourth deliberate exception in the `@NotClubScoped` Javadoc and on the new entity, in the same style as the existing ones.

Store dates and times as **Israel local values with no conversion** (see "Verify against reality": prove the stored form with a raw-document test, because Spring Data may convert `LocalDate` / `LocalTime` through the JVM's default timezone).

### 2. Resolving which club(s) a scrape belongs to: never from the payload

The ingest request carries `teamId`, `leagueId`, `seasonId`, and **no `clubId`** (CLAUDE.md rule 4: a clubId never comes from a request). The backend finds the clubs **currently** mapped to exactly that `(teamId, leagueId, seasonId)` and writes the fixtures snapshot for each, inside `ClubContext.callAs(clubId, ...)` so the normal club-scoped repository does the write. Normally that's one club. Zero clubs → the fixtures part is rejected (`NOT_MAPPED`, see 4; for example the owner changed the mapping between the scrape and its result).

`FootballOrgIlMapping` is package-private in `auth`, and `match` / `scrapingintegration` must not reach into `auth` internals. Add a **small public read API in `auth`** (e.g. a `ClubFootballMappings` service with a public read-only view record) that offers only what's needed:

- the current mapping of a club, by clubId (for the read endpoints; the clubId comes from `ClubContext`);
- the ids of clubs mapped to an exact `(teamId, leagueId, seasonId)`;
- whether any club is mapped to a `(leagueId, seasonId)`.

Its queries on `ClubRepository` are lookups across all clubs. `Club` is `@NotClubScoped`, so ArchUnit doesn't check them. Give each a Javadoc saying why it's safe (it returns clubIds or a boolean to a server-side caller that has no club context; nothing tenant-owned is returned). The mapping must still **never** appear in any API response (spec section 09).

### 3. Team names → ids: resolved by the backend

The scraper sends fixture team **names only**. On ingest, the backend resolves each name to a `teamId` by exact match against the standings of the same `(leagueId, seasonId)`: those in the same payload if it carries standings, otherwise the stored ones. A name that isn't found keeps a `null` id. Never guess, and never fail the scrape for it.

Our own team:

- Its name is the `teamName` of the standings row whose `teamId` equals the configured one.
- If no standings are available yet (none in the payload, none stored), fall back to the one name that appears in **every** game. If that isn't exactly one name, reject the fixtures part (`OWN_TEAM_UNKNOWN`).
- **Every game must have our team on one side.** A game that doesn't → reject the fixtures part (`INVALID`), because the page is our team's own list.
- If standings are available but **the configured `teamId` isn't in them**, store the data anyway, report the warning `TEAM_NOT_IN_STANDINGS` in the ingest response, and log it at WARN (ids only). The read endpoints show it too (see 6). This is how a wrong mapping becomes visible (KAN-65 couldn't check it).

### 4. The ingest endpoint and the contract

`POST /internal/scrape-results`, JSON. One request per scrape. The request DTOs **are** the scraper ↔ backend contract: name them clearly, give them Javadoc, and document them in a new `docs/scraper-backend-contract.md` (see Docs).

Request shape (field names may be adjusted for consistency, but keep the structure):

```
{
  "runId":    string | null,        // opaque, ≤ 100 chars; reserved for KAN-68, ignored and not stored here
  "teamId":   int ≥ 1, "leagueId": int ≥ 1, "seasonId": int ≥ 1,
  "fixtures":  null | { "status": "OK",     "fetchedAt": instant, "games": [ ... ] }
                    | { "status": "FAILED", "reason": FailureReason, "detail": string|null (≤ 500) },
  "standings": null | { "status": "OK",     "fetchedAt": instant, "round": int ≥ 1, "rows": [ ... ] }
                    | { "status": "FAILED", "reason": FailureReason, "detail": string|null (≤ 500) }
}
FailureReason: SOURCE_BLOCKED | TIMEOUT | SOURCE_ERROR | PARSE_ERROR
```

`null` / absent part = not attempted (e.g. KAN-68's per-league lock skips the standings). At least one part must be present.

A game: `gameId` (int ≥ 1 or `null`), `date` (`yyyy-MM-dd`), `kickoffTime` (`HH:mm` or `null`), `homeTeamName`, `awayTeamName` (trimmed, 1–100 chars, different from each other), `venue` (`null` or 1–200 chars), `homeScore` / `awayScore` (0–99, both or neither). 1–100 games.

A standings row: `position` (1..row count), `teamId` (≥ 1, unique in the table), `teamName` (1–100, unique in the table), `played`, `won`, `drawn`, `lost` (0–99, and `won + drawn + lost == played`), `goalsFor`, `goalsAgainst` (0–999), `points` (−99..999). 2–40 rows.

Validation:

- **Validate the whole request before writing anything** (spec section 10: entity constraints aren't re-checked on save).
- A structurally invalid request (bad types, missing required fields, out-of-range values, a failure detail too long, unknown `status`) → `400` in the standard error shape, nothing written. For this endpoint, **unknown JSON properties are rejected too** (`400`), because it's a contract and a typo in the scraper should fail loudly. Do this for these DTOs only, without changing global JSON behaviour.
- `fetchedAt` more than 5 minutes in the future → `400`.
- **Duplicate game key** (same home + away twice) → reject the **fixtures part** with `DUPLICATE_GAME`. The page is league-only today, so this shouldn't happen. But the league table carries a `table-w-playoff` class, so a playoff stage could repeat a pairing. If it does, we want a visible rejection, not a silent overwrite. Say this in a code comment.

Per-part processing. The two parts are independent: one rejected doesn't block the other. Standings are processed **first**, so the fixtures can be resolved against them. Each part's outcome is one of:

- `STORED`
- `REJECTED` with a reason: `NOT_MAPPED`, `DUPLICATE_GAME`, `OWN_TEAM_UNKNOWN`, `INVALID`
- `STALE`: its `fetchedAt` is not newer than the stored snapshot's, so nothing is written. This covers an old scrape arriving after a newer one, and a replayed request.
- `FAILURE_RECORDED`: the scraper reported `FAILED`. Log it at INFO with the reason and ids only, **never** `detail` at more than DEBUG. Write nothing. Updating a scrape status is KAN-68.
- `NOT_SENT`

Response:

- `200` with a small result DTO: each part's outcome (+ reason), the number of clubs updated, and `warnings` (e.g. `TEAM_NOT_IN_STANDINGS`, `UNRESOLVED_TEAM_NAMES` with a count). Never the clubIds.
- Only a `400` (invalid request), `401` (missing / bad token), `413` (too large) or `5xx` is not a `200`.
- Return `200` even when every part is rejected. The result tells the scraper why, and these are data outcomes, not client errors.

Body size: cap the request body for this endpoint at **256 KB** → `413` in the standard error shape. The real payload is about 15 KB. How you enforce it is your call (see "Verify against reality"), but it must hold for a chunked body with no `Content-Length` too, and it must not change any other endpoint's limits.

### 5. Concurrency

Two ingests for the same snapshot at once are unlikely (KAN-68 adds locks), but must be safe. Use `@Version` on both snapshot documents and stay in the repository layer: no hand-built `MongoTemplate` write for fixtures (spec section 03: a hand-built query bypasses the isolation layer).

- Load → compare `fetchedAt` → save.
- On an optimistic-lock failure, or a duplicate-key error on the first insert, re-read once and re-apply the `fetchedAt` comparison. Then either store or answer `STALE`.
- Never overwrite a newer snapshot with an older one.

### 6. Read endpoints for the frontend

Both `@PreAuthorize("hasAuthority('VIEW_ONLY')")`, under a new `match` controller. League and season come only from the caller's club mapping, via the public `auth` API from 2. Fixtures are found by `(clubId from ClubContext, current teamId, current seasonId)`.

**`GET /matches/fixtures`:**

```
{ "state": "NOT_MAPPED" | "NO_DATA" | "OK",
  "lastUpdatedAt": instant | null,         // the snapshot's fetchedAt
  "teamName": string | null,
  "mappingProblem": "TEAM_NOT_IN_STANDINGS" | null,
  "games": [ { "date", "kickoffTime" (null = not set yet), "ownSide": "HOME" | "AWAY",
               "opponentName", "venue", "ownScore", "opponentScore",
               "result": "WIN" | "DRAW" | "LOSS" | null } ] }
```

Games are sorted by date, then kickoff time, with `null` times last within a day. Don't return `gameId` or any team id.

**`GET /matches/league-table`:**

```
{ "state", "lastUpdatedAt", "round", "mappingProblem",
  "rows": [ { "position", "teamName", "played", "won", "drawn", "lost",
              "goalsFor", "goalsAgainst", "goalDifference", "points", "ownTeam": boolean } ] }
```

Rows are ordered by position. **No `teamId` anywhere in either response**: our row is marked by `ownTeam`, so the configured mapping is never exposed (spec section 09).

Rules for both:

- A club with no mapping → `200` with `state: NOT_MAPPED` and empty data. Not an error: it's a valid, expected state.
- Mapped but nothing stored for the current mapping → `NO_DATA`.
- `mappingProblem` is computed when the response is built: standings exist for the current (league, season) but don't contain the configured `teamId`.
- **Season change:** snapshots of an earlier season (or an earlier team / league) stay in the database but are never shown, because reads use the current mapping only. No cleanup job in this ticket.

### 7. Service-token authentication for `/internal/**`

Today every handler must be `@PreAuthorize` or `@PublicEndpoint` (`EndpointAuthorizationRules`). Neither fits a service caller: `@PublicEndpoint` means "reachable without a token", and `@PreAuthorize` today only sees authorities from a **user** JWT. Do this:

- A **second `SecurityFilterChain`** with `securityMatcher("/internal/**")` and a higher precedence (`@Order`) than the existing one. It's stateless, with CSRF / form login / basic / logout / request cache off, the same JSON 401 / 403 bodies as today, and **no `JwtAuthenticationFilter`**.
- Its own filter checks `Authorization: Bearer <token>` against the configured service token with a **constant-time comparison** (e.g. `MessageDigest.isEqual` on SHA-256 digests of both, so the length isn't leaked either). On a match it grants **one** authority, e.g. `SCRAPER_SERVICE`, and sets **no `ClubContext`**.
- **The filter must not be a `@Bean` / `@Component` of type `Filter`**: Spring Boot would also register it as a servlet filter on every path. Instantiate it inside the chain, as `SecurityConfig` does with `JwtAuthenticationFilter`, or verify and prove otherwise.
- The authority is **not** a `PermissionLevel` and **not** in the role hierarchy, so no user level implies it, and it implies no user level.
- The ingest handler: `@PreAuthorize("hasAuthority('SCRAPER_SERVICE')")`. The ArchUnit rule stays unchanged and passes.
- In the existing chain, add `requestMatchers("/internal/**").denyAll()` as defence in depth (a mis-ordered chain must fail closed).
- Add a rule test (ArchUnit or reflection, your call): every handler under `/internal/**` requires `SCRAPER_SERVICE`, and every handler requiring `SCRAPER_SERVICE` is under `/internal/**`.
- Where the chain and filter live (`auth` next to `SecurityConfig`, or `scrapingintegration`) is your call. Justify it in the summary.

Configuration:

- `squadpulse.scraper.service-token: ${SCRAPER_SERVICE_TOKEN:}`, with a validated properties class in the existing style (`SecurityProperties` / `TokenProperties`). Name the env var exactly `SCRAPER_SERVICE_TOKEN`: KAN-67 / KAN-68 will reuse it.
- **Empty / unset →** the app starts, logs one WARN at startup ("internal endpoints disabled"), and **every** `/internal/**` request is answered `401`. This is fail closed, so dev, CI and e2e keep working before the scraper exists.
- **Set but shorter than 32 characters →** startup fails, like `JWT_SECRET`.
- The token is **never** logged, echoed or put in an error. No request body on this endpoint is logged at INFO or above. The existing 5xx logging already omits headers and body; keep it that way.
- If a `.env.example` (or similar) exists, add the variable with an empty value and a one-line comment. Never a real value.

### 8. Modules

- Ingest controller, request / result DTOs and the token chain → `scrapingintegration`, per its `package-info`: it talks to the scraper and hands results over. It **owns no data**.
- Entities, repositories, the ingest-applying service, the read service and the read controller → `match`.
- `auth` only gains the small public mapping API from 2.
- Update both `package-info` Javadocs if what they say stops being true (`match` says "Every document is tenant data", which is now false for the standings).

## Out of scope

The refresh button / trigger endpoint, locks, scrape status and `runId` handling (KAN-68); the scraper itself (KAN-67); frontend (KAN-69); manual match entry, lineups, player stats (KAN-70); cleanup of old-season snapshots; any reverse-proxy config.

## Verify against reality, not memory

Check these against what's actually installed (`backend/pom.xml`, the resolved versions, e.g. `./mvnw dependency:list`, or the jars in `~/.m2`), not general knowledge. Say in the summary how you checked each:

- Spring Boot 4.1.1, Spring Security and Spring Data MongoDB versions as resolved.
- **Two `SecurityFilterChain` beans** in this Spring Security version: how ordering and `securityMatcher` behave. Prove with tests (see Tests) that an `/internal/**` request never runs `JwtAuthenticationFilter` and a normal request never runs the service-token filter.
- That the role hierarchy bean doesn't make any user level imply `SCRAPER_SERVICE` (test it).
- **How `LocalDate` / `LocalTime` are stored** by this Spring Data MongoDB version. If they're converted through the JVM default timezone (e.g. to a BSON date at midnight), the stored date could shift by a day depending on the server's zone. Choose a representation that can't shift (e.g. ISO strings, or explicit converters), and prove it with a raw-document test **run with a non-Israel default timezone** (e.g. set `TimeZone.setDefault` to `America/New_York` in that test and restore it).
- How to reject unknown JSON properties on these DTOs only, with the Jackson version Boot 4.1.1 actually uses (Jackson 3 changed packages and defaults; check, don't assume).
- How to enforce the 256 KB body limit on this endpoint only, given this Tomcat / Spring version, including a chunked body.
- That `@Version` + a unique index behave as you rely on for the first insert race (duplicate-key exception type as actually thrown).

## Read first

`CLAUDE.md` (standing rules, especially 4, 5 and 7), then only:

- `backend/src/main/java/com/squadpulse/auth/SecurityConfig.java`, `JwtAuthenticationFilter.java`, `SecurityProperties.java`, `TokenProperties.java`, `Club.java`, `ClubRepository.java`, `FootballOrgIlMapping.java`, `PermissionLevel.java`
- `backend/src/main/java/com/squadpulse/common/ClubContext.java`, `ClubScopedEntity.java`, `ClubScopedRepositoryImpl.java` (Javadoc only), `NotClubScoped.java`, `GloballyScoped.java`, `PublicEndpoint.java`, `GlobalExceptionHandler.java`, `ErrorLogging.java`
- `backend/src/main/java/com/squadpulse/match/package-info.java`, `scrapingintegration/package-info.java`
- One existing controller + service + integration test as a style model: `squad/SquadSummaryController.java` and its tests
- `backend/src/test/java/com/squadpulse/common/EndpointAuthorizationRules.java`, `ClubScopedRepositoryRules.java`, `auth/PublicEndpointsConsistencyTest.java`, `auth/TestAccessTokens.java`, `auth/SecurityConfigTest.java`
- `backend/src/main/resources/application.yml`
- `scraper/docs/KAN-64-findings.md` sections 4, 5, 6, "Proposed parsed output", and "Open questions and risks"
- `docs/spec.md` sections 02, 03, 07, 10: to list what goes out of date, **not** to edit

## Tests

Follow the existing style (WebMvc tests for controllers, Testcontainers integration tests for services, repositories and the full chain). At least the following.

**Security (the most important group):**

- No token, a wrong token, a token of the right length but one character off, and a **valid user access token of an `ADMIN`** → each `401` on `/internal/scrape-results`, and nothing written.
- The service token on `GET /matches/fixtures`, `GET /matches/league-table` and a few existing endpoints (e.g. `/auth/users/me`, `/squad/players`) → `401`.
- Token unset → `/internal/**` always `401` (including with any bearer value), and the startup WARN is logged.
- Token shorter than 32 → startup fails.
- The token never appears in any log line captured during the security tests (use the existing `CapturedLogs`).
- `JwtAuthenticationFilter` does not run for `/internal/**`, and the service-token filter does not run for other paths.
- The new rule test from 7, plus a rule-test fixture proving it catches a violation (same pattern as `EndpointAuthorizationRuleTest`).

**Isolation:**

- Two clubs, A and B, mapped to different teams in **the same league and season**:
  - an ingest for A's team writes only A's fixtures;
  - B's `GET /matches/fixtures` never sees A's games;
  - both see the same league table, each with its own `ownTeam` row.
- A club mapped to a different league never sees that league's table.
- Standings for a (league, season) no club is mapped to → standings part `REJECTED NOT_MAPPED`, nothing stored.
- Fixtures for a (team, league, season) no club is mapped to → `REJECTED NOT_MAPPED`.
- No read endpoint accepts a league / season / team parameter. A request adding `?leagueId=...` changes nothing.
- Neither read response contains a `teamId`, `leagueId`, `seasonId` or `gameId` key (assert on the raw JSON).

**Ingest behaviour:**

- A full valid payload (build it from the KAN-64 sample data shape) → both parts `STORED`. Check the raw documents: shape, home/away scores as sent, `null` time for an unscheduled game, `null` `gameId` for `-1`.
- Name resolution: from the payload's standings; from the stored standings when the payload has none; an unknown opponent name → `null` id and a warning, not a failure.
- Own-team fallback without any standings; `OWN_TEAM_UNKNOWN` when ambiguous; a game without our team → `INVALID`.
- `TEAM_NOT_IN_STANDINGS`: data stored, warning returned, WARN logged, and `mappingProblem` set on both read endpoints.
- `DUPLICATE_GAME` → fixtures part rejected, standings part still stored, the old fixtures snapshot untouched.
- A `FAILED` part → `FAILURE_RECORDED`, the existing snapshot untouched, `detail` not logged at INFO.
- `STALE`: an older `fetchedAt` after a newer one, and the same request replayed → nothing changes.
- Validation `400`s (one test per rule is not needed; cover each category):
  - type errors and missing fields;
  - each numeric range;
  - `won + drawn + lost != played`;
  - one score without the other;
  - an unknown property;
  - `fetchedAt` in the future;
  - both parts absent;
  - a `FAILED` part without a reason.

  Each writes nothing.
- A body over 256 KB → `413`, also when chunked.
- Concurrency: two ingests of the same snapshot in parallel, with different `fetchedAt` → the newer one wins, whatever the order.

**Reads:**

- `NOT_MAPPED`, `NO_DATA` and `OK` for each endpoint.
- Sorting (`null` time last within a day).
- `result` / `ownScore` / `ownSide` correct when we're home and when we're away.
- `goalDifference` derived.
- **Season change:** store season 28 data, change the club's mapping to season 29 (through the KAN-65 repository method) → both reads return `NO_DATA`. The old document still exists.
- `VIEW_ONLY` can read. No token → `401`.

**Dates:** the raw-document test with a non-Israel default timezone from "Verify against reality".

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. let the service-token filter accept any non-empty token;
2. take the league id for the table from a request parameter;
3. remove the "some club is mapped" check for standings;
4. remove the `fetchedAt` staleness check;
5. register the service-token filter as a `@Component`.

Report each and whether it was caught.

Run `./mvnw spotless:apply` then `./mvnw verify` from `backend/` with `JAVA_HOME` set to Temurin 21 (Spotless crashes on JDK 25). Report the test count before (1075 at the KAN-65 merge) and after.

## Docs

- **`docs/scraper-backend-contract.md`** (new). It is the contract KAN-67 builds against. Include:
  - the endpoint, auth header and env var;
  - every field with type, range and meaning;
  - the home/away and for/against conventions (the scraper fixes the site's order);
  - Israel local date and time;
  - `null` meanings;
  - every outcome, reason and warning, and every status code;
  - the 256 KB limit;
  - one full example request and response;
  - what the backend does with the data (snapshot replace, staleness, name resolution).

  Keep it in sync with the DTOs. Say in the DTO Javadoc that the doc is the public description.
- **`README.md`** (CLAUDE.md rule 7, same commit as the code): the two read endpoints in a new "Matches API" section in the existing style; the `SCRAPER_SERVICE_TOKEN` env var (optional, ≥ 32 chars, what happens when unset); a pointer to the contract doc.
- **`CLAUDE.md`:** rule 4 says `@NotClubScoped` is "today just `Club`". Update it to name the shared standings and its safety rules in one or two sentences. Update the status line with the new endpoints, and the architecture section if it describes modules. Nothing else.
- **Javadoc:** `NotClubScoped` (the new use and why it's safe), the standings entity, the fixtures entity, the new `auth` public API, the token filter and chain, the ingest service (processing order, outcomes), and both `package-info`s.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR. In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words so I can find it) and what's wrong. I expect at least:
  - section 02 (modules / `/internal` endpoint, the scraper ↔ backend flow);
  - section 03 (the shared standings as a new deliberate exception, next to the existing bypasses);
  - section 07 (what's stored, snapshots, name resolution, "last updated");
  - section 09 ("the API never returns it": still true, confirm);
  - section 10 (service-token auth, the internal chain, the new env var, the 256 KB limit, that `/internal` must be blocked at the production reverse proxy in Phase 6).

  Confirm or correct each, and add any I missed.

## Commits

Small, focused commits, for example:

- `feat(match): store league fixtures and standings snapshots (KAN-66)`
- `feat(scrapingintegration): service-token authenticated ingest endpoint for scrape results (KAN-66)`
- `feat(match): read endpoints for the club's fixtures and league table (KAN-66)`
- `docs: scraper-backend contract, README and CLAUDE.md (KAN-66)` (or with the code, per rule 7)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions verified and **how** you checked each, with the findings for every item in "Verify against reality" (evidence, not assumptions).
3. The final design:
   - both entities, with one raw stored document of each pasted from a test;
   - the public `auth` API;
   - where the chain and filter live, and why;
   - how the body limit and unknown-property rejection are done.
4. The final contract: request and response examples as in the contract doc, and any deviation from this prompt's shape.
5. Tests: the new tests (name + one line each), the mutation checks and their results, the test count before and after, and the `verify` result (local).
6. Docs changed (contract doc, README, CLAUDE.md, Javadoc), one line each.
7. Spec: every out-of-date place (section + quoted words + what's wrong).
8. Deviations from this prompt, and why.
9. Open ends and risks for KAN-67 / KAN-68 / KAN-69 and Phase 6.
10. Print in full, at branch HEAD:
    - the token filter;
    - the internal `SecurityFilterChain` config and the changed `SecurityConfig`;
    - the ingest service;
    - the public `auth` mapping API and the changed `ClubRepository`;
    - the standings entity and repository.
