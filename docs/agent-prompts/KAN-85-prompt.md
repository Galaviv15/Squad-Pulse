# KAN-85: Scraper: fetch and parse the State Cup list, send the cup part, take played cup games into game details

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `bfe02cc`, the KAN-84 merge, PR #63). If it doesn't, stop and report. Then create the branch `feature/KAN-85-cup-games-scraper`.

## Context

Jira KAN-85, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-76 (done: the cup-games spike) and KAN-84 (done: the backend stores the `cupGames` part, PR #63). KAN-86 (frontend) is still to do.

KAN-84 gave the backend a fifth result part, `cupGames`, and a trigger property `cupGames` that the backend **doesn't send yet** (`ScrapeTriggerService.REQUEST_CUP_GAMES = false`). Today's scraper rejects `cupGames` as an unknown trigger property. This ticket teaches the scraper to: accept `cupGames`, load the team page for our full club name, fetch the State Cup list in-page, parse it, send the `cupGames` part exactly as the contract says, and feed played cup games into the game-details selection.

**The source of truth for the payload is `docs/scraper-backend-contract.md`, section "Cup games part (KAN-84)", and `backend/.../scrapingintegration/ScrapeResultRequest.java` (`CupGamesPart`, `CupCompetitionPart`, `CupGame`).** Where the KAN-76 findings' "Proposed cup part" or the Jira description differ from the contract (raw strings for extra time / penalties, Toto Cup, +3 requests), **the contract and this prompt win**.

Read:

- `scraper/docs/KAN-76-findings.md`: **only** "Decided for KAN-84 / KAN-85 / KAN-86", §1, §2, §3, §6 and "Proposed scrape sequence".
- `docs/scraper-backend-contract.md`: "Trigger: backend → scraper", "Cup games part (KAN-84)", "Game details (KAN-80)" paragraph, "Run status".
- `scraper/src/`: `scrape.js`, `browserSource.js`, `asmx.js`, `selectGames.js`, `parseGameDetails.js` (the `row` checks), `contract.js`, `server.js` (`parseTrigger`), `backendClient.js` (the `PARTS` logging), `text.js`.
- `scraper/test/`: `helpers.js` (`fakeSource`, `fakeClock`, `contractExample`), `scrape.test.js`, `scrapeGameDetails.test.js`, `contract.test.js`, `server.test.js`; `scraper/test/fixtures/README.md`.
- `backend/.../scrapingintegration/ScrapeResultRequest.java`: the three cup records only (lines ~370–525).

## Decisions (agreed with Gal; not up for change in this ticket)

1. **The backend constant stays `false` in this PR.** `ScrapeTriggerService.REQUEST_CUP_GAMES` is **not** flipped here (the Jira description says otherwise; this prompt wins). Reason: flipping it puts cup games into `GET /matches/fixtures`, and the frontend that labels them (KAN-86) isn't done. The flip will be the last step of KAN-86's PR. So in this PR: **no backend production change at all.** Prove the end-to-end path locally with the constant flipped (see "Verify end to end"), then revert that change before committing.
2. **State Cup only.** Only `GetTeamNatCupData?team_id={team}&season_id={season}` (snake_case). **No Toto call** (no Toto Cup for Liga Alef and below). The competition is `STATE_CUP`.
3. **Our side:** load the team page `/team-details/?team_id={team}&season_id={season}` (a document) on every scrape with `cupGames`; `ownName` = the trimmed text of `h1.page_main_title span.big` (exactly one, non-empty, else the part `FAILED PARSE_ERROR`). Each cup row's `ownSide`: exactly one of home / away **equals** `ownName` (exact string equality after the same trimming as the names) → `HOME` / `AWAY`; neither or both → that competition `FAILED PARSE_ERROR` (never a guess, never a fuzzy match).
4. **If the team-games page didn't parse but the cup list did**, game details stay `FAILED PARSE_ERROR` "not attempted: the team games page didn't parse (…)", exactly as today. Don't select from cup rows alone.
5. **A shootout on a game page:** don't extend `parseGameDetails`'s event parsing in this ticket. Pin today's behaviour with the captured page (below) in a test, whatever it is. If it throws, that's a `PARSE_ERROR` for that game (counted, given up after 3), and you report it so Gal can open a follow-up ticket.

## Step 1: Live check and fixture capture (before any production code)

The NatCup call **from the team page** is unverified (KAN-76 made every cup call from the team-games page). The penalties cell's format is unverified. A preliminary round's game-page `h1` was never seen. These are settled by capture, not by guessing.

**Policy** (as in KAN-70 / KAN-75 / KAN-76): headless Chromium, User-Agent `SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)` (as in KAN-76), locale `he-IL`, **the production route rule** (same-origin documents; a `fetch` only when its URL equals the exact URL about to be fetched; everything else aborted), `fetchInPage`'s headers exactly (`x-requested-with: XMLHttpRequest`, nothing else), `waitUntil: "domcontentloaded"`. Random 3–5 s between requests within a run; **at least 60 s between runs (check the clock before each run)**. **Hard cap: 9 requests to football.org.il in total.** Scripts live in a scratch folder outside the repo; nothing installed, no lockfile or `node_modules` change. A `403` or a challenge: stop at once and report.

| Run | Requests | Why |
|---|---|---|
| 1 | team page `team_id=7617&season_id=28` → `GetTeamNatCupData?team_id=7617&season_id=28` **in-page from that team page** | Proves the call from the team page (decision 3). The answer must equal the KAN-76 envelope (`<string>`-wrapped JSON); compare its `Games` with `natcup-7617-s28.xml`. |
| 2 | team page `team_id=2194&season_id=28` → its NatCup in-page | Extra-time row, game `1123493` (list result `2-2`, extra-time cell `2-3`, away-first, per KAN-84). Also: `ownName` on a second club. |
| 3 | team page `team_id=2338&season_id=28` → its NatCup in-page | Penalties row, game `1123518` (teams `2338` / `1854`). **The penalties cell's format.** If `2338`'s list doesn't contain `1123518`, use `1854` instead, still within the cap. |
| 4 | game pages `1123491`, `1123493`, `1123518` | `1123491` = Sderot's preliminary-round cup game (`ה' - ליגות א' דרום`): **its `h1`**. It will be the first cup game the scraper selects for details. `1123493`: extra time on a game page. `1123518`: a shootout on a game page. |

**If run 1's NatCup call from the team page fails** (any status ≥ 400, a block, a different envelope): **stop and report.** Don't fall back to the team-games page on your own; the sequence then needs a new decision.

**If the penalties cell isn't two integers `a-b`** (or you can't tell its order from the game page, which shows home first): **stop and report** what you saw, verbatim. Don't write a parser for a format you're guessing.

