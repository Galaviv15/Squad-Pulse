# KAN-73: Scraper: parse the team's player-statistics table and send it with the scrape result

**Credit budget is tight.** Read only the files this prompt names, plus whatever you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Check that local `master` equals `origin/master`: `git rev-parse master origin/master` must print the same hash, and it must be `99623d7` (the KAN-72 merge, PR #54) or later. If it isn't, stop and report. Otherwise create the branch `feature/KAN-73-scraper-player-stats`.

The scraper needs Node ≥ 24 (`scraper/package.json` `engines`). Gal's shell defaults to Node 22. Check `node --version` before running anything in `scraper/`, and switch to 24 if needed.

## Context

This is Jira KAN-73, in epic KAN-14 ("Match & Scraping Integration"). It was blocked by two tickets, both done:

- **KAN-70:** the player-statistics spike. Findings are in `scraper/docs/KAN-70-findings.md`, the fixture is `scraper/test/fixtures/team-players-stats-7617-s28.xml`.
- **KAN-72:** the backend side. PR #54 added the `playerStats` part to `POST /internal/scrape-results`, the storage, the links and the run status with three parts.

KAN-73 in turn blocks KAN-74 (the frontend screen) and, indirectly, KAN-77.

football.org.il publishes one table per team and season, with every registered player's season totals across all competitions. This ticket teaches the scraper (KAN-67) to:

1. fetch that table,
2. parse it,
3. send it as the third part, `playerStats`.

It also turns the request on in the backend. KAN-72 left the backend sending without `playerStats` (`ScrapeTriggerService.REQUEST_PLAYER_STATS = false`) because the scraper rejects unknown trigger properties. Both sides change **in this PR**.

The contract is **fixed** by KAN-72. Its source of truth is `docs/scraper-backend-contract.md` ("Player statistics part") and `ScrapeResultRequest.java`. Do not change the backend's ingest rules. If the scraper can't meet one, stop and report.

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. The trigger: `playerStats` is an optional boolean

- `server.js` `parseTrigger` accepts a seventh property, `playerStats`. It is **optional**: absent means `false`.
- If present, it must be a boolean. `null` or any other type is a `400` with a fixed message, like the other booleans.
- The six existing properties stay required, and other unknown properties are still rejected.
- The returned job always has `playerStats` as a real boolean.
- Why optional: the backend leaves `false` out of the JSON (`@JsonInclude(NON_DEFAULT)` on `ScraperClient.TriggerRequest`). A required property would make the scraper and the backend depend on deploy order.
- The "nothing to scrape" rule counts all three parts. Update its message.
- Queue identity (`queue.js` `keyOf`) is unchanged: same team, league and season means the same scrape.

### 2. The request sequence: team games → **stats** → league page → `LeagueTable`

This is the KAN-70 findings §3 sequence. It adds one request per scrape (4 instead of 3).

**The team-games page.** It is loaded if `fixtures` **or** `playerStats` is requested. If only `playerStats` is requested, the page is loaded but not parsed, and `fixtures` stays `null`.

**The stats fetch.** It is an in-page `fetch` from that already-loaded page, through the existing `request("fetch", …)`. It uses the same pause, the same 20 s `fetchTimeoutMs`, the same shared single retry, the same 90 s cap and the same block handling. Build the URL exactly like this, so it equals, character for character, the URL the browser reports, which the exact-match route rule compares against:

```js
new URL(
  `${origin}Components.asmx/GetTeamPlayersStatisticsList?teamId=${teamId}&seasonId=${seasonId}&isFemale=${encodeURIComponent('"False"')}`,
).href
```

Note the camelCase `teamId` / `seasonId`, unlike `LeagueTable`. `isFemale` is the constant `"False"` (KAN-70 §1), which means men's teams only. Document that limitation; don't solve it.

**`browserSource.js` route rule: no change.** `fetchInPage` already allows exactly the URL it fetches. Never allow `fetch` / `xhr` in general.

**No re-navigation.** The stats fetch runs only if the team-games request returned a response that is not blocked and has status < 400. A parse failure of the fixtures does **not** stop it, because the page is loaded. If the team-games request failed (timeout, 4xx, 5xx, network error, cap reached), the stats are **not attempted**:

- `playerStats` = `FAILED` with the **same reason** as that failure.
- `detail` = `not attempted: the team games page didn't load (<that failure's detail>)`, trimmed to the contract's limit by `failedPart`.
- There is no second navigation to the page. The existing single retry for that request still applies as today.

**Block.** A block anywhere stops the scrape, as today. Every requested part not yet attempted is `FAILED SOURCE_BLOCKED` with the existing `NOT_ATTEMPTED_BLOCKED` detail. That includes stats after a blocked team page, and standings after a blocked stats fetch.

**Other failures.** A stats failure that isn't a block (timeout, 5xx after the retry, parse error) fails `playerStats` only. Fixtures and standings are still sent as they are. Standings are still attempted afterwards, as today after a fixtures failure.

**`fetchedAt`** is the time of the stats response, like `LeagueTable`.

**The shared retry.** It stays one per scrape. A stats timeout that uses the retry leaves none for the standings. That is accepted; document it in the scraper README.

**The browser fails to start.** Every requested part, `playerStats` included, is `FAILED SOURCE_ERROR "browser failed to start"`.

### 3. The parser: `src/parsePlayerStats.js`, `parsePlayerStats(xml)`

**Envelope.** Reuse the `LeagueTable` envelope reading: `load(xml, { xml: true })`, exactly one `HtmlData`, then `load(htmlData.text())`. Extract it into a small shared helper and call it from both parsers. `parseLeagueTable`'s behaviour and its existing `ParseError` messages must not change; its tests prove that.

**Rows.** Select `a.table_row[href*="player_id="]`, in page order. Don't rely on the order for anything else.
- 0 rows is a `ParseError`.
- More than 80 rows is a `ParseError`, matching the contract's 1–80.

**Cells.** Identify each cell with the existing `readLabelledColumns` and a 10-entry `LABELS` map (findings §4):

| Label | Field |
|---|---|
| `שם השחקן` | `name` |
| `מס. משחקים` | `games` |
| `שערים` | `goals` |
| `כ. צהובים ליגה/גביע` | `yellowCardsLeagueAndCup` |
| `כ. צהובים טוטו` | `yellowCardsToto` |
| `כ. אדומים` | `redCards` |
| `הרכב פותח` | `starts` |
| `נכנס כמחליף` | `cameOnAsSub` |
| `הוחלף` | `subbedOff` |
| `דקות משחק` | `minutes` |

An unknown, missing or duplicate label is a `ParseError`; `readLabelledColumns` already does this.

**Header cross-check.** The header row (`div.table_header_row`) must exist. Its cells' `data-sort` values must be exactly these 10, in this order:

```
PlayerName, GamesCount, Goals, YellowCardsLeague, YellowCardsToto, RedCards, Opened, Substitue, OutOfGame, TotalMin
```

- `Substitue` is misspelled on the site. Keep it.
- Each row's i-th cell label must map to the same field as the header's i-th `data-sort`.
- Any mismatch is a `ParseError`.

**`playerId`.** Read it with the existing `queryParameter(href, "player_id")`. It must match `^\d{1,10}$` and be in 1..2147483647, otherwise it's a `ParseError`. A row without one is a `ParseError`. A duplicate `playerId` in the table is a `ParseError`.

**`name`.** Use the cell text after removing the `sr-only` label, normalized by `cleanText` (trim, whitespace runs to one space). Keep it exactly as shown, **surname first**. Never split it, never reorder it, never touch punctuation. An empty name is a `ParseError`.

**Numbers.** Every numeric cell must match `^\d{1,5}$` after cleaning, otherwise it's a `ParseError`. The contract's ranges are enforced by `contract.js` (decision 4), not here.

**Sanity check.** `games === starts + cameOnAsSub` per row, otherwise it's a `ParseError`. **Never** check minutes against `90 × games` (added time, extra time). Add no other cross-field check. If you think one is needed, list it under open ends.

**Output.** `[{ playerId, name, games, goals, yellowCardsLeagueAndCup, yellowCardsToto, redCards, starts, cameOnAsSub, subbedOff, minutes }]`, every field present.

**Privacy of error details.** A `ParseError` message never contains a player's **name** or **`player_id`**. Refer to a row by its 1-based index (`row 7: …`). An excerpt of a bad **numeric** cell is fine; an excerpt of a name cell is not. The contract says names are never logged. The `detail` reaches the backend and the operator, and must not leak either value.

**No photos, links or anything else** from the table: only the fields above.

### 4. `contract.js`: the local check mirrors the backend's `playerStats` rules exactly

This is the most important part of the ticket. The backend answers `400` for the **whole request** on any rule violation and writes nothing, so a bad stats part would lose the fixtures and the standings too. `enforceContract` exists to turn such a part into `FAILED PARSE_ERROR` before posting. It must do the same for `playerStats`:

- Add `playerStats` to `TOP_KEYS`.
- Add the part's keys and the player keys.
- Make `partViolation` dispatch **explicitly** by name. Today any name other than `fixtures` falls through to `validateStandings`.
- "needs at least one part" counts three parts.
- `enforceContract` covers all three parts.
- `payloadViolation` checks all three.

Mirror **every** rule `ScrapeResultRequest.java` has for `playerStats`:
- 1–80 players, none `null`;
- every field required, and no unknown property;
- `playerId` 1..2147483647 and unique in the list;
- `name` as the existing `text(…, 100)`;
- counts 0..200, `minutes` 0..20000;
- `games === starts + cameOnAsSub`.

Use **the Java code** as the source, not this list. Read it and list any difference you found in the summary. As today, the local check doesn't check `fetchedAt`'s future limit.

### 5. Logging and the backend answer

- `index.js` ("part result") and `backendClient.js` `logOutcomes` ("backend outcome") loop over all three parts.
- No log line ever contains a player name or a `player_id`. Ids of the team, league and season, counts and reasons only.
- The ingest response's new `playerStats` field (KAN-72) is now read like the other two parts.

### 6. The backend: turn the request on

- Set `ScrapeTriggerService.REQUEST_PLAYER_STATS = true`.
- Update its Javadoc and `ScraperClient.TriggerRequest`'s Javadoc: the scraper now accepts the property; it's optional on the scraper side.
- Keep `@JsonInclude(NON_DEFAULT)`. It's harmless now, and removing it isn't this ticket's job.
- Update the tests that pin the old state. At least `ScraperClientTest` asserts `REQUEST_PLAYER_STATS` is `false`; KAN-72 also had a test that the trigger body has exactly six properties.
- The run status logic (three parts, order fixtures → standings → playerStats) is KAN-72's and stays unchanged.
- Find every backend test that pins the trigger body or the requested parts, and update it to the new truth. Don't weaken a test to make it pass.
- `FakeScraper` (backend tests): check whether it validates the body. If it mirrors the real scraper's rules, mirror the new optional property.
- The backend `verify` must pass.

**Accepted consequence (Gal's decision, document it in the README, don't change it).** From now on, a refresh succeeds only if the stats are stored too. At a season start, the site's table can be empty. The parser then gives `PARSE_ERROR` (the contract needs at least 1 player), so every refresh is `FAILED` until the site registers players, even though fixtures are stored. This is an open end, not a bug to fix here.

## Out of scope

- Layer B (game details, KAN-75) and layer C (the player card).
- Cup games (KAN-76).
- Any frontend change (KAN-74).
- Any change to the ingest rules or the run status rules.
- Any change to the route rule's code.
- Women's teams (`isFemale`).
- Refreshing the fixtures. See "Live check".

## Verify against reality, not memory

Check these against what's actually installed. Say in the summary **how** you checked each, with evidence, not assumptions:

- **Node.** The version you ran with (`node --version`) against `engines`.
- **Playwright.** The resolved version from `package-lock.json` and `node_modules/playwright/package.json`. KAN-70 found 1.64.0.
- **Cheerio.** The resolved version, and that `load(…, { xml: true })` + `.text()` unescapes `HtmlData` the same way for this response as for `LeagueTable`. A parser test on the real fixture proves it.
- **The URL.** That `new URL(…).href` produces `isFemale=%22False%22`: a unit test in Node. That Chromium reports the same string to the route handler, so the exact-match rule lets the fetch through and aborts everything else: the browser integration test (below) proves it with a real headless Chromium.
- **`node:test`.** How the existing tests run and are discovered (`npm test` = `node --test`). Follow them.

## Read first

`CLAUDE.md` (standing rules, especially 7), then only:

- **Findings:** `scraper/docs/KAN-70-findings.md`, sections Summary, 1–6. `scraper/test/fixtures/README.md`.
- **Scraper source** (`scraper/src/`): `scrape.js`, `browserSource.js`, `contract.js`, `parseStandings.js`, `parseFixtures.js` (`readLabelledColumns`, `cleanText`, `queryParameter`), `text.js`, `server.js`, `index.js`, `backendClient.js`, `queue.js` (skim).
- **Scraper tests** (`scraper/test/`): `helpers.js`, `scrape.test.js`, `parsers.test.js`, `contract.test.js`, `server.test.js`, `backendClient.test.js`, `browser.integration.test.js`.
- **Docs:** `docs/scraper-backend-contract.md` (whole file), `scraper/README.md`.
- **Backend** (`backend/src/main/java/com/squadpulse/scrapingintegration/`): `ScrapeResultRequest.java` (the `playerStats` records and their checks: **the rules you mirror**), `ScraperClient.java`, `ScrapeTriggerService.java` (the constant and its use).
- **Backend tests:** `ScraperClientTest.java`, `FakeScraper.java`, `ContractExampleIntegrationTest.java`, and whatever pins the trigger body or the requested parts.
- **Spec:** `docs/spec.md`, the status line at the top, §02 (the scraper paragraph), §07 and §13. Read them to list what goes out of date, **not** to edit them.

## Test data

The fixture keeps real names and ids on purpose (Gal's decision). Parser tests use the fixture. Mutated cases are built in the test by editing a copy of the fixture's text in memory: a renamed label, a missing `player_id`, a broken sum, a non-numeric cell, a duplicate row, an empty table, a missing header. Never commit a modified fixture. Contract tests build payloads in code with **made-up** names and ids.

## Tests

Follow the existing style. No live site in any test.

### Parser (`parsers.test.js`, or a new `parsePlayerStats.test.js`)

- The fixture parses into **34** rows with **34** distinct `playerId`s, in page order.
- Exactly **7** rows have 0 games and 0 minutes, and all are kept. Pin their ids from findings §5.
- **Surname first:** player `85286`'s name is exactly `אביב גל`, not reordered. Pin one three-word name exactly too, e.g. `48702` → `אבוחצירה ירדן משה`.
- `games === starts + cameOnAsSub` holds on all 34 rows.
- **Minutes > 90 × games is accepted.** Pin `158410` (1 game, 96 minutes) and `120427` (8 games, 769).
- Pin one full row, every field.
- `ParseError` for each of:
  - an unknown label;
  - a missing column;
  - a row without `player_id`;
  - a non-numeric `player_id`;
  - a duplicate `player_id`;
  - a non-numeric count;
  - `games ≠ starts + cameOnAsSub`;
  - an empty name;
  - 0 rows;
  - 81 rows;
  - no `HtmlData`;
  - a missing header row;
  - a header `data-sort` mismatch, including `Substitue` "fixed" to `Substitute`.
- For each case, assert that the message contains **no** name and **no** `player_id` from the fixture.
- `parseLeagueTable`'s existing tests pass unchanged after the envelope helper is extracted.

### Scrape flow (`scrape.test.js`, with `fakeSource`)

- **All three parts:** 4 requests in the order team games → stats → league → `LeagueTable`, with the exact URLs. Assert the stats URL string, `%22` included. The stats request has kind `fetch` and a 20 s timeout. All three parts are `OK`.
- **Stats only:** 2 requests (team games, stats). `fixtures` and `standings` are `null`. The games page is not parsed (an unparseable games body still gives stats `OK`).
- **Stats + standings, no fixtures:** 4 requests, `fixtures` `null`.
- **Team page timeout** (after its retry), 5xx, 404 and network error: no stats request is sent; `playerStats` is `FAILED` with the same reason and the "not attempted" detail; standings are still attempted.
- **Team page 200 but fixtures parse error:** stats are still fetched and `OK`.
- **Block on the team page:** all requested parts are `SOURCE_BLOCKED`, 1 request only.
- **Block on the stats fetch:** stats `SOURCE_BLOCKED`, standings `SOURCE_BLOCKED` (not attempted), fixtures `OK`, no further request.
- **Stats timeout:** it uses the single retry; a later standings timeout then gets none.
- **Stats parse error:** `playerStats` is `FAILED PARSE_ERROR`; fixtures and standings are `OK`.
- **The 90 s cap reached before the stats:** `TIMEOUT`.
- **Browser fails to start:** all three requested parts are `SOURCE_ERROR`.
- **Backward compatibility:** the existing tests (whose `JOB` has no `playerStats`) pass **unchanged**. Say so explicitly in the summary.

### Contract (`contract.test.js`)

- The payload built from the fixtures with all three parts passes `payloadViolation`.
- **Each backend `playerStats` rule** is turned into `FAILED PARSE_ERROR` by `enforceContract`, while fixtures and standings stay `OK`. Cover: duplicate `playerId`, 81 players, 0 players, a count of 201, minutes 20001, a broken sum, a missing field, an unknown player property, a name of 101 characters, a control character in a name.
- Minutes above 90 × games stay `OK`.
- `{playerStats: null, fixtures: null, standings: null}` → "needs at least one part".
- **The contract doc's own example.** Give the player-statistics example in `docs/scraper-backend-contract.md` its own marker, `<!-- contract-example-player-stats -->`, in the existing style. Assert that the scraper's `payloadViolation` accepts it, as is done for `contract-example-request`.
- If adding the same marker to the backend's `ContractExampleIntegrationTest` is cheap (same pattern), do it. Otherwise say why not.

### Trigger (`server.test.js`)

- `playerStats` absent → job `false`.
- `true` → `true`.
- `null`, `"true"`, `1` → `400`.
- Only `playerStats: true` (fixtures and standings `false`) is accepted.
- All three `false` → `400` "nothing to scrape".
- An unknown property is still `400`.

### Backend client and logs

- `logOutcomes` logs the `playerStats` outcome. `REJECTED` is WARN, as for the other parts.
- With the memory logger, run a full scrape plus post from the fixture and assert that **no** log line contains any name or `player_id` from the fixture.

### Browser integration (`browser.integration.test.js`, real headless Chromium, local server)

- Serve the stats fixture at `/Components.asmx/GetTeamPlayersStatisticsList` (`text/xml`).
- A scrape with all three parts reaches the server with exactly 4 requests, the stats request's query exactly `teamId=7617&seasonId=28&isFemale=%22False%22`, and `playerStats` `OK` with 34 rows.
- The page's own extra resources and the site XHR (`EXTRAS`) are still aborted.

### Backend

- The tests from decision 6, updated.
- The full backend `verify` passes.

### Mutation checks

Break the code on purpose, confirm a test fails, then restore. Report each one and whether it was caught:

1. Drop the `games === starts + cameOnAsSub` check in the parser.
2. Drop the duplicate-`playerId` rule in `contract.js`.
3. Make `playerStats` required in `parseTrigger`.
4. Fetch the stats even when the team page failed.
5. Build the stats URL as a plain template string with a literal `"` (no `new URL(…).href`).
6. Widen the route rule to allow every `fetch`.
7. Put the row's name in a `ParseError` message.
8. Let `partViolation` fall through to `validateStandings` for `playerStats` again.
9. Set `REQUEST_PLAYER_STATS` back to `false`.

## Live check (on Gal's machine, manual, never in CI)

Only after every test is green.

**The scrape.** Write a throwaway script **outside the repo** (a scratch folder). It calls `runScrape` with the real `createBrowserSource()` for Sderot (`teamId 7617`, `leagueId 62`, `seasonId 28`, all three parts) and prints only:
- each part's `status`, `reason` and `detail`;
- the row counts;
- the stats count of 0-minute rows;
- whether every stats row passes the sum check.

**No names, no ids** in the output. **Don't post to the backend.**

**Limits.** At most **2** runs, at least 60 s apart. Stop at once on any block and report.

**The team-games fixture.** In the same run, check whether `parseGamesPage` succeeds on the live page and how many rows it gives. KAN-70 noted the page's size changed since KAN-64. Don't replace any fixture: the existing tests pin its data. If the live page no longer parses, stop and report what changed.

Report the results in the summary.

## Docs

- **`docs/scraper-backend-contract.md`:**
  - The trigger section: the seventh property, optional, absent = `false`, at least one of three `true`. Rewrite the "defined, not sent yet" paragraph: it's now sent, `true`.
  - "What one scrape does": 4 requests, the order, when stats are not attempted.
  - Every "not requested by the trigger yet" / "today's trigger never requests it" sentence.
  - The `backendClient.js` sentence in the Response section.
  - The new marker.
- **`scraper/README.md`:**
  - The parts and the request sequence.
  - The request budget: 4 requests, the shared single retry, the 90 s cap, at most 24 requests per club per hour with the 10-minute lock.
  - Stats need the team-games page and are never re-navigated.
  - `isFemale` is men's teams only.
  - The season-start consequence (decision 6).
  - The no-names-in-logs rule.
- **Root `README.md` and `CLAUDE.md`:** only where they describe what the scraper fetches or the status line (CLAUDE.md rule 7). Nothing else.
- **`scraper/test/fixtures/README.md`:** only if a test now uses a fixture in a new way.
- **Javadoc / JSDoc:** the new parser, the changed `runScrape` (header comment: "at most 4 requests"), `contract.js`, `parseTrigger`, the backend constant.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date: the section, a few quoted words, and what's wrong. I expect at least:
  - the status line ("The scraper part (KAN-73) and the screens (KAN-74) are next");
  - §02's scraper description ("to scrape one team's fixtures and its league's table");
  - §07 (what the scraper fetches, the number of requests, "a refresh doesn't ask for them yet" or similar);
  - §13 if it lists KAN-73.

  Add any I missed.

## Commits

Small, focused commits, for example:

- `refactor(scraper): share the ASMX envelope reader between parsers (KAN-73)`
- `feat(scraper): parse the team's player-statistics table (KAN-73)`
- `feat(scraper): local contract check for the playerStats part (KAN-73)`
- `feat(scraper): fetch player statistics after the team games page; optional playerStats trigger property (KAN-73)`
- `feat(scrapingintegration): request player statistics in every refresh (KAN-73)`
- `docs: contract, scraper README for player statistics (KAN-73)` (or with the code, per rule 7)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet; Gal commits it with the PR.

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions verified and **how** you checked each, with the findings for every item in "Verify against reality".
3. The final design:
   - the request sequence, with every branch (stats only, team page failed, blocked at each step);
   - the parser's rules as implemented;
   - every `playerStats` rule in `contract.js`, next to the Java rule it mirrors, and any difference you found;
   - the trigger change;
   - the backend change.
4. Raw examples from tests:
   - one full payload with three `OK` parts, players trimmed to 2 with **made-up** names;
   - one with `playerStats` `FAILED` "not attempted";
   - one where `enforceContract` turned a bad stats part into `PARSE_ERROR`.
5. Tests: the new tests (name + one line each), the mutation checks and their results, the test counts before and after for scraper and backend, and the lint, format and `verify` results (local). State explicitly that the existing scrape tests passed unchanged.
6. The live check results (no names, no ids).
7. Docs changed, one line each.
8. Spec: every out-of-date place (section + quoted words + what's wrong).
9. Deviations from this prompt, and why.
10. Open ends and risks for KAN-74, KAN-75 and KAN-77, including any check you thought of but didn't add.
11. Print in full, at branch HEAD:
    - `src/parsePlayerStats.js`;
    - the changed parts of `scrape.js`;
    - the `playerStats` validation in `contract.js`;
    - `parseTrigger`;
    - the changed backend constant and its Javadoc.
