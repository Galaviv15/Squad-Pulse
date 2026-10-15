# KAN-80: Scraper: fetch up to 2 finished, not-yet-stored game pages per scrape, parse our side, send the game-details part

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `1a92eb6`, the KAN-79 merge, PR #59). If it doesn't, stop and report. Then create the branch `feature/KAN-80-game-details-scraper`.

## Context

Jira KAN-80, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-75 (done: the game-details spike, findings + fixtures, no production code) and KAN-79 (done: the backend stores the `gameDetails` part, builds `knownGameIds`, counts failed attempts, serves the details). Blocks KAN-81 (frontend game-details screen). Related: KAN-76 (cup games, later).

KAN-79 defined the trigger's `gameDetails` / `knownGameIds` and the result's `gameDetails` part, but **nothing requests them yet**: `ScrapeTriggerService.REQUEST_GAME_DETAILS` is `false`, because today's `scraper/src/server.js` rejects both properties as unknown (`400`). This ticket teaches the scraper both properties, adds a fourth step to the scrape (up to 2 game pages), parses **our side** of each page, sends the part, and flips the backend constant in the **same PR**.

The contract (`docs/scraper-backend-contract.md`, "Trigger" and "Game details part") is the decision. `scraper/docs/KAN-75-findings.md` has the facts (markup, selectors, request budget). Where the findings' **proposals** differ from the contract (e.g. `storedGameIds`, `side`), the contract wins.

## Rules (from the ticket and earlier decisions; not up for change)

- **Our side only** (Gal, 2026-10-14). The opponent's players, scorers and cards are never parsed into the payload, sent or logged. For the opponent only its name and the score.
- **Route rule unchanged.** A game page is a same-origin **document** (`session.navigate`). Never widen `browserSource.js`'s route rule to `fetch` / `xhr` or anything else.
- **Existing request policy unchanged:** the 3–5 s pause before every request, 30 s document timeout shrunk to the time left, the 90 s cap, **one retry per scrape, shared** by every request, and the block policy (a block stops the whole scrape).
- **The other parts never suffer for this one.** A game-details failure (parse, contract, request) never changes `fixtures`, `standings` or `playerStats`.
- **No names in logs**, ours or the opponent's, at any level. Game ids, counts, reasons and rule names are fine. Our side in the fixtures is real data.
- **No live site in tests / CI.** Tests use the KAN-75 fixtures and in-memory variants of them.

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Trigger (`scraper/src/server.js`)

Two new optional properties, exactly as the contract defines them:

| Field | Rules |
|---|---|
| `gameDetails` | boolean; absent = `false` |
| `knownGameIds` | int array; absent = `[]`; **only with `gameDetails: true`** (present with `gameDetails` false or absent → `400`); ≤ 200 entries; each an integer 1..2147483647; no duplicates. Order not required. |

- Every rejection is a `400` with a **fixed** message (as today: never echo the body or a value).
- The job always carries `gameDetails` (boolean) and `knownGameIds` (array, `[]` when absent).
- "At least one part `true`" now counts four parts.
- The "trigger accepted" / "deduplicated" log line adds `gameDetails` and `knownGameIds` as a **count**, not the ids.
- 200 ten-digit ids plus everything else must fit `MAX_BODY_BYTES` (4096): prove it with a test of the largest legal body.
- Queue deduplication (`queue.js`) is unchanged: a deduplicated trigger keeps the queued job's `knownGameIds`. Say in the README that this is harmless (a game fetched anyway is stored for every mapped club; a given-up game is at worst fetched once more and counted again).

### 2. The scrape sequence (`scraper/src/scrape.js`)

1. **Team-games page** (document), if `fixtures` **or** `playerStats` **or** `gameDetails`. Don't rely on the backend always sending `fixtures: true`.
   - Parse it **once** with `parseGamesPage` and reuse the result for `fixtures` and for the game selection.
   - Page failed → `gameDetails` = `FAILED` "not attempted: …", exactly like `playerStats` today (blocked → `SOURCE_BLOCKED` + `NOT_ATTEMPTED_BLOCKED`).
   - Page loaded but didn't parse → `gameDetails` = `FAILED PARSE_ERROR` "not attempted: the team games page didn't parse (…)". `fixtures` is `FAILED PARSE_ERROR` as today; `playerStats` still runs.
