# KAN-68: Backend: refresh trigger endpoint (EDIT_FULL+), per-team / per-league lock, scrape status and last-updated

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `9b40ee8`, the KAN-67 merge, PR #50). If it doesn't, stop and report. Then create the branch `feature/KAN-68-refresh-trigger-locks-status`.

## Context

Jira KAN-68, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-66 (done: storage, `POST /internal/scrape-results`, `GET /matches/fixtures` / `league-table`) and KAN-67 (done: the Node scraper service with `POST /scrape`). Blocks KAN-69 (the frontend: dashboard cards, matches screen, refresh button with status).

Today nothing triggers the scraper. This ticket is the backend side of the "refresh data" button: decide whether a scrape may start, start it, and report its status. The Redis locks built here are **the primary protection of football.org.il** against being asked too often (spec §07: the site sends no caching headers, so these locks are what limits it). The scraper's own queue dedupe is only a second line of defence, and it dedupes by `(teamId, leagueId, seasonId)`, **not** by league: two clubs in the same league would each get the league table scraped unless the per-league lock here prevents it.

Facts you must build against (from `docs/scraper-backend-contract.md`, "Trigger: backend → scraper", and the scraper's code, `scraper/src/server.js`):

- `POST /scrape`, `Authorization: Bearer <SCRAPER_SERVICE_TOKEN>` (the same token as the ingest), body `{runId, teamId, leagueId, seasonId, fixtures, standings}`, all six required, `runId` `null` or 1–100 chars with no control characters, at least one part `true`, unknown properties → `400`, body ≤ 4 KB.
- `202 {"status":"QUEUED","runId":<ours>}` or `202 {"status":"ALREADY_QUEUED","runId":<the existing run's runId, may be null>}` (nothing added or changed, not even the parts). `503` = queue full or shutting down. `400` / `401` / `413` / `415` = our bug or misconfiguration.
- The scraper answers at once; the result arrives later as `POST /internal/scrape-results` with the same `runId`. One scrape at a time, FIFO of ≤ 5 waiting, each scrape ≤ 90 s + posting with one retry. **Worst case, a queued run finishes ~10 minutes after it was accepted.** A scraper restart drops queued/running runs with **no result posted** (so the backend must time a run out).
- The scraper retries its ingest POST once on `409` / `5xx` / network error, so the same `runId` can be ingested twice.
- `ScrapeResultController` today ignores `runId` and only logs FAILED parts ("recording a scrape status is KAN-68").

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Endpoints

Both under `/matches` (already in the frontend dev proxy's `BACKEND_PREFIXES`, never under `/internal`):

- `POST /matches/refresh`: `@PreAuthorize("hasAuthority('EDIT_FULL')")`. No body. Starts a refresh for **the caller's club** (club from `ClubContext` only; mapping via `auth.ClubFootballMappings.currentClubMapping()` — never a club, team, league or season from the request).
- `GET /matches/refresh`: `@PreAuthorize("hasAuthority('VIEW_ONLY')")`. The caller's club's refresh status.

Put them in `scrapingintegration` (it "talks to the scraper worker", CLAUDE.md architecture), in a controller of their own. Neither is an "internal" endpoint, so `InternalEndpointRules` must still pass unchanged.

### 2. One response shape, outcomes not errors

Both endpoints return the same record (name your call, e.g. `RefreshStatusResponse`):

```
{
  "outcome":       "STARTED" | "ALREADY_RUNNING" | "COOLDOWN" | "NOT_MAPPED" | "SCRAPER_UNAVAILABLE" | null,
  "state":         "NOT_MAPPED" | "IDLE" | "RUNNING" | "SUCCEEDED" | "FAILED",
  "failureReason": null | "SOURCE_BLOCKED" | "SOURCE_TIMEOUT" | "SOURCE_ERROR" | "PARSE_ERROR" | "REJECTED" | "NO_RESULT",
  "startedAt":     instant | null,
  "finishedAt":    instant | null,
  "nextRefreshAt": instant | null
}
```

- `outcome` is set only by `POST` (always `null` on `GET`). HTTP status: `202` for `STARTED`, `200` for every other outcome. These are states the user should see, not client errors; don't add error codes to `429` / `503` (today only `409` carries a `code`).
- `state` / `failureReason` / `startedAt` / `finishedAt` describe **the club's latest run** (after a `POST`, the state after it). `finishedAt` = when the backend recorded the run's end (ingest received, or the timeout applied). `failureReason` only with `FAILED`.
- `nextRefreshAt`: when a `POST` may next start a scrape for this club, or `null` if it may now. From the team lock's remaining TTL.
- `SCRAPER_UNAVAILABLE` does **not** overwrite the stored status: the last run's state (e.g. a success with its time) stays visible.
- `NOT_MAPPED`: the club has no `footballOrgIl` mapping → `outcome` `NOT_MAPPED` on `POST` (nothing else happens, no lock taken), `state` `NOT_MAPPED` on both.
- Never any football.org.il id, `runId`, scraper URL, Redis key or the scraper's `detail` text in the response.
- Instants in the JSON exactly as the existing responses serialize `Instant` (check `lastUpdatedAt` in `match` and follow it).

### 3. Locks (Redis), and when the cooldown starts

Durations are configuration properties with these defaults (so tests can shorten them): cooldown **10 minutes**, run timeout **10 minutes** (see 5). Validate at startup that both are positive and that the run timeout is **≤ the cooldown** (otherwise a second scrape could start while the first is still within its timeout) — refuse to start otherwise.

- **Team lock**, key per `(teamId, leagueId, seasonId)` (the same key the scraper dedupes by): `SET NX PX <cooldown>` with our `runId` as the value. Taken first.
  - Held by a run of this club that is still `RUNNING` → `ALREADY_RUNNING` (`200`, the current status). Otherwise → `COOLDOWN` with `nextRefreshAt` from the key's TTL.
- **League lock**, key per `(leagueId, seasonId)`: `SET NX PX <cooldown>`, value our `runId`. If it's held, send `standings:false` and scrape the fixtures only — **silently**: the response must not differ in any way that tells club B that another club in its league refreshed (no extra field, same `outcome`).
- **The cooldown starts when the scraper accepts the trigger** (`202`). A scrape that later fails — even a block — keeps the cooldown (deliberate: the site was asked).
- **A trigger that doesn't reach the scraper or is refused by it** (unset URL or token, connection refused, timeout, `503`, `400` / `401` / `413` / `415`, a malformed `202`) **releases both locks**, but only if they still hold our `runId` (compare-and-delete in one Lua script, like `auth.RefreshTokenService`'s scripts). The site was never asked, so the user may try again at once.
- **Double click / two admins at once:** exactly one `POST` may call the scraper; the others get `ALREADY_RUNNING` (or `COOLDOWN`). The team lock's `SET NX` is the guarantee — no check-then-act in Java.
- Lock keys contain only football.org.il ids (public, not club-owned). Keys holding club data (status, run records) contain the clubId from `ClubContext` or a stored record, never from a request. Use one key prefix for all of this (e.g. `scrape:`), distinct from the auth prefixes.
- **Redis unavailable:** never call the scraper without the locks. Let the error become the normal 5xx path (no special handling), and test that the scraper isn't called.

### 4. The trigger call to the scraper

- New env var **`SCRAPER_URL`** (the scraper's base URL, e.g. `http://127.0.0.1:8090`), bound as a property next to `serviceToken` in `scrapingintegration.ScraperProperties` (keep its `toString` token-safe). **No default.** Unset → `POST` answers `SCRAPER_UNAVAILABLE` without touching any lock, plus one startup WARN (like the unset token). Set but invalid (not `http`/`https`, no host, has user-info, a query or a fragment) → refuse to start. URL set but token unset → also `SCRAPER_UNAVAILABLE` (we can't authenticate to it).
- `POST <SCRAPER_URL>/scrape` with the bearer token, `Content-Type: application/json`, body per the contract. `runId`: a random UUID string generated by the backend.
- **Fast failure:** connect timeout ~2 s, read timeout ~5 s, no retry, no redirect following. A scraper that is down or hangs yields `SCRAPER_UNAVAILABLE` within those bounds; nothing else in the app waits on it (it's only on this request's thread).
- Map the answer: `202 QUEUED` with our runId → `STARTED`. `202 ALREADY_QUEUED` → `STARTED` too if the returned runId has a run record of this club (adopt it as the current run; keep the locks); otherwise keep our own run record `RUNNING` (it will time out to `NO_RESULT` if no result with our runId arrives — this can only happen after Redis lost our locks or someone triggered the scraper by hand; log it at WARN). Anything else (including an unparseable `202`) → `SCRAPER_UNAVAILABLE` and release the locks; `400` / `401` / `413` / `415` additionally log at ERROR (it's our bug or a token mismatch). Read at most a small response body; never log it, the request body or the token.
- **Verify** the HTTP client against what's installed (see "Verify against reality"). Don't add a dependency for this.

### 5. Run records and status

- **Run record** per `runId` (Redis, TTL e.g. 7 days): clubId (from `ClubContext`), the team / league / season ids sent, which parts were requested, `startedAt`, state, `failureReason`, `finishedAt`, trigger source.
- **Current-run pointer** per club (Redis, TTL e.g. 30 days): the club's latest run's id. No pointer, or its record expired → `IDLE`.
- **Ordering — a result must never be lost:** write the run record **before** calling the scraper (state e.g. `PENDING`). On `202`, set it to `RUNNING` **only if it's still `PENDING`** (atomically — a result can, in theory, arrive before this line runs) and point the club at it. On a failed trigger, delete the record; the pointer and the previous status stay untouched.
- **Timeout:** a run still `RUNNING` (or `PENDING`) after `startedAt + runTimeout` is `FAILED NO_RESULT`. Evaluate it lazily when the status is read (`GET`, or the status part of a `POST`) and persist it with a compare (only if still running). No scheduler.
- **Ingest → status** (in `ScrapeResultController`, after `ScrapeIngestService` has returned normally, from the outcomes it computed — so a `400` / `409` / `5xx` never changes the status; the scraper's retry will):
  - `runId` `null`, unknown, or its record's team / league / season don't equal the request's → no status change; WARN for a mismatch (ids and runId only, never the body).
  - Otherwise, for each part **the run requested**: outcome `STORED` or `STALE` → fine; `FAILURE_RECORDED` → that part failed with the scraper's reason (`SOURCE_BLOCKED`, `TIMEOUT` → `SOURCE_TIMEOUT`, `SOURCE_ERROR`, `PARSE_ERROR`); `REJECTED` → `REJECTED`; `NOT_SENT` for a requested part → `NO_RESULT`. All fine → `SUCCEEDED`. Any failed → `FAILED`, reason of the first failed part in the order fixtures, standings. A part the run didn't request (standings skipped by the league lock) is ignored. `TEAM_NOT_IN_STANDINGS` doesn't fail the run (it's shown on the read endpoints as `mappingProblem`).
  - Idempotent: the same result ingested twice gives the same state. A result arriving after the timeout marked the run `NO_RESULT` **replaces** that (the data was stored anyway; the status must tell the truth). A result for a run that is no longer the club's current run updates that run's record only.
  - Recording the status must never fail the ingest response: the data is already written. A Redis error there → WARN (ids + runId), and the `200` is still returned.
- **Mapping changed** since the run (the club's current mapping's ids differ from the run record's) → `GET` shows `IDLE`, and the next `POST` uses the new mapping's locks.
- The existing FAILED-part INFO log in `ScrapeResultController` stays.

### 6. Cron-ready, without building cron

All the logic lives in a service (e.g. `scrapingintegration.ScrapeTriggerService`), not the controller, that reads the club only from `ClubContext` and takes a trigger source (`USER` / `SCHEDULED`, used only in log lines and the run record). It must not depend on the HTTP request, Spring Security's context or the user, so a scheduled job can later call it inside `ClubContext.callAs(clubId, …)` unchanged. Prove it with a test that calls it inside `callAs` with no security context. Listing the clubs to refresh on a schedule is **not** part of this ticket (it would need a new cross-club lookup, which must then be confined like `CrossClubLookupConfinementTest` does today).

### 7. Logging

One INFO line per trigger decision: source, outcome, runId, team / league / season ids, and whether standings were sent. One INFO line per status change from ingest or timeout (runId, new state, reason). No user email, no token, no URL credentials, no request or response bodies.

## Out of scope

The frontend (KAN-69); a scheduled / cron refresh (only stay compatible, see 6); changing the scraper or the ingest contract's fields; per-user rate limiting beyond the team lock; any reverse-proxy or container config (Phase 6).

## Verify against reality, not memory

Check these against what's actually installed (`backend/pom.xml`, resolved versions via `./mvnw dependency:list` or the jars in `~/.m2`), not general knowledge. Say in the summary how you checked each:

- Spring Boot 4.1.1, Spring Framework and Spring Data Redis versions as resolved.
- **The HTTP client** for the trigger: which of `RestClient` / its request factories is available with the current dependencies (no new dependency), how connect and read timeouts are set on it in this version, and that it doesn't follow redirects as you configure it. Prove the timeouts with tests (below), not by reading docs.
- Whether Boot 4.1.1 auto-configures a `RestClient.Builder` and any global HTTP-client properties (`spring.http.client.*` or similar) that could silently change your timeouts; build the client so its timeouts are the ones you set.
- `StringRedisTemplate` / `RedisScript` usage as in `auth.RefreshTokenService` (same version): how `SET NX PX` and `PTTL` are expressed with this Spring Data Redis version.
- How `Instant` is serialized in the existing responses (Jackson 3 in Boot 4: check, don't assume).
- The real scraper's validation in `scraper/src/server.js` (the runId rules, `ALREADY_QUEUED` body) — build the client against the code, not only the doc.

## Read first

`CLAUDE.md` (standing rules, especially 4, 5 and 7), then only:

- `docs/scraper-backend-contract.md` (whole file) and `scraper/src/server.js` (the trigger's validation and responses)
- `backend/src/main/java/com/squadpulse/scrapingintegration/`: `ScraperProperties.java`, `ScrapeResultController.java`, `ScrapeResultResponse.java`, `InternalSecurityConfig.java` (Javadoc), `package-info.java`
- `backend/src/main/java/com/squadpulse/match/IngestResult.java`, `MatchController.java`, `MatchReadService.java` (how `NOT_MAPPED` and `lastUpdatedAt` are done), `package-info.java`
- `backend/src/main/java/com/squadpulse/auth/ClubFootballMappings.java`, `FootballOrgIlIds.java`, `RefreshTokenService.java` (the Redis / Lua style), `SecurityConfig.java` (only the role hierarchy and how `@PreAuthorize` is used)
- `backend/src/main/java/com/squadpulse/common/ClubContext.java`, `GlobalExceptionHandler.java` (skim)
- `backend/src/main/resources/application.yml`, `.env.example`
- Tests as a style model: `scrapingintegration/ScrapeResultApiIntegrationTest.java`, `ScrapePayloads.java`, `InternalEndpointRules.java`, `common/EndpointAuthorizationRules.java`, `common/CapturedLogs.java`, and one existing Redis integration test of `RefreshTokenService`
- `docs/spec.md` sections 04, 07, 10: to list what goes out of date, **not** to edit

## Tests

Follow the existing style (WebMvc tests for controllers, Testcontainers integration tests with real Redis and Mongo for the service and the full flow). For the scraper, use a **real local HTTP server** in the test (the JDK's `com.sun.net.httpserver.HttpServer` or whatever is already on the test classpath — no new dependency) that records each request and can answer `202 QUEUED`, `202 ALREADY_QUEUED`, `503`, `401`, garbage, or never answer. At least:

**Authorization and isolation**

- `POST`: no token → `401`; `VIEW_ONLY` and `EDIT_PARTIAL` → `403`; `EDIT_FULL` and `ADMIN` → allowed. `GET`: `VIEW_ONLY` allowed. The scraper service token on both → `401`.
- Two clubs A and B: B's `GET` never shows A's run; an ingest with A's `runId` changes only A's status; an ingest whose ids don't match its run record changes nothing (and logs WARN).
- Neither endpoint accepts a club / team / league / season parameter (adding `?teamId=` changes nothing). Neither response contains `runId`, any id, the URL or `detail` (assert on the raw JSON).

**Trigger and locks**

- Not mapped → `NOT_MAPPED`, no scraper call, no Redis key written.
- Happy path → `202 STARTED`; the scraper got exactly the contract body (assert every field: `runId` a UUID, the club's three ids, both parts `true`, bearer token) and the status is `RUNNING`.
- **Double click:** ~10 parallel `POST`s from the same club → exactly one scraper request; one `STARTED`, the rest `ALREADY_RUNNING`.
- Second `POST` after the run finished but within the cooldown → `COOLDOWN` with a correct `nextRefreshAt`; after the cooldown (short configured duration in the test) → `STARTED` again.
- **Shared league:** A (team 1) and B (team 2), same league and season. A starts → its trigger has `standings:true`. B starts within the cooldown → `STARTED`, its trigger has `standings:false`, and B's response is field-for-field the same shape and outcome it would be without A (no leak).
- Scraper unavailable — URL unset; token unset; connection refused (a closed port); **no answer** (server accepts and hangs: assert `SCRAPER_UNAVAILABLE` arrives within the read timeout + a margin); `503`; `401`; an unparseable `202` → each `SCRAPER_UNAVAILABLE`, both locks released (an immediate retry reaches the scraper), the previous status unchanged. `401` / `400` → ERROR logged; none of the cases logs the token (`CapturedLogs`).
- A failed trigger only releases locks it owns: simulate another runId in the key before release → the key survives.
- `ALREADY_QUEUED` with a runId of this club's run → adopted; with an unknown / `null` runId → our record stays `RUNNING`, WARN logged.
- Redis down (stop the container or point at a dead port in that test's context) → `POST` is a 5xx and the scraper got no request.
- Startup: run timeout > cooldown → refuses to start; invalid `SCRAPER_URL` → refuses to start; unset URL → one startup WARN.

**Status**

- Ingest with both parts `STORED` → `SUCCEEDED` with `finishedAt`. Both `STALE` → `SUCCEEDED`. Fixtures `FAILED SOURCE_BLOCKED` → `FAILED SOURCE_BLOCKED`. Standings `REJECTED` → `FAILED REJECTED`. A requested part `NOT_SENT` → `FAILED NO_RESULT`. A run that didn't request standings, result with standings absent → `SUCCEEDED`. Build the payloads with `ScrapePayloads`, through the real `/internal/scrape-results` endpoint with the service token.
- The same result ingested twice → same state. A `400` ingest → status unchanged (still `RUNNING`).
- Timeout (short configured duration): no result → `GET` shows `FAILED NO_RESULT`; a late result for that run → `SUCCEEDED`.
- A result arriving **before** the trigger code marks the run `RUNNING` (force the order, e.g. ingest from the fake scraper's handler before it answers `202`) → final state is the result's, not `RUNNING`.
- A Redis failure while recording the status from ingest → the ingest still answers `200` with its outcomes, WARN logged.
- Mapping changed after a run → `GET` `IDLE`.
- The service called inside `ClubContext.callAs` with no security context and source `SCHEDULED` works (decision 6).

**Rule tests:** existing `EndpointAuthorizationRules` and `InternalEndpointRules` pass with the new controller (no change to them expected; if one needs a change, stop and report why).

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. check-then-set the team lock in Java instead of `SET NX` (or drop `NX`);
2. take the cooldown on the trigger attempt even when the scraper is unreachable (don't release);
3. release the locks without the runId compare;
4. ignore the league lock (always `standings:true`);
5. let the ingest status hook take the club from anything but the run record;
6. let the status hook overwrite `SUCCEEDED` with `RUNNING` (remove the "only if `PENDING`" guard).

Report each and whether it was caught.

Run `./mvnw spotless:apply` then `./mvnw verify` from `backend/` with `JAVA_HOME` set to Temurin 21 (Spotless crashes on JDK 25). Report the test count before (1145 at the KAN-66 merge; KAN-67 didn't touch the backend) and after.

**Manual check (only if Gal's local stack is up; otherwise say so):** with the real scraper running and Sderot mapped (7617 / 62 / 28), one `POST /matches/refresh` as an `EDIT_FULL` user → `STARTED`, then `GET` until `SUCCEEDED`; a second `POST` → `COOLDOWN`. **One real scrape only** — never loop against the live site. Report the scraper's log lines for that run (request count, outcomes).

## Docs

- **`docs/scraper-backend-contract.md`:** the trigger section — how the backend fills `runId` (a UUID), when it sends `standings:false`, how it treats `ALREADY_QUEUED` and every error status; the ingest section — `runId` is no longer "reserved / ignored": what the backend does with it (status), and that a status-recording failure never changes the ingest response. Keep it consistent with `ScrapeResultRequest`'s Javadoc.
- **`README.md`** (CLAUDE.md rule 7, same commit as the code): the two endpoints in the "Matches API" section in the existing style (permissions, the response shape, every outcome and state); `SCRAPER_URL` (optional, no default, what happens unset / invalid) in the setup steps and `.env.example`; the scraper step now says the backend triggers it; the cooldown and timeout properties.
- **`CLAUDE.md`:** the status line (the new endpoints; replace "Nothing triggers it automatically yet (KAN-68)"); one short paragraph in the right place on the refresh flow's safety rules: locks are the primary protection of the site (`SET NX`, release only own runId and only when the site wasn't asked), status keys only from `ClubContext` / the run record, the league lock never leaks to another club, the trigger service is `ClubContext`-only so cron can reuse it. Nothing else.
- **Javadoc:** the trigger service (the order of operations and why), the status service, the controller, the changed `ScraperProperties` and `ScrapeResultController`, `scrapingintegration/package-info.java`.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words) and what's wrong. I expect at least: the status line (top) and §13 (KAN-68 done, KAN-69 next); §04 (the new endpoints' permission levels); §07 (the refresh flow: locks, cooldown, shared league lock, status and timeout, "last updated"); §10 (the backend → scraper call: `SCRAPER_URL`, token, timeouts, fail closed; Redis keys). Also confirm or reword these carried-over items so Gal can fold them in: §10 omits that a `SCRAPER_SERVICE_TOKEN` containing whitespace also fails startup (KAN-66); §07 "the table is reported as not available" means `FAILED PARSE_ERROR` (KAN-67). Add any I missed.

## Commits

Small, focused commits, for example:

- `feat(scrapingintegration): refresh trigger with per-team and per-league Redis locks (KAN-68)`
- `feat(scrapingintegration): scrape run status from ingest results and timeout (KAN-68)`
- `docs: contract, README and CLAUDE.md for the refresh flow (KAN-68)` (or with the code, per rule 7)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions verified and **how** you checked each, with the findings for every item in "Verify against reality" (evidence, not assumptions).
3. The final design: Redis key scheme (every key, its value, its TTL), the order of operations in a trigger, the Lua scripts, the HTTP client and its timeouts, the response record.
4. Raw examples: one `POST` response for each outcome and one `GET` for each state (from tests), and one recorded trigger body.
5. Tests: the new tests (name + one line each), the mutation checks and their results, the test count before and after, and the `verify` result (local). The measured duration of the "no answer" test.
6. Manual check result, or why it wasn't run.
7. Docs changed (contract doc, README, `.env.example`, CLAUDE.md, Javadoc), one line each.
8. Spec: every out-of-date place (section + quoted words + what's wrong).
9. Deviations from this prompt, and why.
10. Open ends and risks for KAN-69, cron, and Phase 6.
11. Print in full, at branch HEAD:
    - the trigger service;
    - the status service (incl. the ingest hook and the timeout);
    - the new controller;
    - the changed `ScrapeResultController` and `ScraperProperties`;
    - every Lua script.
