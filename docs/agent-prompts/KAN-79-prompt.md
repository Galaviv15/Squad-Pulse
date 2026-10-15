# KAN-79: Backend: store per-game details (our lineup, shirt numbers, goals, cards, subs), contract, known-games trigger, read endpoint

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `d37659d`, the KAN-75 merge, PR #58). If it doesn't, stop and report. Then create the branch `feature/KAN-79-game-details-backend`.

## Context

Jira KAN-79, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-75 (done: the game-details spike, **no production code**). Blocks KAN-80 (the scraper fetches game pages and sends this part) and KAN-81 (the frontend game-details screen). Related: KAN-76 (cup games, later; it will change the fixtures' identity key).

Each played game on football.org.il has a details page with both lineups, the **per-game** shirt number, goals, cards and substitution minutes, plus the competition and round in its `h1`. This ticket gives **our side** of that page a home in the backend, extends the scraper ↔ backend contract with it, tells the scraper which games to skip, and exposes one game's details to the frontend. Nothing in this ticket scrapes anything, and nothing writes `Player`.

Read `scraper/docs/KAN-75-findings.md` for the facts. The ones that matter here:

- `game_id` on the team-games row equals the page's id. Ids are **not chronological**.
- Our side is picked by the header `team_id` (KAN-80's job). The opponent's players are never sent (see Rules).
- Per player: `player_id` (the same id as KAN-72's stats, so it reaches our `Player` through the existing `player_stats_links`), name **first name first**, sometimes masked by the site (`*****`, sent as `null`), shirt number `מס' N`, role (starter / came on / unused), `(GK)`, `(C)`.
- Events: goal, yellow, second yellow, red, sub on, sub off, each with a minute shown as a **plain number** (93, 98; no `90+N`), or unknown (the site's `-1`, sent as `null`). Events can belong to any role. Own goal and penalty markup is **unseen**: not in this contract (KAN-80 fails the game loudly on them).
- Count anomalies (starters ≠ 11, no GK, our goals ≠ our score, subs on ≠ subs off) are real on real pages: **never** reject a game for them.
- The "Proposed parsed output" and "Proposed trigger addition" in the findings are proposals. The shapes below are the decision.

## Rules (from the ticket; not up for change)

- **Never writes `Player`.** `Player.jerseyNumber` stays club-managed; the per-game shirt number lives only on the appearance.
- **No per-game minutes played** (the page has none). Store sub-on / sub-off minutes as events, nothing derived.
- **No football.org.il id** (game, player, team, league, season) in any user-facing response (spec §09). Assert it on raw JSON.
- **Our side only** (Gal, 2026-10-14): the opponent's lineup and scorers are never stored or sent. For the opponent only its name and the score.
- **Club-scoped**, ordinary `clubId` isolation, **no** new `@NotClubScoped`, no new bypass. `CrossClubLookupConfinementTest` must pass unchanged.

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Trigger (backend → scraper): `gameDetails` + `knownGameIds`, defined but **not sent yet**

Two optional properties in `POST /scrape`:

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `gameDetails` | boolean | optional; absent = `false` | Fetch the details of up to 2 finished, unknown games (KAN-80). |
| `knownGameIds` | int array | optional; absent = `[]`; only with `gameDetails: true`; ≤ 200 entries, each 1..2147483647, no duplicates | Games the scraper must not fetch: those whose details we already store **and** those we gave up on (decision 5). The scraper doesn't need to know which is which. |

- The name is `knownGameIds`, not the findings' `storedGameIds`: the list also holds given-up games.
- 200 ids ≈ 1.8 KB, inside the scraper's 4 KB body limit with everything else. If a club has more than 200 known ids for the current team and season, the backend **doesn't request** `gameDetails` for that trigger and logs a WARN (ids and counts only). Don't truncate: a truncated list would make the scraper re-fetch stored games.
- The list is built from the **triggering club's** own data (club-scoped reads, inside the `ClubContext` the trigger already runs in, for both the HTTP and the scheduled path), for the club's current `(teamId, seasonId)`.
- **The trigger does not ask for `gameDetails` in this ticket.** `scraper/src/server.js` rejects unknown properties with `400`, which the backend treats as "scraper unavailable": every refresh would break until KAN-80. Exactly as KAN-72 did with `REQUEST_PLAYER_STATS`: the trigger record gets both fields, the backend fills `gameDetails` from **one named constant** `REQUEST_GAME_DETAILS = false`, and a `false` `gameDetails` and its `knownGameIds` are **omitted** from the JSON body. Still build and unit-test the list-building code path (call it with the constant's value as a parameter, so a test can exercise `true`). KAN-80 flips the constant and teaches the scraper both properties in the same PR. Add a test that today's trigger body has exactly the properties it has now (`runId, teamId, leagueId, seasonId, fixtures, standings, playerStats`).
- The run record in Redis gets a fourth requested-part flag, `gameDetails` (the Lua script's arguments and the hash). A record without it (written before this ticket) means "not requested".

### 2. The contract: a fourth part, `gameDetails`

`POST /internal/scrape-results` gets an optional fourth part, built like the others (`status` OK / FAILED, the same `FailureReason`s, the same OK-has-data / FAILED-has-reason rule, `fetchedAt` not more than `CLOCK_SKEW` in the future). `ScrapeResultRequest`'s "at least one part" check now counts four parts.

```
"gameDetails": {
  "status": "OK",
  "fetchedAt": "<instant>",
  "games": [ GameDetails, … ],
  "failures": [ { "gameId": 1122670, "reason": "PARSE_ERROR", "detail": "unknown event icon" } ]
}
```

- `games`: 0–10 entries, none null. **An empty list is a valid `OK`** (nothing to fetch, or every attempt failed).
- `failures`: 0–10 entries, none null. Each: `gameId` ≥ 1, `reason` a `FailureReason` (required), `detail` string or `null`, ≤ 500 chars, `@NoControlCharacters`. **Only games the scraper actually requested** appear in `failures`; a game it never started (out of time, after a block) is simply absent.
- A `gameId` appears at most once across `games` and `failures` together.

**GameDetails** (every field present; `null` only where stated):

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `gameId` | int | ≥ 1 | football.org.il's `game_id`. Never leaves the backend. |
| `date` | string | `yyyy-MM-dd`, a real date | From the team-games row (Israel local, like the fixtures). |
| `kickoffTime` | string or `null` | `HH:mm` | From the row. |
| `competition` | string | 1–100 chars after trimming, no control characters | The `h1` before ` מחזור N`, e.g. `ליגה א' דרום`. |
| `round` | int | 1–99 | `N` from the `h1`. |
| `ownSide` | `HOME` \| `AWAY` | | Our side on the page (picked by `team_id`). |
| `homeTeamName`, `awayTeamName` | string | 1–100 after trimming, no control characters, different | Short names, as the page shows them. |
| `homeScore`, `awayScore` | int | 0–99 each | Home first. A finished game always has both. |
| `players` | array of Appearance | 1–40, none null, `playerId` unique in the list | **Our side only.** |

**Appearance:**

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `playerId` | int | ≥ 1 | `player_id`. Never leaves the backend. |
| `name` | string or `null` | 1–100 after trimming, no control characters | First name first. `null` = masked by the site. Never logged. |
| `shirtNumber` | int or `null` | 0–99 | The number in **this** game. `null` if missing (unseen so far). |
| `role` | `STARTER` \| `SUBSTITUTE` \| `UNUSED` | | `SUBSTITUTE` = came on. |
| `goalkeeper`, `captain` | boolean | | `(GK)`, `(C)`. |
| `events` | array of Event | 0–10, none null | |

**Event:** `kind` ∈ `GOAL`, `YELLOW`, `SECOND_YELLOW`, `RED`, `SUB_ON`, `SUB_OFF`; `minute` int 0–150 or `null` (unknown). No other kind (own goal / penalty are added in their own ticket once a fixture pins them).

- **No cross-field count checks** at all: not starters = 11, not one GK, not goals = score, not subs on = subs off, not events vs role. If you think one is needed, don't add it: list it under open ends.
- Any violation is a `400` for the whole request, like today: nothing from it is written, not even its other parts.
- Logs: ids and counts only. **No player name, no team name, no `detail` above DEBUG.**

### 3. Storage

Two new club-scoped collections in `match`, both `ClubScopedEntity`, both written **only** by `ScrapeIngestService`:

**`game_details`**, one document per `(clubId, teamId, seasonId, gameId)` (unique index):

- `id` (a random UUID string: **the opaque id**, decision 6), `version`, `teamId`, `seasonId`, `gameId`, `fetchedAt`, `storedAt`;
- `date`, `kickoffTime` (ISO strings, like `StoredGame`, for the same reason), `competition`, `round`, `ownSide`, `homeTeamName`, `awayTeamName`, `homeScore`, `awayScore` — stored on the record itself, **not** taken from the fixtures snapshot, which is replaced on every scrape and will be re-keyed by KAN-76;
- `appearances`: the players exactly as sent (`sourcePlayerId`, `name`, `shirtNumber`, `role`, `goalkeeper`, `captain`, `events` sorted by minute, unknown last).

**`game_detail_attempts`**, one document per `(clubId, teamId, seasonId, gameId)` (unique index): `failedAttempts` (int), `lastReason`, `lastAttemptAt`, `version`.

- Written for every club mapped to exactly `(teamId, leagueId, seasonId)` (the existing `mappings.clubIdsMappedTo`, each club inside `ClubContext.callAs`), exactly like the player statistics. No such club → `REJECTED NOT_MAPPED`.
- **Per game, not a snapshot.** A game in `games` creates its record, or **replaces its own record only** if the incoming `fetchedAt` is newer than the stored one (otherwise that game is skipped silently). On replace, **keep the opaque id**. Other games' records are never touched. Use the existing load / compare / save-with-`@Version` / re-read-once pattern (`storeIfNewer` or its equivalent).
- Storing a game's details **deletes** its attempts record, if any.
- Indexes: build them the way the existing club-scoped collections do (see "Verify against reality").

### 4. Ingest outcome and run status

- `gameDetails` is processed last (standings, fixtures, playerStats, gameDetails). Add it to `IngestResult` and `ScrapeResultResponse` as a `PartOutcome` with the same enum: `STORED` when the part was accepted and processed (including 0 games, and including games skipped as not newer), `REJECTED NOT_MAPPED`, `FAILURE_RECORDED` for a `FAILED` part, `NOT_SENT` when absent. Do **not** use `STALE` for this part (staleness is per game here). `clubsUpdated` keeps its meaning (fixtures).
- Check `scraper/src/backendClient.js` reads the response in a way that tolerates a fourth field (it logs a fixed list of parts today). Say so in the summary with the line numbers.
- **Run status:** a run that requested `gameDetails` treats the part like the others: `STORED` fine; `FAILURE_RECORDED` fails the run with the scraper's reason; `REJECTED` → `REJECTED`; `NOT_SENT` → `NO_RESULT`. The order of the "first failed part" becomes fixtures, standings, playerStats, gameDetails. **Per-game entries in `failures` never fail the run**: otherwise one broken page would turn every refresh red. A part the run didn't request is ignored.

### 5. Failed games: count, give up after 3

- For each entry in `failures`: if `reason` is `PARSE_ERROR` or `SOURCE_ERROR` (the page itself is broken), increment that game's `failedAttempts` (create the record at 1). `TIMEOUT` and `SOURCE_BLOCKED` **don't count** (the site or the network, not the page); update `lastReason` / `lastAttemptAt` only. Otherwise an outage would burn healthy games in three refreshes.
- A game is **given up** when `failedAttempts >= 3` (one named constant). Given-up games go into `knownGameIds` (decision 1) and show as unavailable (decision 6).
- A failure for a game that already has stored details is ignored (no attempts record).
- No reset mechanism in this ticket. List under open ends: a new scraper version could reset the counts (the findings' suggestion).

### 6. Read

Club from `ClubContext` only; team and season from `ClubFootballMappings.currentClubMapping()`, never from the request.

**`GET /matches/fixtures`** (existing): each `Game` gets two fields:

- `detailsId`: the `game_details` opaque id, or `null`;
- `detailsState`: `AVAILABLE` (a record exists) | `UNAVAILABLE` (given up, decision 5) | `PENDING` (played, neither) | `null` for a game not played yet.

Joined by `StoredGame.gameId` against the club's records for the current `(teamId, seasonId)`: one query per collection per read, not per game. A stored game whose `gameId` is `null` gets `null` / `PENDING`-or-`null` by the same rule.

**`GET /matches/games/{id}`** (new), `VIEW_ONLY`+:

- `404` if the id isn't a `game_details` record of the caller's club **for its current mapping** (an old season's record is not shown, like old snapshots), or the club isn't mapped. A record of another club is a `404`, never a `403`.
- Response (no football.org.il id anywhere):

```
{
  "id": "<opaque>",
  "date": "2026-10-09", "kickoffTime": "20:00",
  "competition": "ליגה א' דרום", "round": 6,
  "ownSide": "AWAY", "opponentName": "…",
  "ownScore": 1, "opponentScore": 4, "result": "LOSS",
  "lastUpdatedAt": "<fetchedAt>",
  "players": [
    { "name": "…" | null,
      "linkedPlayer": { "id": "…", "fullName": "…", "active": true } | null,
      "shirtNumber": 7 | null, "role": "STARTER", "goalkeeper": false, "captain": true,
      "events": [ { "kind": "GOAL", "minute": 38 }, { "kind": "RED", "minute": 40 } ] }
  ]
}
```

- `players` in the order starters, substitutes, unused; within a role, the order sent.
- `name` is the name as the site shows it (`null` if masked); `linkedPlayer` comes from the existing KAN-72 links by `sourcePlayerId`, reusing their service, **with the dangling-link rule** (a link whose `Player` no longer exists counts as none). The frontend shows `linkedPlayer.fullName` when present (KAN-81). Never `Player.jerseyNumber`.
- Reuse `FixturesResponse`'s `Side` / `Result` enums and its result rule.

### 7. Package boundaries

`match` already depends on `squad` (KAN-72). Don't add a `squad` → `match` dependency. Report the direction of every new cross-package import.

## Out of scope

The scraper (KAN-80); every screen and message (KAN-81); cup games and the fixtures key change (KAN-76); own-goal / penalty events; any reset of the attempt counts; per-game minutes played; anything about the opponent beyond name and score; deleting old seasons' records; a scheduled refresh; any write to `Player`.

## Verify against reality, not memory

Check these against what's actually installed (`backend/pom.xml`, resolved versions via `./mvnw dependency:list` or the jars in `~/.m2`), not general knowledge, and say in the summary how you checked each:

- Spring Boot 4.1.1 and the resolved Spring Data MongoDB, Jackson and Hibernate Validator versions.
- How KAN-72's collections declare their compound unique indexes (and whether `auto-index-creation` is on, or indexes are created in code): build both new ones the same way, and **prove with a Testcontainers test that each unique index exists and rejects a duplicate**.
- How Bean Validation handles container-element constraints and `@AssertTrue` methods on nested records (follow `PlayerStatsPart`), including on a nullable list element field and an enum.
- How the existing trigger record omits `playerStats: false` (`@JsonInclude(NON_DEFAULT)` on a `boolean`): prove with a test that `gameDetails: false` and an empty `knownGameIds` are both omitted with this Jackson version (an empty list is not a "default" for every inclusion setting).
- How `ScrapeTriggerService`'s Lua script and `ScrapeRunStatusService` read the run hash, so an old record without `gameDetails` reads as "not requested".

## Read first

`CLAUDE.md` (standing rules, especially 4, 5 and 7), then only:

- `scraper/docs/KAN-75-findings.md` (sections 1–5, "Proposed parsed output", "Proposed trigger addition", "Decided for KAN-79", "Open questions"); `scraper/test/fixtures/` game-page fixtures only to build realistic test data
- `docs/scraper-backend-contract.md` (whole file); `scraper/src/server.js` (the trigger's properties); `scraper/src/backendClient.js` (how it reads the ingest response)
- `backend/src/main/java/com/squadpulse/scrapingintegration/`: `ScrapeResultRequest.java`, `ScrapeResultController.java`, `ScrapeResultResponse.java`, `ScrapeRunStatusService.java`, `ScrapeTriggerService.java`, `ScraperClient.java`
- `backend/src/main/java/com/squadpulse/match/`: `ScrapeIngestService.java`, `IngestResult.java`, `StoredGame.java`, `FixturesResponse.java`, `MatchReadService.java`, `MatchController.java`, `PlayerSeasonStatsSnapshot.java` + its repository, `PlayerStatsLink.java` + `PlayerStatsLinkService.java`, `PlayerStatsReadService.java`, `package-info.java`
- `backend/src/main/java/com/squadpulse/common/`: `ClubContext.java`, `ClubScopedEntity.java` (skim)
- `backend/src/main/java/com/squadpulse/auth/ClubFootballMappings.java`
- Tests as a style model: `scrapingintegration/ScrapeResultApiIntegrationTest.java`, `ScrapePayloads.java`, `ContractExampleIntegrationTest.java`, the run-status and trigger tests, `match/CrossClubLookupConfinementTest.java`, `match/StandingsWritePathTest.java`, KAN-72's player-stats tests, `common/EndpointAuthorizationRules.java`, `common/CapturedLogs.java`
- `docs/spec.md` sections 03, 04, 07, 09, 13, 14: to list what goes out of date, **not** to edit

## Test data

Our side in the KAN-75 fixtures is real (names and ids); the opponent is anonymised. Build test payloads in code (extend `ScrapePayloads`) with **made-up** names and ids. Include a masked name (`null`), a `null` minute, minutes 91–98, two goals by one player, a goal and a red for the same player, a booked unused player, a captain who is the goalkeeper, a 1-player bench, and a lineup with 10 starters (accepted).

## Tests

Follow the existing style (WebMvc tests for controllers, Testcontainers integration tests with real Mongo and Redis for services and the full flow). At least:

**Contract and ingest** (through the real `/internal/scrape-results` with the service token)

- A valid `gameDetails` with 2 games → `200`, `STORED`; each game stored for every club mapped to exactly the three ids and for no other club.
- `OK` with `games: []` and `failures: []` → `STORED`, nothing written.
- Each `400`: an unknown event kind; a minute of 151; `round` 0; `players` empty; 41 players; a duplicate `playerId`; a `gameId` in both `games` and `failures`; a `gameId` twice in `games`; same home and away name; a control character in a player name; OK with `reason`; FAILED with `games`; `fetchedAt` too far in the future; an unknown property in an appearance. Each writes nothing, **including the request's other parts** (assert).
- Accepted (no count checks): 10 starters, no goalkeeper, our goals ≠ our score, subs on ≠ subs off, a yellow on an unused player.
- Re-sent game with a newer `fetchedAt` → its record replaced, **opaque id kept**, the other game untouched; with an older or equal `fetchedAt` → unchanged.
- `FAILED` part → `FAILURE_RECORDED`, nothing written, no attempt counted.
- Not mapped → `REJECTED NOT_MAPPED`.
- All four parts in one request → independent outcomes.
- No log line contains a player or team name (`CapturedLogs`).

**Attempts**

- `PARSE_ERROR` ×3 for one game → given up: in `knownGameIds`, `UNAVAILABLE` in fixtures. `SOURCE_ERROR` counts the same.
- `TIMEOUT` ×5 and `SOURCE_BLOCKED` ×5 → never given up.
- Two `PARSE_ERROR`s, then the game arrives in `games` → stored, attempts record gone, `AVAILABLE`.
- A failure for an already stored game → ignored.

**Trigger**

- Today's trigger body has exactly `runId, teamId, leagueId, seasonId, fixtures, standings, playerStats` (no `gameDetails`, no `knownGameIds`).
- The list builder (exercised with the flag as `true`): stored ∪ given-up ids for the triggering club's current team and season only (not another club's, not another season's, not a game with 2 failures); no duplicates; > 200 → `gameDetails` not requested and a WARN.
- The run record stores `gameDetails` `"0"` today; an old record without the field reads as not requested.

**Run status**

- A run that requested `gameDetails`: `STORED` (with per-game `failures`) → `SUCCEEDED`; `FAILED PARSE_ERROR` → `FAILED PARSE_ERROR`; not sent → `FAILED NO_RESULT`; fixtures and gameDetails both failed → the fixtures' reason.
- A run that didn't request it: a result with or without the part → judged on the other parts only.

**Reads** (clubs A and B mapped to different teams, plus C mapped to the **same** team as A)

- Authorization: `GET /matches/games/{id}` `VIEW_ONLY`+; no token → `401`; the scraper service token → `401`.
- Fixtures: `detailsId` / `detailsState` for a stored, a given-up, a pending and an unplayed game.
- Details: the full response; a linked player shows `linkedPlayer`; a dangling link shows `null`; a masked name is `null`; events sorted by minute, unknown last; players ordered by role.
- Isolation: A's user gets `404` for B's and C's ids; C's opaque id differs from A's for the same `gameId`; an id from A's previous season (after the mapping moved) → `404`.
- The raw JSON of both reads contains no `gameId`, `playerId`, team, league or season id of the test data (assert on the raw string for each).

**Contract doc example:** add a `gameDetails` example request and its response to `docs/scraper-backend-contract.md` with markers, and extend `ContractExampleIntegrationTest` to post it and check the answer, like the player-stats example.

**Rule tests:** `EndpointAuthorizationRules`, `InternalEndpointRules`, `CrossClubLookupConfinementTest` and the club-scoped repository rules pass unchanged. If you can add both new collections to a write-path rule like `StandingsWritePathTest` cheaply (only the ingest writes them), do; otherwise say why not.

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. count `TIMEOUT` as a failed attempt;
2. give up at 2 instead of 3;
3. generate a new opaque id on replace;
4. replace a game's record with an older `fetchedAt`;
5. drop the given-up ids from `knownGameIds`;
6. read the details by id without the current-mapping check;
7. fail the run on a per-game `failures` entry;
8. send `gameDetails: false` in the trigger body instead of omitting it;
9. drop the duplicate-`playerId` check.

Report each and whether it was caught.

Run `./mvnw spotless:apply` then `./mvnw verify` from `backend/` with `JAVA_HOME` set to Temurin 21 (Spotless crashes on JDK 25). Report the backend test count before (on master) and after.

**No manual check against the live site** in this ticket: the scraper doesn't send the part until KAN-80.

## Docs

- **`docs/scraper-backend-contract.md`:** the trigger's `gameDetails` / `knownGameIds` as **defined but not sent yet** (KAN-80 turns them on together with the scraper; deploy order as KAN-73: scraper first or together); the `gameDetails` part (every field and limit, no count checks, the 400 rules, `failures`, the outcomes, per-game staleness, the attempt rule and which reasons count, the opaque id, that `game_id` / `player_id` never leave the backend); the run status rule with four parts and per-game failures not failing a run; the example (above).
- **`README.md`** (CLAUDE.md rule 7, same commit as the code): `GET /matches/games/{id}` in the existing API style; the two new fields on `GET /matches/fixtures`.
- **`CLAUDE.md`:** the status line and one short paragraph: game details are club-scoped (no new bypass), our side only, never write `Player`, per-game shirt numbers only on the appearance, no football.org.il id exposed, attempt counting and give-up after 3. Nothing else.
- **Javadoc:** both new entities, the changed `ScrapeIngestService`, `ScrapeResultRequest`, `ScrapeRunStatusService`, `ScrapeTriggerService` / `ScraperClient` (the constant and why it's `false`), `MatchReadService`, `match/package-info.java`.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words) and what's wrong. I expect at least: the status line and §13; §03 (two new club-scoped collections; no new exception); §04 (the new endpoint's permission level); §07 (the scope still lists "playing minutes"; KAN-75 described as building lineups / shirt numbers; the fourth refresh part, defined but not requested; the trigger's known-games list; attempt counting); §09 (`game_id` never exposed); §14 (per-game shirt numbers vs `Player.jerseyNumber`). Add any I missed.

## Commits

Small, focused commits, for example:

- `feat(scrapingintegration): gameDetails part, known-games trigger fields and run status (KAN-79)`
- `feat(match): club-scoped game details and attempt counts (KAN-79)`
- `feat(match): game details read endpoint and details state on fixtures (KAN-79)`
- `docs: contract, README and CLAUDE.md for game details (KAN-79)` (or with the code, per rule 7)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions verified and **how** you checked each, with the findings for every item in "Verify against reality" (evidence, not assumptions).
3. The final design: both collections (fields, indexes, how they're created), the ingest flow for the fourth part, per-game staleness and opaque-id carry-over, the attempt rule as implemented, how `knownGameIds` is built, the run-status change, the fixtures join, and the direction of every new cross-package import.
4. Raw examples from tests: one ingest response with all four parts; one `GET /matches/fixtures` game for each `detailsState`; one `GET /matches/games/{id}`.
5. Tests: the new tests (name + one line each), the mutation checks and their results, the test count before and after, and the `verify` result (local).
6. Docs changed (contract doc, README, CLAUDE.md, Javadoc), one line each.
7. Spec: every out-of-date place (section + quoted words + what's wrong).
8. Deviations from this prompt, and why.
9. Open ends and risks for KAN-80, KAN-81 and KAN-76 (including any validation check you thought of but didn't add).
10. Print in full, at branch HEAD:
    - the `gameDetails` records in `ScrapeResultRequest` (with their checks);
    - the game-details and attempts handling in `ScrapeIngestService`;
    - the `knownGameIds` builder;
    - the run-status change.