2. Player stats (unchanged).
3. League page + `LeagueTable` (unchanged).
4. **Game pages, last** (findings §6: a slow site loses them to the cap before anything else). If an earlier request was blocked → `gameDetails` = `FAILED SOURCE_BLOCKED` `NOT_ATTEMPTED_BLOCKED`.

**Selection** (from step 1's rows, nothing else):

- A row qualifies when it has a `gameId` (not `null`), **both** scores, and its kick-off is **at least 24 h ago** (`clock.now()`), and its `gameId` is not in `knownGameIds`.
- Kick-off instant = the row's `date` + `kickoffTime` in **Asia/Jerusalem** (DST-correct, not a fixed +3). A `null` `kickoffTime` (the parser turns `00:00` / empty into `null`) counts as **23:59** of that date: later, so never too early.
- Sort by date, then kick-off time (`null` as 23:59), then `gameId` (ids are **not** chronological). Take the **first 2** (oldest first).
- Zero qualifying games → `gameDetails` = `OK` with `games: []`, `failures: []`. No request.

**Per selected game, in order:**

- **15 s guard:** right before the pause, if less than 15 s of the cap remain, don't start it (and don't start any later game). A game not started is **absent** from both lists (contract), with an INFO log (`gameId`, "not attempted: time limit"). Put the 15 s in `SCRAPE_DEFAULTS` (e.g. `gameMinRemainingMs: 15_000`) so tests can drive it.
- Request `${origin}leagues/games/game/?game_id=${id}` with the existing `request("document", …)`.
- Request failure:
  - `SOURCE_BLOCKED` → a `failures` entry for this game, `blocked = true`, **stop**: the next game is absent.
  - `TIMEOUT` / `SOURCE_ERROR` (incl. a `404`) → a `failures` entry, continue with the next game (the guard still applies).
- Loaded → `parseGameDetails` (below). `ParseError` → a `failures` entry `PARSE_ERROR` with the message as `detail`. Success → the game, with `date` and `kickoffTime` **from the row**.

The part is `OK` with `fetchedAt` (taken once, when the part is built, after the last game page), `games` and `failures` (both always present, possibly empty). Every `failures` `detail` goes through the same cleaning as `failedPart` (control characters, 500 chars) and must never contain a name.

### 3. The parser: new `scraper/src/parseGameDetails.js`

`parseGameDetails(html, { gameId, teamId, row })` → `{ game, warnings }` or throws `ParseError`. Pure; Cheerio only. Follow the findings §§1–5 and the "Goal detection" rules exactly. **Fail (ParseError) on:**

- no `section#gfd_<gameId>` (the requested id);
- the page not marked finished: **all three** of `div.teams-names.past`, `#gTimeHolder.past`, `data-onlive="False"` on the section are required;
- the header (`div.team-home` / `div.team-guest`, the link's `team_id`): **neither or both** sides equal `teamId`. Our side = the one that matches → `ownSide` `HOME` / `AWAY`;
- **cross-check with the team-games row (Gal, 2026-10-15):** the page's home / away names (whitespace collapsed) must equal the row's `homeTeamName` / `awayTeamName`, in that order, and the page's home / away score must equal the row's. Any mismatch → ParseError (the detail names the field, never the value);
- an `h1` (text before its `span`, whitespace collapsed) not matching `^(.+) מחזור (\d+)$`, or a round outside 1–99;
- a score (`div.total`, **home first**) that isn't two integers 0–99;
- in our side's groups, a player link without a valid `player_id` (read from the URL's query; `season_id` is optional), a duplicate `player_id`, a `span.number` that isn't empty / missing (→ `null`) or `מס' N` with N 0–99, an empty name, or a name suffix other than empty, `- (GK)`, `- (C)`, `- (GK) (C)`, `- (C) (GK)`;
- **an unknown event** in our players' `span.moves`: walk the **child nodes in order** (never `.text()` on the whole). Allowed: `span.yellow` → `YELLOW`, `span.yellow2` → `SECOND_YELLOW`, `span.red` → `RED`, `span.change-down` → `SUB_OFF`, `span.change-up` → `SUB_ON`, each with its minute after the `sr-only` `דקה`; and the bare goal: an `img` whose `src` **file name** is `football.svg`, then `sr-only` `שער`, `sr-only` `דקה`, then the minute as a text node → `GOAL`. Any other class, element, `sr-only` text or icon file (e.g. `own_goal.svg`, `penalty.svg`) → ParseError naming the class / file;
- **an unknown icon in a timeline event node**: `#gameMoves > div.timeline > div.node` (both teams' nodes: an own goal by either side must fail the game) with an icon file other than `football.svg`. **Never** the legend `#gameMoves > div.keys` (it holds `own_goal.svg` / `penalty.svg` on every page): select event nodes structurally, not by position;
- a minute that isn't an integer 0–150 or `-1` (`-1` → `null`).