**If `1123491`'s `h1` doesn't match `^(.+) מחזור (\d+)$`:** don't change the `h1` parsing. Commit the fixture, pin "this game fails `PARSE_ERROR`" in a test, and report it (it will be given up after 3 attempts; Gal decides on a follow-up).

**Fixtures** (`scraper/test/fixtures/`, documented in its README in the existing style: source URL, date and time, setup, what was trimmed):

- `natcup-2194-s28.xml`, `natcup-2338-s28.xml` (or `-1854-`): the bytes as received, nothing trimmed (like `natcup-7617-s28.xml`). If run 1's body differs from `natcup-7617-s28.xml`, save it as `natcup-7617-s28-teampage.xml` and report the difference.
- `team-7617-s28.html` (and the other two team pages, only if a test uses them): trimmed to the `h1.page_main_title` in a minimal `<html lang="he" dir="rtl">` document.
- `game-1123491.html`, `game-1123493.html`, `game-1123518.html`: trimmed like the other `game-*.html` files. **Anonymise every player and coach of a side that isn't Sderot (`7617`), as the fixtures README describes**; in `1123493` / `1123518` neither side is Sderot, so both sides are anonymised. Team names and ids, score, `h1`, events and minutes stay real.

Report in the summary: each request (time UTC, path, status, size, ms), the gaps between runs, the exact markup of the extra-time and penalties cells (both the empty and the filled form), and each `h1` you captured.

