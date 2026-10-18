# KAN-84: Backend: store cup fixtures as their own part and snapshot, merge them into the fixtures read with competition and round

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `110c6d9`, the KAN-76 merge, PR #62). If it doesn't, stop and report. Then create the branch `feature/KAN-84-cup-games-backend`.

## Context

Jira KAN-84, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-76 (done: the cup-games spike, **no production code**). Blocks KAN-85 (the scraper loads the team page, fetches the State Cup list, sends this part, and takes played cup games into game details) and KAN-86 (the frontend: competition label, cup round, next-match card across competitions).

Today only league games exist in the backend. This ticket gives the club's **State Cup** games a home of their own, next to the league fixtures, extends the scraper ↔ backend contract with a fifth part, and merges both into `GET /matches/fixtures`. Nothing in this ticket scrapes anything.

Read **only** the section "Decided for KAN-84 / KAN-85 / KAN-86" and §1, §2, §5, §6 of `scraper/docs/KAN-76-findings.md`. Where the findings' "Proposed cup part" differs from this prompt, **this prompt wins** (no Toto Cup; the read shape; run status; the empty-list rule). The facts that matter here:

- A cup list row has: the round **as the cup names it** (free text: `ו`, `ה' - ליגות א' דרום`), date, home and away as **full club names** (not the league's short names: they can't be matched against the standings), venue, kick-off, a result, extra-time and penalties cells (format **unseen**: both empty in every captured row), and a `game_id`.
- **No team id** in a cup row. Our side (`ownSide`) is decided by the scraper (exact match to the team page's `h1`) and sent with every game. The backend does **no** name matching for cups.
- `game_id` is unique across the site (league and cup), so game details keyed by `gameId` can't collide.

Files to read first: `backend/src/main/java/com/squadpulse/match/` — `ScrapeIngestService`, `LeagueFixturesSnapshot`, `StoredGame`, `ScrapedFixtures`, `MatchReadService`, `FixturesResponse`, `KnownGames`, `GameDetailsReadService`, `GameDetailsResponse`, `IngestResult`, `package-info.java`; `backend/src/main/java/com/squadpulse/scrapingintegration/` — `ScrapeResultRequest`, `ScrapeResultController`, `ScrapeResultResponse`, `ScrapeRunStatusService`, `ScrapeTriggerService`, `ScraperClient`; `docs/scraper-backend-contract.md`; tests `StandingsWritePathTest`, `ContractExampleIntegrationTest`, `ScrapeIngestServiceIntegrationTest`, `CrossClubLookupConfinementTest`.

## Rules (from the ticket; not up for change)

- **No football.org.il id** (game, team, league, season, player) in any user-facing response (spec §09). The cup `gameId` never leaves the backend. Assert it on the raw JSON.
- **Club-scoped**, ordinary `clubId` isolation: the new collection extends `ClubScopedEntity`, custom repository methods contain `ClubId` in their name, **no** new `@NotClubScoped`, no new `@GloballyScoped`, no new cross-club lookup. Clubs are found exactly as for the player statistics (`ClubFootballMappings.clubIdsMappedTo(teamId, leagueId, seasonId)`, already confined to `ScrapeIngestService`), each write inside `ClubContext.callAs`. `CrossClubLookupConfinementTest` must pass **unchanged**.
- **The league snapshot, its key and its ingest are unchanged.** No migration. A failed or rejected cup competition never touches the league snapshot, and the reverse.
- **The trigger doesn't ask for cup games in this ticket** (decision 1).

## Verify against reality, not assumptions

Before relying on any framework behaviour, check it against what's installed: Spring Boot, Spring Data MongoDB, Jackson and Bean Validation versions from `backend/pom.xml` (and the resolved versions, e.g. `./mvnw dependency:tree` or the effective POM), the JDK used by the build. In particular confirm, with evidence (a test or the docs of the **installed** version), not from memory:

1. how a `@CompoundIndex` with four fields is created at startup in this project (follow what `LeagueFixturesSnapshot` does; check the index exists in an integration test or say how you checked);
2. that a Java `enum` field in a record stored inside a document round-trips as its name in this Spring Data MongoDB version;
3. that Jackson in this project rejects an unknown enum value (e.g. `"competition": "TOTO_CUP"`) with a `400`, not silently as `null` (check `ScrapeResultJsonConfig` and prove it with a test);
4. that `@JsonInclude(NON_DEFAULT)` on a `boolean` record component omits `false` in this Jackson version (the trigger test, decision 1).

Report each finding and **how** you checked it.

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Trigger (backend → scraper): `cupGames`, defined but **not sent yet**

One optional property in `POST /scrape`: `cupGames` (boolean; absent = `false`). Meaning: load the team page and fetch the team's cup lists (KAN-85). Needs only `teamId` and `seasonId`.

- Exactly as KAN-72 / KAN-79 did: one named constant `REQUEST_CUP_GAMES = false` in `ScrapeTriggerService`, Javadoc saying why (`scraper/src/server.js` rejects unknown properties with `400`, which the backend treats as "scraper unavailable"; KAN-85 flips it together with the scraper; deploy order below). A `false` `cupGames` is **omitted** from the JSON body (`@JsonInclude(NON_DEFAULT)`, like `playerStats`).
- Add a test that today's trigger body has **exactly** the properties it has now on master (check which; with `REQUEST_GAME_DETAILS = true` that includes `gameDetails` and, when non-empty, `knownGameIds`), and a test that with `cupGames = true` the body carries `"cupGames": true`.
- The run record in Redis gets a fifth requested-part flag, `cupGames` (the `ACQUIRE_SCRIPT`'s arguments and the hash, and `ScrapeRunStatusService.Run`). A record without it (written before this ticket, i.e. runs in flight at deploy) means **not requested**. Update the Javadoc of both Lua scripts' argument lists.
- **Deploy order** (write it in the constant's Javadoc and the contract doc): flipping the constant puts cup games into `GET /matches/fixtures`. KAN-86 (frontend) must be live before, or together with, the flip, then the scraper (KAN-85), then the backend constant, or all together. Today's frontend decides "played" from `result`, which is `null` for a cup game with extra time / penalties (decision 6).

### 2. The contract: a fifth part, `cupGames`

`POST /internal/scrape-results` gets an optional fifth part, built like the others (`status` OK / FAILED, the same `FailureReason`s, the same OK-has-data / FAILED-has-reason rule, `fetchedAt` not more than `CLOCK_SKEW` in the future, checked in `ScrapeResultController` like the others). `isAnyPartPresent` counts five parts.

```
"cupGames": {
  "status": "OK",
  "fetchedAt": "<instant>",
  "competitions": [
    { "competition": "STATE_CUP", "status": "OK", "games": [ CupGame, … ] }
  ]
}
```

- **The part is `FAILED` only when the team page failed** (no `ownName`, so no row could be read). Then: `reason` (+ optional `detail`), no `fetchedAt`, no `competitions`.
- `OK`: `fetchedAt` and `competitions` required, no `reason` / `detail`. `competitions`: 1–5 entries, none `null`, **each `competition` at most once** (a `400` otherwise: a structural error).
- **CupCompetition:** `competition` (required) — a new enum `CupCompetition` with exactly one value today, **`STATE_CUP`**, designed to grow (the regional cup, KAN-87; **no `TOTO_CUP`**: there's no Toto Cup for Liga Alef and below). `status` OK / FAILED (required). `OK` carries `games` (required, **0–50**, none `null`; **empty is a valid `OK`**: the site's `Games: null`) and no `reason` / `detail`; `FAILED` carries `reason` (+ optional `detail`, ≤ 500, `@NoControlCharacters`) and no `games`.
- **CupGame** (every field present; `null` only where stated):

| Field | Type | Rules | `null` means |
|---|---|---|---|
| `gameId` | int or `null` | ≥ 1 | No link / the site's `-1` (unseen). **An attribute, never the key.** Never leaves the backend. |
| `round` | string | 1–100 chars after trimming, no control characters | — As the cup list shows it. Never the game page's `מחזור N`. |
| `date` | string | `yyyy-MM-dd`, a real date (strict, like `Game.date`) | — |
| `kickoffTime` | string or `null` | `HH:mm` (strict) | Not set (the site's `00:00`). |
| `homeTeamName`, `awayTeamName` | string | 1–100 chars after trimming, no control characters, different | — Full club names, as the list shows them. |
| `ownSide` | `HOME` \| `AWAY` | required (`FixturesResponse.Side`, as in `GameDetailsGame`) | — Decided by the scraper. |
| `venue` | string or `null` | 1–200 chars after trimming, no control characters | None shown. |
| `homeScore`, `awayScore` | int or `null` | 0–99; both or neither; **home first** | Not played. |
| `extraTime`, `penalties` | string or `null` | 1–**50** chars after trimming, no control characters; **never interpreted** | Empty cell. The format is unseen: the limit is deliberately loose, because any field violation is a `400` for the **whole** request. |

- Trim strings with the existing `trim` helper (space characters only, so `@NoControlCharacters` still sees control characters), as the other records do.
- `extraTime` / `penalties` set while the scores are `null`: **allowed** (don't add a cross-field check on an unseen format). Say so in the contract doc.
- **Not a Bean Validation check: a duplicate key within one competition** (decision 3). It must never be a `400`: that would lose the league fixtures, standings, stats and game details of the same request.

### 3. Storage and ingest

**Collection** `cup_fixtures`, entity `CupFixturesSnapshot extends ClubScopedEntity`, one document per **`(clubId, teamId, seasonId, competition)`** (unique compound index), `@Version`, fields: `teamId`, `seasonId`, `competition`, `fetchedAt`, `storedAt`, `games` (list of `StoredCupGame`, in the order sent). **No `leagueId` filter on read** (a cup belongs to the team and season, not the league), so don't store `leagueId`. Repository package-private, methods with `ClubId` in the name.

`StoredCupGame` (a record): `key`, `gameId`, `round`, `date` (ISO string, as `StoredGame` — and for the same reason), `kickoffTime` (ISO string or `null`), `homeTeamName`, `awayTeamName`, `ownSide`, `venue`, `homeScore`, `awayScore`, `extraTime`, `penalties`. **No team ids.** Key: `(round, homeTeamName, awayTeamName)` joined with U+001F, like `StoredGame.keyOf` (the competition is already the document's key). Give it its own `keyOf`.

**Ingest** (`ScrapeIngestService`, the only writer; add it to the class Javadoc's list and order: standings, fixtures, player stats, **cup games**, game details). `ScrapeResultController` maps the request to a new `ScrapedCupGames` in `match` (like `ScrapedFixtures`; dates and times as `LocalDate` / `LocalTime`), passing `null` for a `FAILED` part. Per competition, independently:

- Scraper reported the competition `FAILED` → nothing written for it (logged at INFO with reason and ids; `detail` at DEBUG only).
- No club mapped to exactly `(teamId, leagueId, seasonId)` → every `OK` competition `REJECTED NOT_MAPPED` (and the part `REJECTED NOT_MAPPED`).
- **A duplicate key** in the competition's `games` → that competition `REJECTED DUPLICATE_GAME`; other competitions are unaffected.
- **Empty `games` while the club's stored snapshot for the same `(team, season, competition)` has at least one game** → that competition `REJECTED` with a new reason **`EMPTY_REPLACES_GAMES`** (add it to `IngestResult.RejectionReason`, with Javadoc: within a season cup games never leave the list, played games stay listed after an elimination, so an empty list over stored games is a site glitch, not news; the stored games stay and the refresh fails visibly). Checked **per club** inside its `ClubContext`. Empty with nothing stored (or only an empty snapshot) → stored normally as an empty snapshot (`STORED`, or `STALE`).
- Otherwise: the whole list replaces that competition's snapshot via the existing `storeIfNewer` (staleness by the part's `fetchedAt`, the same lost-race handling). `STORED` if written for at least one club, else `STALE`.
- **No ownership check** for cups (no `INVALID`, no standings name, no `OWN_TEAM_UNKNOWN`), **no team-name resolution**, no `UNRESOLVED_TEAM_NAMES` warning for cup names.
- Logs: ids, competition and counts only. Never a team name, never a `round`.

Part outcome (for the response and run status): the part is `STORED` when processed (whatever each competition's outcome, like `gameDetails`), `REJECTED NOT_MAPPED` when no club is mapped, `NOT_SENT` when absent. Each competition carries its own outcome (decision 4).

### 4. Response

`ScrapeResultResponse` gains:

```
"cupGames": {
  "outcome": Outcome, "reason": RejectionReason | null,
  "competitions": [ { "competition": "STATE_CUP", "outcome": Outcome, "reason": RejectionReason | null } ]
}
```

- `competitions` lists every competition in the request, in the request's order: `STORED` / `STALE` / `REJECTED` (+ reason) / `FAILURE_RECORDED` (the scraper reported it `FAILED`).
- Part `FAILED` → `{ "outcome": "FAILURE_RECORDED", "reason": null, "competitions": [] }`. Absent → `NOT_SENT` with `competitions: []`.
- Keep the existing four parts' shape exactly as today (the KAN-80 scraper reads it; check `scraper/src/` for how it reads the response, read only, and say whether an added top-level field breaks it).

### 5. Run status

`ScrapeRunStatusService.firstFailure`: if the run requested `cupGames`, it's checked **after** `playerStats` and **before** `gameDetails` (order: fixtures, standings, playerStats, cupGames, gameDetails), and fails the run when:

- the part is `FAILURE_RECORDED` → the scraper's reason (as for the other parts), `REJECTED` → `REJECTED`, `NOT_SENT` → `NO_RESULT`;
- the part is processed but **a competition in a backend constant `EXPECTED_CUP_COMPETITIONS = Set.of(STATE_CUP)` is missing** from it → `NO_RESULT`;
- any competition is `FAILURE_RECORDED` → its scraper reason; `REJECTED` (any reason, incl. `DUPLICATE_GAME`, `EMPTY_REPLACES_GAMES`) → `REJECTED`.

`STORED` / `STALE` competitions are fine. A run that didn't request `cupGames` ignores the part whatever it says (as today). Update the class Javadoc.

### 6. Fixtures read: `GET /matches/fixtures`

`MatchReadService.fixtures()` merges the league snapshot (as today: current mapping, `leagueId` filter) with the club's cup snapshots for the current `(teamId, seasonId)` (every competition; one query).

**Each game** (`FixturesResponse.Game`) gains, additively:

- `competition`: a new enum `Competition` in `FixturesResponse`: `LEAGUE`, `STATE_CUP` (maps 1:1 from `CupCompetition`; an exhaustive `switch`, no `default`, so a new cup competition doesn't compile until it's mapped);
- `round`: the cup list's round string; `null` for league games;
- `extraTime`, `penalties`: the raw strings; `null` for league games and empty cells.

**Our side:** league games as today (by `ownName`); cup games from the stored `ownSide`. The opponent is the other side's name (full club name for cups).

**Result:** `Result.of(own, opponent)` as today, **except `null` when `extraTime` or `penalties` is non-null** (KAN-76 decision 6: a cup tie decided on penalties must never show as a plain draw, nor a W/L computed from a score of unknown meaning). Both scores are still shown.

**Bug to fix on the way** (it would break exactly that case): `fromOurSide` uses `result == null` to mean "not played", so a penalties game would get `detailsState = null`. "Played" must mean **both scores are set**, for league and cup games alike. `detailsState` / `detailsId` for cup games exactly as for league games, joined by `gameId` against the same two in-memory maps (no extra query).

**Top level:**

- `state`: `OK` if the league snapshot (for the current league) **or** at least one cup snapshot exists; `NO_DATA` if neither; `NOT_MAPPED` as today.
- `lastUpdatedAt`: the **oldest** `fetchedAt` among the snapshots shown (honest: the list is at least that old). Javadoc it.
- `teamName`: the league snapshot's short name as today; `null` if there's no league snapshot.
- `mappingProblem`: unchanged.

**Order:** by date, then kick-off (unset last within its day, as today), then **league before cup**, then the snapshot's own order (stable sort). Javadoc on `games`.

### 7. Game details read: `GET /matches/games/{id}`

`GameDetailsResponse.result` must also be `null` for a cup game with extra time or penalties: in `GameDetailsReadService`, if the record's `gameId` is a game in the club's cup snapshots (current team and season, club-scoped, one query) whose `extraTime` or `penalties` is non-null, return `result = null`. Scores unchanged. Nothing else in that read changes. Document on `GameDetailsResponse.result` that it can be `null` now.

### 8. Known games

`KnownGames.knownGameIds` is built from stored `GameDetails` and given-up `GameDetailAttempts`, **not** from the fixtures, so played cup games join it with **no code change** once KAN-85 sends their details. Don't change it; prove it with a test (decision below), and say so in the summary.

## Tests

Integration tests against the real Mongo / Redis setup the existing tests use (follow `ScrapeIngestServiceIntegrationTest`), plus unit tests where they're cheaper. At least:

**Contract (`400`, nothing written — assert the league snapshot of the same request is not written either):** an unknown competition (`TOTO_CUP`); the same competition twice; part `OK` without `competitions`, and `FAILED` with them; competition `OK` without `games`, `FAILED` without `reason`; 51 games; `homeTeamName` = `awayTeamName` after trimming; one score set; `round` blank; `extraTime` 51 chars; a control character in `round`, `extraTime`, a name; `date` `2026-02-30`; `kickoffTime` `24:00`; `fetchedAt` more than 5 minutes ahead; only `cupGames` present is a valid request.

**Ingest:**

- first store; replace whole (a game missing later disappears); `STALE` on the same and an older `fetchedAt`;
- per competition: competition `FAILED` → stored games stay; **league fixtures `FAILED` + cup `OK` → cup stored; cup competition `REJECTED` + league `OK` → league stored** (independence, both directions);
- duplicate key in a competition → `REJECTED DUPLICATE_GAME`, stored games stay, **the request is still a `200`** and the league part of the same request is stored; same pairing in two different rounds → both stored;
- empty list: nothing stored → stored empty `OK`; stored games → `REJECTED EMPTY_REPLACES_GAMES`, games stay; stored empty snapshot → empty `OK`;
- no club mapped → `REJECTED NOT_MAPPED`;
- **isolation**: two clubs A and B mapped to the same team / league / season both get their own copy; club C mapped to another team gets nothing; A's read never shows C's cup games;
- the league snapshot document is byte-for-byte untouched by a cup ingest (compare before / after).

**Response:** the per-competition outcomes for `STORED`, `STALE`, `REJECTED DUPLICATE_GAME`, `REJECTED EMPTY_REPLACES_GAMES`, `FAILURE_RECORDED`; part `FAILED`; part absent.

**Run status** (requested `cupGames` = true via the run record): all fine → `SUCCEEDED`; competition `FAILED PARSE_ERROR` → `FAILED PARSE_ERROR`; competition `REJECTED` → `FAILED REJECTED`; part absent → `NO_RESULT`; part `OK` without `STATE_CUP` (only possible once the enum grows: test the rule with the constant as a parameter, or explain how you covered it) → `NO_RESULT`; a run record **without** the `cupGames` field ignores a failing cup part; order: failing playerStats and cupGames → playerStats' reason.

**Trigger:** the body-shape tests (decision 1); the run record stores `cupGames` "0".

**Fixtures read** (raw JSON assertions):

- league + cup merged and ordered (incl. same date and time: league first; an unset time last within its day across both);
- a cup game `ownSide` `AWAY` → opponent is the home name, scores flipped;
- a cup game with `penalties` and scores → `result` `null`, scores shown, **`detailsState` `PENDING`** (the bug fix), and `AVAILABLE` / `UNAVAILABLE` when its details / given-up attempts exist;
- a league game played → `detailsState` as today (regression);
- only cup snapshots → `OK`, `teamName` `null`; only league → as today with `competition` `LEAGUE` and `round` `null`; `lastUpdatedAt` = the older of the two;
- a cup snapshot from an earlier season (mapping moved) is not shown;
- the raw JSON contains no `gameId` of the test data (league or cup), no team / league / season id.

**Game details read:** a cup game's details with `penalties` in the cup snapshot → `result` `null`; without → as today; a league game → as today.

**Known games:** a stored `GameDetails` for a cup game's `gameId` (and a given-up one) appears in `knownGameIds` (no league fixture with that id exists).

**Contract doc example:** add a `cupGames` example request and its response to `docs/scraper-backend-contract.md` with markers, and extend `ContractExampleIntegrationTest` to post it and check the answer, like the game-details example.

**Rule tests:** `EndpointAuthorizationRules`, `InternalEndpointRules`, `CrossClubLookupConfinementTest` and the club-scoped repository rules pass **unchanged**. Add the cup collection to `StandingsWritePathTest` (only `ScrapeIngestService` writes it), like the player stats and game details.

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. apply the league ownership check (`INVALID`) to cup games;
2. make a duplicate cup key a Bean Validation `400`;
3. drop the `EMPTY_REPLACES_GAMES` check;
4. replace the cup snapshot when the competition is `FAILED`;
5. compute `result` despite `penalties`;
6. revert "played" to `result != null` in `fromOurSide`;
7. order cup before league at the same date and time;
8. ignore a `FAILURE_RECORDED` competition in the run status;
9. send `cupGames: false` in the trigger body instead of omitting it;
10. drop the club filter (read the cup snapshot by team and season only, without `clubId`) — if the club-scoped repository makes this impossible to write, say so instead.

Report each and whether it was caught.

Run `./mvnw spotless:apply` then `./mvnw verify` from `backend/` with `JAVA_HOME` set to Temurin 21 (Spotless crashes on JDK 25). Report the backend test count before (on master) and after. The frontend is **not** changed in this ticket; don't touch `frontend/`.

**No manual check against the live site**: the scraper doesn't send the part until KAN-85.

## Docs

- **`docs/scraper-backend-contract.md`:** title list gains KAN-84; the trigger's `cupGames` as **defined but not sent yet** (KAN-85 turns it on; the deploy order of decision 1, including KAN-86 first); the `cupGames` part (every field and limit, the 400 rules, what is **not** a 400 and why, per-competition status, the empty-list rule, staleness, no name matching and no team ids for cups, that `gameId` never leaves the backend); "What the backend does" (order and a cup bullet); run status with five parts and the expected-competitions rule; the response with `competitions`; the example (above).
- **`README.md`** (CLAUDE.md rule 7, same commit as the code): the four new fields on `GET /matches/fixtures` games, the `state` / `lastUpdatedAt` / `teamName` rules, and `result` `null` with extra time / penalties (also on `GET /matches/games/{id}`).
- **`CLAUDE.md`:** the status line, and one short paragraph: cup games are a club-scoped snapshot per competition (no new bypass), `ownSide` from the scraper, no name matching and no team ids for cups, `gameId` never exposed, no W/D/L with extra time / penalties. Nothing else.
- **Javadoc:** the new entity, record, repository and enums; the changed `ScrapeIngestService`, `IngestResult`, `ScrapeResultRequest`, `ScrapeResultResponse`, `ScrapeRunStatusService`, `ScrapeTriggerService` / `ScraperClient` (the constant and why it's `false`), `MatchReadService`, `FixturesResponse`, `GameDetailsReadService` / `GameDetailsResponse`, `match/package-info.java`.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words) and what's wrong. I expect at least: the status line and §13; §03 (one new club-scoped collection; no new exception); §07 (scope: cup games; the fifth refresh part, defined but not requested; what's stored and the cup identity rule; the merged read and "next game" across competitions; run status); §09 (`game_id` never exposed, now for cups too). Add any I missed.

## Commits

Small, focused commits, for example:

- `feat(scrapingintegration): cupGames part, trigger flag and run status (KAN-84)`
- `feat(match): club-scoped cup fixtures snapshot per competition (KAN-84)`
- `feat(match): merge cup games into the fixtures read; no result with extra time or penalties (KAN-84)`
- `fix(match): decide "played" from the scores, not the result (KAN-84)`
- `docs: contract, README and CLAUDE.md for cup games (KAN-84)` (or with the code, per rule 7)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions verified and **how** you checked each, with the findings for every item in "Verify against reality" (evidence, not assumptions).
3. The final design: the collection (fields, index, how it's created), the ingest flow per competition, the empty-list rule as implemented, the response shape, the run-status change, the merged read (state, `lastUpdatedAt`, order, result rule, the "played" fix), the game-details read change, and the direction of every new cross-package import.
4. Raw examples from tests: one ingest response with all five parts and two competitions' outcomes if you can construct it (otherwise one competition); one `GET /matches/fixtures` with a league game, a cup game and a cup game decided on penalties; one `GET /matches/games/{id}` with `result: null`.
5. Tests: the new tests (name + one line each), the mutation checks and their results, the test count before and after, and the `verify` result (local).
6. Docs changed (contract doc, README, CLAUDE.md, Javadoc), one line each.
7. Spec: every out-of-date place (section + quoted words + what's wrong).
8. Deviations from this prompt, and why.
9. Open ends and risks for KAN-85 and KAN-86 (including any validation check you thought of but didn't add, and whether the KAN-80 scraper's response reading tolerates the new `cupGames` field).
10. Print in full, at branch HEAD:
    - the `cupGames` records in `ScrapeResultRequest` (with their checks);
    - the cup handling in `ScrapeIngestService`;
    - `MatchReadService.fixtures()` and `fromOurSide` (or what replaced it);
    - the run-status change;
    - the `GameDetailsReadService` change.