**Mapping:** groups by **role class and side class together** (`.Active` → `STARTER`, `.Replacement` → `SUBSTITUTE`, `.Bench` → `UNUSED`), never `div.home` / `div.guest` alone (that matches the header row and the coaches). Coaches (`coach_id`) and referees are never read. `name` = `span.name > b` trimmed, first name first; all asterisks (`*****`) → `null`. `goalkeeper` / `captain` from the suffix. Events sorted by minute, `null` last, stable. Players in page order (starters, substitutes, unused).

**Warnings, never failures** (the contract has no count checks): starters ≠ 11; no goalkeeper among the starters; our players' `GOAL` events ≠ our score; `SUB_ON` count ≠ `SUB_OFF` count. Return them as `{ check, expected, actual }`; `scrape.js` logs each at WARN with the `gameId`. No names.

### 4. Local contract check (`scraper/src/contract.js`)

- Add `gameDetails` to `PARTS` (this also makes `backendClient.js` log its outcome) and a `validateGameDetails` that mirrors **`backend/.../scrapingintegration/ScrapeResultRequest.java`**, the source of truth: read the Java records and their checks, don't copy from this prompt or the doc.
- **Per-game demotion (Gal, 2026-10-15):** in `enforceContract`, a game in `games` that breaks a rule is **moved to `failures`** as `PARSE_ERROR` with `contract check: <path: rule>` (the path relative to that game, no values, no names). The other game stays. Only a **part-level** violation (`fetchedAt`, list sizes, a `gameId` twice across the lists, a bad `failures` entry, missing lists) makes the whole part `FAILED PARSE_ERROR`. Reason: a whole `FAILED` part fails the run and loses the good game; a moved game is counted and retried by KAN-79's attempt rule.
- Check the existing violation messages: none may include a value (a name must never reach a `detail`).

### 5. Backend: flip the constant (same PR)

- `ScrapeTriggerService.REQUEST_GAME_DETAILS = true`. Update its Javadoc, `ScraperClient`'s, and the tests that pin `false` (`ScraperClientTest` asserts it is `false`; the "today's trigger body" test). Keep the tests proving that a `false` `gameDetails` and an empty `knownGameIds` are omitted.
- Check every test / fake that builds or asserts the trigger body or the run record (`gameDetails` `"1"` now), including any stub scraper used by e2e or frontend CI (`grep` for it). Change only what the flip breaks.
- **Deploy order** (KAN-73 / KAN-79): the scraper first, or both together. A backend with the flag on in front of today's scraper gets `400` on every trigger → every refresh fails as `SCRAPER_UNAVAILABLE`. This goes in the contract doc and the PR body.

### 6. One shared trigger example

Add a trigger example with `gameDetails: true` and two `knownGameIds` to the contract doc, with a marker (e.g. `<!-- contract-example-trigger -->`). The scraper's `parseTrigger` test accepts it; a backend test asserts that `ScraperClient`'s body for the same inputs has exactly the same properties and values. That ties both sides to one text.

## Out of scope