## Step 2: The trigger (`scraper/src/server.js`)

- `cupGames` becomes an **optional** property: absent = `false`; present → must be a boolean (`null` is a `400`), like `playerStats`.
- "Nothing to scrape" counts **five** parts (`cupGames` alone is a valid trigger). Keep the error messages fixed texts.
- The job always carries `cupGames` as a boolean. The "trigger accepted / deduplicated" log line gains `cupGames`.
- Update the doc comment of `parseTrigger`.

## Step 3: The cup reader and parser (new module, e.g. `scraper/src/parseCupGames.js`)

**Reader** (a sibling of `asmx.js`'s `loadHtmlData`, not a change to it): load as XML; require exactly one root `string` element; `JSON.parse` its text; require an object with a `Games` key whose value is a string or `null`. Anything else → `ParseError` (message names the endpoint, never a value from the page). `Games: null` → an empty list (`OK`, no games). A non-`null` `StandingTable` or `Comments` → log at INFO that it's present (never its content), don't fail.

**Team page:** `ownName` from `h1.page_main_title span.big` (decision 3).

**Rows**, from `Games`: every `a.table_row` inside `div.table_view` except the header row (`table_header_row`, skipped by class). Each row must have exactly **8** `div.table_col` children; read **by position, never by the `sr-only` label** (the first two labels are swapped):

| # | Field | Rule |
|---|---|---|
| 0 | `round` | the cell's text **without** its `sr-only` label, trimmed; 1–100 chars |
| 1 | `date` | `dd/MM/yyyy` → `yyyy-MM-dd`, a real date (reuse what `parseFixtures.js` does) |
| 2 | home / away | the cell's `span.team-name-text` elements: **exactly 2**; strip a trailing `U+00A0` + hyphen from the first (regex `/ -\s*$/`), trim both; **never split on `-`** |
| 3 | `venue` | empty → `null` (as `parseFixtures.js`) |
| 4 | `kickoffTime` | `HH:mm`; `00:00` → `null` |
| 5 | result | empty → both scores `null`; else `a-b` **away-first** → `homeScore = b`, `awayScore = a` |
| 6 | extra time | same rule as the result: **away-first → swap** to `extraTimeHomeScore` / `extraTimeAwayScore`; cumulative after extra time |
| 7 | penalties | as pinned in Step 1; swap to home first like the others |

- **A cell's `sr-only` label is never a value.** Read each value cell's text **without** its `sr-only` child (e.g. remove the `.sr-only` element on a clone, or read the remaining text nodes): an empty cell holds only its label (`הארכה`, `ב.הכרעה`). Empty → `null` pair. A cell that isn't `^\d{1,2}-\d{1,2}$` after trimming → that competition `PARSE_ERROR`, never a guess. Check whether the result cell has the same label structure and handle it the same way.
- `gameId` from the row's `href` (`game_id` query parameter): an integer ≥ 1, or `null` if there's no link or it's `-1` (unseen, but the contract allows it).
- `ownSide` per decision 3.
- **Don't dedupe or reject duplicate rows in the scraper.** A duplicate key is the backend's `DUPLICATE_GAME` for that competition (a `400` would cost the whole request; the backend deliberately handles it per competition).
- **Don't enforce cross-field "obvious" rules** (extra time ≥ the 90-minute score, an unequal shootout). The contract deliberately doesn't. Only what the contract checks.
- Any row that doesn't fit → `ParseError`, which fails **that competition** (not the part, not the scrape).

## Step 4: The sequence (`scraper/src/scrape.js`)

Order: team-games page → player stats → league page → `LeagueTable` → **team page → `GetTeamNatCupData` (in-page, from the team page)** → game pages. **At most 8 requests per scrape** (+ the one shared retry).

- **Guard:** start the team page only if at least `gameMinRemainingMs` (15 s) of the cap remain (same constant or a sibling with the same value, named).
- Each URL through `new URL(…).href` (as the stats and `LeagueTable` already are). Team page: `navigationTimeoutMs`; NatCup: `fetchTimeoutMs`. Both go through `request()`: same pauses, the same single retry, the same cap, the same block handling.
- **No change to `browserSource.js`'s route rule.** The team page is a same-origin document (already allowed); the NatCup call goes through `allowedFetch`, which allows exactly the URL being fetched. If you find you need to change the route rule, stop and report why.
- **Part and competition outcomes** (the contract's "the part is `FAILED` only when the team page failed"):

| Situation | `cupGames` |
|---|---|
| not requested | `null` |
| browser failed to start | part `FAILED SOURCE_ERROR` "browser failed to start" (like the others) |
| an earlier request was blocked | part `FAILED SOURCE_BLOCKED` "not attempted: an earlier request was blocked" |
| < 15 s of the cap left | part `FAILED TIMEOUT` with a "not attempted: …" detail |
| team page failed (timeout, 4xx/5xx, network, block) | part `FAILED` with that reason |
| team page `h1` doesn't parse | part `FAILED PARSE_ERROR` |
| NatCup request failed | part `OK`, `fetchedAt`, `competitions: [{STATE_CUP, FAILED, reason, detail}]` |
| NatCup blocked | as above with `SOURCE_BLOCKED`; then the scrape stops: game details `FAILED SOURCE_BLOCKED` "not attempted" (the existing `blocked` path) |
| NatCup doesn't parse (envelope, row, `ownSide`, a score cell) | part `OK`, `STATE_CUP` `FAILED PARSE_ERROR` |
| parsed | part `OK`, `STATE_CUP` `OK` with `games` (0–50; empty is valid) |

- `fetchedAt` of an `OK` part = the NatCup response time if it arrived, else the team page's (as the other parts use their last response).
- Logs: game ids, counts, reasons and statuses only. **Never a team name, a round or `ownName`** (as the game details do today).
- Update the header comment of `scrape.js` (order, "at most 8 requests") and the `runScrape` JSDoc (`cupGames`).

## Step 5: Game details for cup games (`selectGames.js`, `parseGameDetails.js`, `scrape.js`)

- Played cup games join the selection **only if the State Cup list parsed in this scrape** (competition `OK`). Otherwise the selection uses the league rows alone, as today.
- `selectGames` gets league rows + cup rows together: oldest first, at most 2 across all competitions, the same 24 h rule, the same `knownGameIds` exclusion. A cup row needs `gameId`, `date`, `kickoffTime`, `homeScore` / `awayScore` (the **90-minute** score, home first). If the same `gameId` appears in both lists (not seen: the team-games fixture has no cup game), keep it **once** and log it at WARN with the id. Prove the "once" with a test.
- **The row cross-check in `parseGameDetails`:** a league row stays exactly as today (names + scores). A **cup row** is checked by **`ownSide`** (the page's side by the header `team_id`, already computed) **equal to the row's `ownSide`**, and both scores equal the row's 90-minute scores. **No name comparison** for a cup row (the list has full names, the page short ones). Make the row's kind explicit (e.g. a `kind: "LEAGUE" | "CUP"` on the selected row, or a separate parameter), not inferred from which fields happen to be present. The game page's main score is the 90-minute score (KAN-84: `1123493` shows `2 : 2`, `הארכה : 3 : 2`): prove it with the `1123493` fixture.
- The `GameDetails` sent is unchanged in shape: the page's short names, `competition` / `round` from the page's `h1`, `date` / `kickoffTime` from the row.

## Step 6: The contract check (`scraper/src/contract.js`) and logging

- Add `cupGames` to `PARTS` (and so to `TOP_KEYS`). `payloadViolation`'s "at least one part" counts five.
- `validateCupGames` mirrors `ScrapeResultRequest.CupGamesPart` / `CupCompetitionPart` / `CupGame` **rule by rule** (read the Java; it is the source of truth): part status consistency; `competitions` 1–5, none `null`, each competition at most once, only `STATE_CUP`; per competition `OK` needs `games` (0–50, none `null`), `FAILED` needs a known `reason`; every `CupGame` field present; `gameId` `null` or 1..int max; `round`, names 1–100 after trimming, no control characters, names differ; `date` strict `yyyy-MM-dd`; `kickoffTime` `null` or strict `HH:mm`; `ownSide` `HOME`/`AWAY`; `venue` `null` or 1–200; each score pair 0–99, both or neither; extra time or penalties need the 90-minute scores.
- **Demote per competition, not per part.** An `OK` competition whose games break a rule becomes that competition's `FAILED PARSE_ERROR` "contract check: <rule>"; the part and the other parts stay. Only a **part-level** violation (e.g. a duplicate competition, a broken part shape) demotes the whole part to `FAILED PARSE_ERROR`. Reason: a violation reaching the backend is a `400` for the **whole** request, which would lose the league fixtures, standings, stats and game details too.
- `backendClient.js`: it logs each part's outcome from `PARTS`, so `cupGames` is picked up; also log each competition's `outcome` / `reason` from the response's `cupGames.competitions` (ids and enum values only). Check the response parsing tolerates `cupGames` (it ignores unknown fields today; confirm).

## Verify against reality, not assumptions

Check what's actually installed before relying on behaviour: Node, Playwright and Cheerio from `scraper/package.json` **and** `scraper/package-lock.json` (resolved versions), and the Node the tests run under (`engines: >=24`; KAN-76 notes the shell's default may be 22 — use 24, and say which). Confirm with evidence (a test, or the installed version's own docs), not from memory:

1. how Cheerio (installed version) loads the `<string>` XML and returns its text: that the JSON's `<` escapes come out of `JSON.parse` as `<`, and that `&nbsp;` inside `Games` becomes `U+00A0` after `load(Games)`. Pin both with the real fixture bytes.
2. that removing / excluding the `sr-only` child leaves exactly the value text for an empty and a filled score cell (a test on the captured fixtures, both forms).
3. that `page.evaluate` + `fetch` from the **team page** works under the production route rule (Step 1, run 1), and that the request the route handler sees has `resourceType() === "fetch"` and a URL equal to `new URL(…).href` (as the existing fetches rely on).

Report each finding and how you checked it.

## Tests (no live site in CI)

All against fixtures and `fakeSource` / `fakeClock`. At least:

- **Parser:** `natcup-7617-s28.xml` → 2 games: `1123491` `ownSide` `HOME`, home 1 away 0, round `ה' - ליגות א' דרום`; `1142007` `AWAY`, home 1 away 4, round `ו`; venue, kick-off, date; extra time / penalties `null` (the label isn't read as a value). The extra-time row from `natcup-2194-s28.xml` (home-first swap of both pairs). The penalties row from the Step 1 fixture. `totocup-7617-s28.xml`'s envelope as a synthetic "`Games: null` → empty `OK`" case for the reader (Toto isn't fetched; the file only exercises the reader). Synthetic, built from the real fixture: a name containing a hyphen; a row with neither / both names equal to `ownName` → `PARSE_ERROR`; a row with 7 cells; a non-number score cell; a `-1` / missing link → `gameId` `null`; a future row (empty result → scores `null`); a non-`null` `StandingTable` (logged, not failed); a broken envelope (no `string`, invalid JSON, no `Games` key).
- **Team page:** `ownName` from the fixture; zero / two `span.big`, empty → `PARSE_ERROR`.
- **Sequence:** the exact order and URLs with all five parts (8 requests: team-games, stats, league, table, team page, NatCup, 2 game pages); `cupGames` only (2 requests); each row of the outcome table in Step 4 (incl. the 15 s guard, a block on the team page and on NatCup, a team page `h1` parse failure, a NatCup parse failure that leaves every other part `OK`); a cup failure never changes fixtures / standings / stats; one shared retry across the cup step and the rest.
- **Game details:** cup rows join the selection (oldest first across competitions, max 2); a failed cup list → league rows only; a `gameId` in both lists → once; `game-1142007.html` against its cup row passes by `ownSide` + score with **different** names; a cup row with the wrong `ownSide` or score → `PARSE_ERROR`; a league row still fails on a name mismatch (unchanged); `1123493`'s page against its cup row (90-minute score); `1123491` and `1123518` pinned as captured (decision 5, Step 1).
- **Contract:** each `CupGame` rule; a violating game demotes **only** its competition; a part-level violation demotes the part; the contract doc's cup example (`contract-example-cup-games`) passes `payloadViolation` unchanged (use `contractExample("contract-example-cup-games")`).
- **Trigger:** `cupGames` absent / `true` / `false` / `null` / a string; `cupGames` alone accepted.

Mutation check: break, one at a time, the away-first swap of the extra-time cell, the `sr-only` exclusion, the `ownSide` equality, the per-competition demotion, and the cup row cross-check, and confirm a test fails for each. Report each.

## Verify end to end (local only, not committed)

With the backend running locally (docker-compose as in the README), flip `REQUEST_CUP_GAMES` to `true` **in your working copy only**, trigger one refresh for a local club mapped to Sderot (7617 / 62 / 28, set up as for the earlier end-to-end checks) against the real site, and report: the trigger body (`"cupGames": true`), the scraper's log lines (request count ≤ 8, statuses), the backend's response outcomes for `cupGames` and `STATE_CUP`, the run status, and `GET /matches/fixtures` showing the two State Cup games with `competition` / `round`. This is **one** live scrape (≤ 8 requests), at least 60 s after Step 1's last request, and counts toward nothing else. **Then revert the constant** and confirm `git diff` shows no backend change. If running the full stack isn't possible, say so and skip it; don't fake it.

## Docs (same commits as the code, CLAUDE.md rule 7)

- `scraper/README.md`: the sequence, "at most 8 requests per scrape" (9 with the retry), 48 per team per hour with the 10-minute lock (54 with every retry; recompute and state how), the cup step, its guard, our-side rule.
- `docs/scraper-backend-contract.md`: the trigger table's `cupGames` row and the "`cupGames` (KAN-84)" paragraph (the scraper now **accepts** it; the backend still doesn't send it; the flip moves to KAN-86 with the deploy order unchanged), "What one scrape does" (8 requests, the order), the "Game details (KAN-80)" paragraph (cup rows join the selection; the cup row check by `ownSide` + score). Keep the rest of the cup section as it is unless it's now wrong.
- `scraper/test/fixtures/README.md`: the new fixtures.
- `CLAUDE.md`: its line on the scraper says "≤ 6 requests … (team games page → player statistics, …)". Update it to the new order and count.
- **`docs/spec.md`: don't edit it.** List in your summary exactly what in it is now out of date and where (KAN-76 named §07: scope, request counts, the sequence, game-detail selection).

## Commits

Conventional Commits with the key, e.g. `test(scraper): add State Cup and cup game-page fixtures (KAN-85)`, `feat(scraper): parse the State Cup list and send the cup part (KAN-85)`, `feat(scraper): take played cup games into game details (KAN-85)`. `npm test`, `npm run lint` and `npm run format:check` in `scraper/` pass before each commit. **Don't push. Don't open a PR.** Don't commit this prompt (Gal does that with the PR).

## Summary to report

Short and factual: the branch and commits (hashes); Step 1's requests, gaps and findings (envelope from the team page; extra-time and penalties cell markup; the three `h1`s; the shootout page's parse result); each "verify" finding and how; files changed; test counts before → after (`npm test`); the mutation checks; the end-to-end check's results (or why it was skipped); what in `docs/spec.md` is now out of date; anything you were unsure of or didn't do.
