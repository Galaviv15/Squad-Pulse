# KAN-72: Backend: store scraped player season stats, player links (EDIT_FULL+) with name suggestions, refuse deleting a linked player

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `91efea7`, the KAN-70 merge, PR #53). If it doesn't, stop and report. Then create the branch `feature/KAN-72-player-stats-links`.

## Context

Jira KAN-72, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-70 (done: the player-statistics spike, **no production code**). Blocks KAN-73 (the scraper parses the table and sends it), KAN-74 (the frontend: statistics screen, linking, the delete error) and KAN-77 (create a squad player from a scraped one, later).

football.org.il publishes one table per team and season with every registered player's season totals, **all competitions together**: games, goals, yellow cards (league + State Cup), yellow cards (Toto), red cards, starts, came on as sub, subbed off, minutes. This ticket gives that table a home in the backend, extends the scraper ↔ backend contract with it, and lets a club link each scraped player to one of its own `Player` records. Nothing in this ticket scrapes anything, and nothing writes `Player`.

Read `scraper/docs/KAN-70-findings.md` for the facts the contract is shaped by. The ones that matter here:

- Today 34 players, 7 of them with 0 games / 0 minutes. **Every one is kept.**
- `player_id` (football.org.il's id, an integer) is the row's key, and it's stable across seasons (supported, not proven). The name is **surname first** in this table, first name first elsewhere on the site, with 1–3 given names (11 of 34 names have three words). Names today have no geresh, gershayim, dots or double spaces, but nothing guarantees that.
- The proposed payload in the findings is a proposal. The shape below is the decision.

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. The contract: a third part, `playerStats`

`POST /internal/scrape-results` gets an optional third part, `playerStats`, built exactly like `fixtures` / `standings` (`status` OK / FAILED, the same `FailureReason`s, the same consistency rule, `fetchedAt` not more than `CLOCK_SKEW` in the future):

```
"playerStats": {
  "status": "OK",
  "fetchedAt": "<instant>",
  "players": [
    { "playerId": 85286, "name": "...", "games": 10, "goals": 2,
      "yellowCardsLeagueAndCup": 1, "yellowCardsToto": 0, "redCards": 0,
      "starts": 8, "cameOnAsSub": 2, "subbedOff": 3, "minutes": 712 }
  ]
}
```

- `ScrapeResultRequest`'s "at least one part" check now counts all three parts.
- `players`: 1–80 entries, none null. Every field required. `playerId` `@Positive` and **unique** in the list; `name` `@NotBlank`, ≤ 100, `@NoControlCharacters`, trimmed like team names; every count `@Min(0)` with a generous `@Max` (≤ 200 for counts, ≤ 20000 for minutes).
- **One** cross-field check, nothing else: `games == starts + cameOnAsSub`. **Never** check minutes against games (added time, extra time). If you think another check is needed, don't add it: list it under open ends.
- A duplicate `playerId` or a failed check is a `400` for the whole request, like the standings table's consistency check today. Nothing is written on a `400`.
- Ids are football.org.il's own: the scraper sends `player_id` as `playerId`, the team / league / season ids as today.

### 2. Storage: a club-scoped snapshot per club, team and season

- New collection (name it e.g. `player_season_stats`), a `ClubScopedEntity`: `teamId`, `seasonId`, `fetchedAt`, `storedAt`, `version`, and the list of players with all the fields above plus **an opaque id of ours** (decision 4). Unique index on `(clubId, teamId, seasonId)`.
- Ordinary `clubId` isolation: **no** new `@NotClubScoped`, no new bypass. The ingest finds the clubs with the existing `mappings.clubIdsMappedTo(teamId, leagueId, seasonId)` (exactly as the fixtures do, the same rule: a club mapped to exactly these three ids) and writes each one inside `ClubContext.callAs`. `CrossClubLookupConfinementTest` must pass unchanged.
- Snapshot semantics exactly like the fixtures: the whole list replaced in one write, through the existing `storeIfNewer` (load, compare `fetchedAt`, save with `@Version`, re-read once on a lost race). Not newer → `STALE`, nothing written. No club mapped → `REJECTED NOT_MAPPED`. A player missing from a later scrape disappears from the snapshot (their stats go), but **their link stays** (decision 5).
- `ScrapeIngestService` is the only writer. Put the code in `match` (spec §07 "Matches, stats & scraping"). Add `playerStats` to `IngestResult` and to `ScrapeResultResponse` (a `PartOutcome`, same enum). `clubsUpdated` keeps its meaning (fixtures). Check `scraper/src/backendClient.js`: it reads `fixtures` / `standings` / `clubsUpdated` only, so an extra field is safe. Say so in the summary.
- Logs: ids and counts only. **No player name in any log line.**

### 3. Refresh (KAN-68): the run can ask for `playerStats`, but doesn't yet

- The run record gets a third requested-part flag, `playerStats`. A record without it (written before this ticket) means "not requested".
- Status rule, unchanged in spirit: **a refresh succeeds only if every part it asked for was stored (or was already up to date)**. Failure reason = the first failed requested part in the order **fixtures, standings, playerStats**. A part the run didn't request is ignored.
- **The trigger does not ask for `playerStats` in this ticket.** The scraper rejects unknown properties in `POST /scrape` with a `400` (`PROPERTIES` in `scraper/src/server.js`), which the backend would treat as "scraper unavailable", and every refresh would break until KAN-73. So: the trigger request record gets the field, the backend fills it from **one named constant** set to `false`, and a `false` `playerStats` is **omitted** from the JSON body. KAN-73 flips the constant and teaches the scraper the property in the same PR. Add a test that today's trigger body has exactly the six properties it has now.
- The stats share the per-team 10-minute lock (same scrape, same team). The league lock is irrelevant to them.

### 4. The opaque id of a scraped player

- Each player in the snapshot has an id of ours (a random UUID string). **On replace, keep the id of every `playerId` already in the stored snapshot; only a new `playerId` gets a new id.** So a link request that arrives during a refresh still finds its player.
- No football.org.il id (`playerId`, team, league, season) in any response, ever (spec §09, now extended to `player_id`). Assert it on the raw JSON.

### 5. Links: their own club-scoped collection

- New collection (e.g. `player_stats_links`), a `ClubScopedEntity`: `sourcePlayerId` (football.org.il's `playerId`), `playerId` (our `Player` id), `linkedAt`. Unique indexes on `(clubId, sourcePlayerId)` and `(clubId, playerId)`: at most one link per scraped player and per `Player`. **Not** a field on `Player` (a `PUT` is a full replacement and would wipe it, and would bump `version`).
- Keyed by the stable `sourcePlayerId`, **not** by season and not by the opaque id: a player who disappears from a scrape keeps their link, and it's back when they reappear; it survives a season change.
- Linking a released player is allowed.
- **The dangling-link rule.** Permanent deletion of a `Player` is not version-checked, and a link and a delete can race (a check in each, in separate transactions, can both pass: write skew). Don't promise what a lock can't give. Instead: **a link whose `Player` no longer exists counts as no link**, everywhere (reads, suggestions, the delete guard is moot). A new link for that scraped player replaces it. Document this in the link service's Javadoc.

### 6. Endpoints

In `match`, under `/matches` (already proxied by the frontend dev server), club from `ClubContext` only, the team and season from `ClubFootballMappings.currentClubMapping()`, never from the request:

- `GET /matches/player-stats` — `VIEW_ONLY`. Same state model as the other reads (`MatchDataState`: `NOT_MAPPED`, `NO_DATA`, `OK`) and `lastUpdatedAt` = the snapshot's `fetchedAt`, serialised like the existing ones. `players` in the site's order, each: `id` (opaque), `name`, the nine counts, `linkedPlayer` (`{id, fullName, active}` or `null`) and `suggestedPlayer` (same shape or `null`, only when not linked).
- `PUT /matches/player-stats/{id}/link`, body `{"playerId": "<our Player id>"}` — `EDIT_FULL`. Sets or replaces this scraped player's link; returns the row as in the `GET`. `404` if the id isn't in the club's current snapshot (or the club isn't mapped / has no data) or the `Player` isn't in the caller's club (the club-scoped lookup gives that for free: a `Player` of another club is a `404`, never a `403` that would leak its existence). `409` with a new code `PLAYER_ALREADY_LINKED` if that `Player` is linked to another scraped player. Linking the same pair again is a no-op `200`. A unique-index violation under a race is the same `409`, not a `500`.
- `DELETE /matches/player-stats/{id}/link` — `EDIT_FULL`. `204`, idempotent (no link → `204`). `404` for an unknown id.
- `DELETE /squad/players/{id}/stats-link` — `EDIT_FULL`. Removes the link of **our** `Player`, whether or not the scraped player is still in the snapshot. Without it, a linked player who left the site's table could never be unlinked, and so never deleted. `204`, idempotent; `404` if the `Player` isn't in the club.

### 7. Name suggestions (a suggestion only, never applied)

- Normalise both names: Unicode NFC; geresh `׳` and `'` `’` `‘` `` ` `` `´` → one character; gershayim `״` and `"` `“` `”` → one character; hyphen `-` and maqaf `־` → a space (matching only, never stored); any run of whitespace (incl. no-break spaces) → one space; trim; Latin letters lowercased. Then split into a **set of words**.
- Candidates: the club's `Player`s, active and released, that aren't linked (by a non-dangling link).
- For a scraped player with words A and a candidate with words B: **exact** if A = B; **partial** if one contains the other and they share at least 2 words.
- If there are exact candidates, consider only them; otherwise the partial ones. Suggest a `Player` only if it is the **only** candidate for this scraped player **and** this scraped player is the only one it would be suggested for. Anything ambiguous → no suggestion.
- Pure function in its own class, with unit tests (below). Computed on read; nothing stored.

### 8. Permanent deletion of a linked `Player`

- `PlayerService.delete` refuses with `409`, new code `PLAYER_STATS_LINKED`, **before** it deletes the photo (the order today is photo, then player; a refused delete must change nothing).
- **No dependency cycle.** `match` will depend on `squad` (to check a `Player` exists and read its name). So `squad` must not depend on `match`: define a small interface in `squad` (e.g. "is this player linked", "remove this player's link") that `match` implements, and inject it into `PlayerService` and the `stats-link` endpoint. Report the direction of every new cross-package import.

## Out of scope

The scraper (KAN-73); every screen and message (KAN-74); creating a `Player` from a scraped player (KAN-77); per-game data, lineups, jersey numbers (layers B and C in the findings); showing stats on the player's own card; a scheduled refresh; any write to `Player` other than the existing endpoints.

## Verify against reality, not memory

Check these against what's actually installed (`backend/pom.xml`, resolved versions via `./mvnw dependency:list` or the jars in `~/.m2`), not general knowledge, and say in the summary how you checked each:

- Spring Boot 4.1.1 and the resolved Spring Data MongoDB, Jackson and Hibernate Validator versions.
- How the existing club-scoped repositories declare compound unique indexes (and whether `auto-index-creation` is on, or indexes are created in code): build the two new collections' indexes the same way, and **prove with a Testcontainers test that each unique index exists and rejects a duplicate**.
- That a `DuplicateKeyException` from a club-scoped `save` surfaces the way `storeIfNewer` and your link service expect with this Spring Data MongoDB version.
- How Bean Validation handles container-element constraints on `List<@NotNull @Valid …>` and `@AssertTrue` methods on nested records in this Hibernate Validator version (follow `StandingsPart`).
- `java.text.Normalizer` NFC behaviour on the Hebrew geresh / gershayim and their ASCII look-alikes: prove it with unit tests, don't assume.
- How the existing `409`s are produced (`ConflictException` + `GlobalExceptionHandler`) and how `MatchReadService` builds `NOT_MAPPED` / `NO_DATA` / `lastUpdatedAt`: follow them.

## Read first

`CLAUDE.md` (standing rules, especially 4, 5 and 7), then only:

- `scraper/docs/KAN-70-findings.md` (the table, the proposed payload, the names) and `scraper/test/fixtures/team-players-stats-7617-s28.xml` (only to build realistic test data; see "Test data")
- `docs/scraper-backend-contract.md` (whole file); `scraper/src/server.js` (the trigger's `PROPERTIES`); `scraper/src/backendClient.js` (how it reads the ingest response)
- `backend/src/main/java/com/squadpulse/scrapingintegration/`: `ScrapeResultRequest.java`, `ScrapeResultController.java`, `ScrapeResultResponse.java`, `ScrapeRunStatusService.java`, `ScrapeTriggerService.java`, `ScraperClient.java`
- `backend/src/main/java/com/squadpulse/match/`: `ScrapeIngestService.java`, `IngestResult.java`, `LeagueFixturesSnapshot.java` + its repository, `MatchReadService.java`, `MatchController.java`, `MatchDataState.java`, `package-info.java`
- `backend/src/main/java/com/squadpulse/squad/`: `PlayerService.java`, `PlayerController.java`, `PlayerRepository.java`, `Player.java` (skim)
- `backend/src/main/java/com/squadpulse/common/`: `ClubContext.java`, `ClubScopedEntity.java`, `ConflictException.java`, `GlobalExceptionHandler.java` (skim)
- `backend/src/main/java/com/squadpulse/auth/ClubFootballMappings.java`
- Tests as a style model: `scrapingintegration/ScrapeResultApiIntegrationTest.java`, `ScrapePayloads.java`, the existing run-status tests, `match/CrossClubLookupConfinementTest.java`, `match/StandingsWritePathTest.java`, `common/EndpointAuthorizationRules.java`, `common/CapturedLogs.java`, and one squad delete test
- `docs/spec.md` sections 03, 04, 05, 07, 09, 14: to list what goes out of date, **not** to edit

## Test data

The fixture keeps real names and ids on purpose (Gal's decision). Build test payloads in code (extend `ScrapePayloads`) with **made-up** names and ids, except where a test explicitly parses the fixture. Include 3-word names and names in surname-first order.

## Tests

Follow the existing style (WebMvc tests for controllers, Testcontainers integration tests with real Mongo and Redis for services and the full flow). At least:

**Contract and ingest** (through the real `/internal/scrape-results` with the service token)

- A valid `playerStats` alone → `200`, `playerStats.outcome` `STORED`; stored for every club mapped to exactly the three ids and for no other club (a club mapped to another season gets nothing).
- Each `400`: `games != starts + cameOnAsSub`; duplicate `playerId`; empty list; 81 entries; a negative count; a blank name; a control character in a name; OK with `reason`; FAILED with `players`; `fetchedAt` too far in the future; an unknown property in a player. Each writes nothing, **including the request's fixtures** (assert).
- A player with minutes far above 90 × games is **accepted**.
- `FAILED` → `FAILURE_RECORDED`, nothing written, the old snapshot intact.
- Not mapped → `REJECTED NOT_MAPPED`. Older `fetchedAt` → `STALE`, nothing changed. The same payload twice → second `STALE`.
- Replace: a second, newer scrape drops player X and adds player Y → X's stats are gone, Y's are there, every surviving player **keeps its opaque id**, Y has a new one.
- All three parts in one request → each outcome independent (e.g. standings `REJECTED`, stats `STORED`).
- No log line contains a player name (`CapturedLogs`).

**Refresh status**

- A run that requested `playerStats`: stats `STORED` / `STALE` → `SUCCEEDED`; `FAILED PARSE_ERROR` → `FAILED PARSE_ERROR`; `REJECTED` → `FAILED REJECTED`; not sent → `FAILED NO_RESULT`; fixtures and stats both failed → the fixtures' reason.
- A run that didn't request `playerStats` (and an old run record without the flag): a result with or without stats → judged on the other parts only.
- The trigger body today has exactly `runId, teamId, leagueId, seasonId, fixtures, standings`.

**Reads, links, suggestions** (two clubs A and B mapped to different teams, plus a third club C mapped to the **same** team as A, to prove links and opaque ids are per club)

- Authorization: `GET` `VIEW_ONLY`+; link / unlink / `stats-link` `EDIT_FULL`+ (`VIEW_ONLY`, `EDIT_PARTIAL` → `403`); no token → `401`; the scraper service token → `401`.
- `GET`: `NOT_MAPPED`, `NO_DATA`, `OK` with `lastUpdatedAt`; the raw JSON contains no football.org.il id of any kind (assert on the raw string for each source id in the snapshot).
- Link → `GET` shows `linkedPlayer`; re-link the same pair → `200`, no change; link another scraped player to the same `Player` → `409 PLAYER_ALREADY_LINKED`; replace a scraped player's link with another `Player` → the old `Player` is free again.
- Isolation: A's user can't link to B's `Player` (`404`), can't see or unlink B's or C's links; C's opaque ids differ from A's for the same `playerId`; a link in A doesn't appear in C.
- Race: ~10 parallel links of different scraped players to the same `Player` → exactly one `200`, the rest `409`, never `500`.
- A linked player missing from the next scrape: the link survives; `DELETE /squad/players/{id}/stats-link` removes it; when the player reappears in a later scrape (with no unlink in between), the `GET` shows the link again.
- Dangling link: delete the `Player` behind the guard's back (directly in the repository), then `GET` shows the row unlinked and offers suggestions as if unlinked, and a new link for that scraped player works.
- Suggestions (unit tests on the pure function): surname-first vs first-name-first → exact; 3 words vs 2 → partial; only 1 shared word → none; two exact candidates → none; one `Player` the only candidate for two scraped players → none for either; geresh vs apostrophe, gershayim vs quote, hyphen vs maqaf vs space, double and no-break spaces → equal; a linked `Player` is never suggested; a released `Player` can be. Plus one integration test that the `GET` returns the suggestion.

**Deletion**

- `DELETE /squad/players/{id}` of a linked `Player` → `409 PLAYER_STATS_LINKED`, the player **and its photo** still there. After unlinking → `204`.

**Rule tests:** `EndpointAuthorizationRules`, `InternalEndpointRules`, `CrossClubLookupConfinementTest` and the club-scoped repository rules pass unchanged. If you can add the stats snapshot to a write-path rule like `StandingsWritePathTest` cheaply (only the ingest writes it), do; otherwise say why not.

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. drop the `games == starts + cameOnAsSub` check;
2. generate a fresh opaque id for every player on replace;
3. resolve the scraped player from any club's snapshot instead of the caller's;
4. drop the unique index on `(clubId, playerId)` in the links;
5. delete the photo before the linked-player check;
6. count a dangling link as linked;
7. send `playerStats: false` in the trigger body instead of omitting it;
8. suggest when there are two exact candidates.

Report each and whether it was caught.

Run `./mvnw spotless:apply` then `./mvnw verify` from `backend/` with `JAVA_HOME` set to Temurin 21 (Spotless crashes on JDK 25). Report the backend test count before (on master) and after.

**No manual check against the live site** in this ticket: the scraper doesn't send stats until KAN-73.

## Docs

- **`docs/scraper-backend-contract.md`:** the `playerStats` part (every field, the limits, the one cross-field check, the 400 rules, the outcomes, the opaque id and that `player_id` never leaves the backend); the trigger's `playerStats` property as **defined but not sent yet** (KAN-73 turns it on, together with the scraper); the run status rule with three parts. Keep it consistent with `ScrapeResultRequest`'s Javadoc.
- **`README.md`** (CLAUDE.md rule 7, same commit as the code): the four endpoints in the existing API style (permissions, shapes, every status code and the two new `409` codes); `DELETE /squad/players/{id}` can now answer `409 PLAYER_STATS_LINKED`.
- **`CLAUDE.md`:** the status line (the new endpoints) and one short paragraph in the right place: stats and links are club-scoped (no new bypass), the ingest never writes `Player`, `player_id` is never exposed, links survive a player's disappearance, a dangling link counts as none, and `squad` doesn't depend on `match`. Nothing else.
- **Javadoc:** the snapshot and link entities, the link service (the dangling-link rule and why there's no lock), the suggestion class (the exact rule), the changed `ScrapeIngestService`, `ScrapeResultRequest`, `ScrapeRunStatusService`, `PlayerService.delete`, `match/package-info.java`.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words) and what's wrong. I expect at least: the status line (top) and §13; §03 (two new club-scoped collections; no new exception); §04 (the four endpoints' permission levels); §05 (deleting a linked player is refused with `409`; the "not enforced yet — nothing references players today" sentence); §07 (player season stats from the site, "all competitions", the snapshot, the links and suggestions, the third refresh part not requested yet; the scope sentence still says minutes are "a later ticket (KAN-70)"); §09 (`player_id` never exposed); §14 (the jersey-number question: scraping never writes `Player.jerseyNumber`). Add any I missed.

## Commits

Small, focused commits, for example:

- `feat(scrapingintegration): playerStats part in the ingest contract and run status (KAN-72)`
- `feat(match): club-scoped player season stats snapshot with stable opaque ids (KAN-72)`
- `feat(match): player links and name suggestions (KAN-72)`
- `feat(squad): refuse deleting a linked player, unlink from the player (KAN-72)`
- `docs: contract, README and CLAUDE.md for player stats (KAN-72)` (or with the code, per rule 7)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions verified and **how** you checked each, with the findings for every item in "Verify against reality" (evidence, not assumptions).
3. The final design: both collections (fields, indexes, how they're created), the ingest flow for the third part, how opaque ids are carried over, the link service's rules, the suggestion rule as implemented, the `squad` ↔ `match` interface and the direction of every new cross-package import.
4. Raw examples from tests: one ingest response with all three parts; one `GET /matches/player-stats` for each state (`OK` with a linked row, a suggested row and a plain row); each new `409` body.
5. Tests: the new tests (name + one line each), the mutation checks and their results, the test count before and after, and the `verify` result (local).
6. Docs changed (contract doc, README, CLAUDE.md, Javadoc), one line each.
7. Spec: every out-of-date place (section + quoted words + what's wrong).
8. Deviations from this prompt, and why.
9. Open ends and risks for KAN-73, KAN-74 and KAN-77 (including any validation check you thought of but didn't add).
10. Print in full, at branch HEAD:
    - the `playerStats` records in `ScrapeResultRequest` (with their checks);
    - the link service;
    - the suggestion class;
    - the changed `PlayerService.delete` and the new interface in `squad`.