The frontend (KAN-81); cup games (KAN-76); own-goal / penalty support (they fail the game until a real page is captured and pinned with its own ticket); any change to the backend beyond the constant flip and the tests it breaks; resetting attempt counts; per-game minutes; anything about the opponent beyond name and score; a scheduled refresh; any change to the route rule, timeouts, pauses, cap, or retry count.

## Verify against reality, not memory

Check against what's installed (`scraper/package.json`, `scraper/package-lock.json`, `node_modules`, the running Node), not general knowledge, and say in the summary **how** you checked each:

- Node 24.x (`node --version` with the Node you actually run tests on; the shell's default may be 22, as in KAN-75), and `node --test` behaviour you rely on.
- Cheerio's resolved version (lockfile) and that its API gives you child nodes **including text nodes** in order, and attribute values with single-quoted attributes as in the fixtures. Prove it with the parser tests, not prose.
- Asia/Jerusalem conversion: that Node's `Intl` has full time-zone data here (`Intl.DateTimeFormat().resolvedOptions()`, a known DST date). Israel's DST ends on **2026-10-25**: test a 24 h boundary on both sides of it.
- Playwright resolved version unchanged (lockfile, `npx playwright --version`); no install, no lockfile change unless a test proves it's needed (then say why).
- Backend: run with the repo's Maven wrapper and `JAVA_HOME` = Temurin 21 (Spotless crashes on JDK 25).

## Read first

`CLAUDE.md` (standing rules, especially 4, 5 and 7), then only:

- `scraper/docs/KAN-75-findings.md` (whole file); `scraper/test/fixtures/README.md` and the four `game-*.html` fixtures (structure only)
- `docs/scraper-backend-contract.md` ("Trigger", "Result", "Game details part", "What the backend does", the examples)
- `scraper/src/`: `scrape.js`, `server.js`, `contract.js`, `backendClient.js`, `parseFixtures.js`, `text.js`, `browserSource.js` (don't change it), `queue.js` (don't change it), `log.js`, `clock.js`
- `scraper/test/`: `helpers.js`, `scrape.test.js`, `scrapePlayerStats.test.js`, `server.test.js`, `contract.test.js`, `parsers.test.js` (style)
- `scraper/README.md`
- Backend: `scrapingintegration/ScrapeResultRequest.java` (the `gameDetails` records and checks), `ScrapeTriggerService.java`, `ScraperClient.java`, `ScraperClientTest.java`, the trigger tests that reference `gameDetails`
- `docs/spec.md` section 07 and the status line: to list what goes out of date, **not** to edit

## Test data

The fixtures (findings, "KAN-80" list):

| Fixture | Our side | Covers |
|---|---|---|
| `game-1122670.html` | AWAY | Dimona–Sderot: straight red after his own goal, `(C)`, `(GK) (C)`, goal at 93, subs with cards, `yellow2` on the opponent side |
| `game-1143937.html` | HOME | 0-0, empty scorers, a masked name (opponent side), lineup missing an event the timeline has (opponent) |
| `game-1122634.html` | HOME | 1-2, our goal at 78 |
| `game-1142007.html` | AWAY | cup (`גביע המדינה ווינר מחזור 4`), two goals by one player (88, 92), a `-1` minute on our substitute, a 1-player bench |

- `1142007` isn't in the team-games fixture: parse it directly with a hand-built `row`.
- Failure cases are **in-memory variants** of a fixture (string replacement in the test), not new files: `football.svg` → `own_goal.svg` in one of **our** goals; an own-goal icon in a timeline event node only; an unknown `span` class in `moves`; a header `team_id` matching neither / both; a missing `gfd_` id; `data-onlive="True"`; an `h1` without `מחזור`; a link without `player_id`; `מס' X`; an unknown suffix; a minute of `151`.
- No fixture contains an own goal or a penalty. Don't invent one as if it were real markup.

## Tests

Follow the existing style (`node:test`, `fakeClock`, `fakeSource`). At least:

**Trigger (`server.test.js`)**

- Accepted: `gameDetails: true` with and without `knownGameIds`; 200 ids; the largest legal body under 4096 bytes.
- `400` (fixed message): `knownGameIds` without `gameDetails: true`; 201 ids; a duplicate; `0`; `2147483648`; `1.5`; a string; `null`; `gameDetails: "true"`.
- Only `gameDetails: true` (other three `false`) is a valid trigger.
- The shared trigger example (decision 6) parses.

**Parser (`parseGameDetails.test.js`, new)**

- Each fixture: `ownSide`, competition, round, names, scores, player count per role, and **full expected output for at least one fixture** (`1122670`) as a literal.
- Pinned (findings): `(C)` and `(GK) (C)`; `yellow` then `yellow2` → `YELLOW` + `SECOND_YELLOW` (both real cases are on the opponent side, so pin it with an in-memory variant that copies that markup into one of our players); `red` after a `GOAL` for the same player; minute `-1` → `null`; minutes 91–98; two goals by one player; a masked name → `null` **if it appears on our side**, otherwise via an in-memory variant; a 1-player bench; links with and without `season_id`; coaches and the header row excluded; **the legend's `own_goal.svg` / `penalty.svg` don't fail any of the 4 pages**.
- Every ParseError case above, plus the row cross-check (name order swapped, score different).
- The opponent's players never appear in the output: assert no anonymised opponent name (`שחקן יריב`) and no `9000NN` id anywhere in the serialized result.
- Warnings: a variant with 10 starters, one with no `(GK)`, one where our goals ≠ score → the right `{check, expected, actual}`, and the game still parses.

**Scrape (`scrapeGameDetails.test.js`, new; fake source and clock)**

- Selection: oldest first by date and time, not by id (use the team-games fixture: `1143937`, round 5 on 01/10, has a **higher** id than `1122670`, round 6 on 09/10, yet must come first); `knownGameIds` skipped; only 2; a game < 24 h after kick-off skipped and the same game > 24 h taken; a `null` kick-off treated as 23:59; the DST boundary (2026-10-25) both sides.
- Sequence: game pages come **after** `LeagueTable`; exactly 6 requests with all four parts and 2 games; the existing parts' results identical with and without `gameDetails`.
- One game parses, one fails `PARSE_ERROR` → `OK` with one game and one failure.
- One game `500` twice → `SOURCE_ERROR` failure (and the shared retry is consumed: a later timeout isn't retried); the other game still fetched.
- Block on game 1 → failure `SOURCE_BLOCKED`, game 2 absent (not in either list), no further request.
- Block earlier (team page / stats / standings) → `gameDetails` `FAILED SOURCE_BLOCKED`, no game request.
- Team page fails / doesn't parse → `gameDetails` `FAILED` "not attempted", stats as today.
- 15 s guard: with < 15 s left, game 2 absent and not requested; with ≥ 15 s, requested.
- A game cut by the cap mid-request → `TIMEOUT` failure.
- Nothing qualifies → `OK`, empty lists, no game request.
- Contract demotion: a parsed game that breaks a contract rule (e.g. 41 players via a variant) → moved to `failures` `PARSE_ERROR`, the other game kept; a part-level violation → whole part `FAILED`.
- Logs: run the full flow with `fixtures` + `gameDetails` over the real-name fixtures and assert **no** captured log line contains any of our players' names (read them from the parsed output) or `שחקן יריב`.

**Contract**

- `contract-example-game-details` passes `payloadViolation`; `backendClient.js` logs the `gameDetails` outcome.

**Backend**

- The flipped constant and the shared trigger example (decision 6); `./mvnw spotless:apply` then `./mvnw verify` from `backend/`. Report the backend test count before (on master) and after.

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. sort the selection by `gameId` instead of date / time;
2. drop the 24 h rule (or use UTC instead of Asia/Jerusalem);
3. ignore `knownGameIds`;
4. key a goal on the `sr-only` `שער` alone (accept any icon);
5. check the legend's icons too (every fixture should then fail);
6. always pick the home side as ours;
7. drop the row cross-check;
8. demote the whole part on one game's contract violation;
9. continue to game 2 after a block;
10. drop the 15 s guard;
11. accept `knownGameIds` without `gameDetails: true`;
12. send `-1` as a minute instead of `null`.

Report each and whether it was caught.

Run `npm run lint`, `npm run format:check` and `npm test` in `scraper/` on Node 24. Report the scraper test count before (220 on master) and after.

## Live check (on Gal's machine, manual, never in CI; approved by Gal)

Only after every test is green.

Write a throwaway script **outside the repo** (a scratch folder). It calls `runScrape` with the real `createBrowserSource()` for Sderot (`teamId 7617`, `leagueId 62`, `seasonId 28`) with **all four parts** and `knownGameIds: []` (so it picks the 2 oldest qualifying games), and prints only:

- each part's `status`, `reason`, `detail`, and the row / player counts;
- per fetched game: `gameId`, `ownSide`, competition and round, our player count per role, event counts per kind, the warnings, and any `failures` entry;
- the request count and the total time.

**No player names.** **Don't post to the backend.**

**Limits:** **one** run, at most 6 requests (team games, stats, league, table, 2 games, plus the shared retry if it happens). Stop at once on any block and report it. If one of the two games has a fixture (e.g. `1122634`), also report whether the live parse **deep-equals** the fixture's parse (only differences in fields, never values with names).

## Docs

- **`docs/scraper-backend-contract.md`:**
  - Trigger: `gameDetails` / `knownGameIds` are now **sent** (`REQUEST_GAME_DETAILS = true`); rewrite the "defined, not sent yet" paragraph and every "not sent until KAN-80" / "requested by no trigger until KAN-80" sentence; deploy order (scraper first or together) and why.
  - "What one scrape does": up to 6 requests, the order (game pages last), selection (24 h in Israel time, `null` time as 23:59, date / time / id order, at most 2, `knownGameIds` skipped), the 15 s guard, not-started games absent, block / timeout handling per game, when the whole part is `FAILED`.
  - The scraper's per-game demotion of contract violations.
  - The `backendClient.js` sentence (`PARTS` now four).
  - The shared trigger example with its marker.
- **`scraper/README.md`:** the four parts and the sequence; the request budget (up to 6 requests + one shared retry, the 90 s cap, the 15 s guard, at most 36 requests per club per hour with the 10-minute lock); the selection rule; own goal / penalty → `PARSE_ERROR` until pinned; warnings are log-only; no names in logs; the deduplication note (decision 1).
- **Root `README.md` and `CLAUDE.md`:** only where they describe what the scraper fetches, or the status line (CLAUDE.md rule 7). Nothing else.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words) and what's wrong. I expect at least §07's game-details paragraph (anything saying details aren't requested / read yet, or KAN-80 is pending) and the status line. Add any I missed.

## Commits

Small, focused commits, for example:

- `feat(scraper): accept gameDetails and knownGameIds in the trigger (KAN-80)`
- `feat(scraper): parse our side of a game details page (KAN-80)`
- `feat(scraper): fetch up to 2 finished game pages and send the gameDetails part (KAN-80)`
- `feat(scrapingintegration): request game details from the scraper (KAN-80)`
- `docs: contract and scraper README for game details (KAN-80)` (or with the code, per rule 7)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions verified and **how** you checked each, with the findings for every item in "Verify against reality" (evidence, not assumptions).
3. The final design: trigger validation, the sequence with the exact condition for each `FAILED` / absent / `failures` case, the selection and the Israel-time computation, the 15 s guard, the parser's structure (selectors, the node walk, the timeline check), the per-game contract demotion, the backend change.
4. Raw examples from tests: one full `gameDetails` part (made-up or fixture data with names replaced by `<name>`), one with a failure and a game, and one trigger body from the backend with `gameDetails: true`.
5. Tests: the new tests (name + one line each), the mutation checks and their results, scraper and backend test counts before and after, lint / format / `verify` results (local).
6. The live check's output (as specified, no names) and the request log.
7. Docs changed, one line each.
8. Spec: every out-of-date place (section + quoted words + what's wrong).
9. Deviations from this prompt, and why.
10. Open ends and risks for KAN-81 and KAN-76 (including any check you thought of but didn't add).
11. Print in full, at branch HEAD:
    - `parseGameDetails.js`;
    - the selection and game-page step in `scrape.js`;
    - the `gameDetails` validation and per-game demotion in `contract.js`;
    - the trigger changes in `server.js`.
