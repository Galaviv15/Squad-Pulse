# KAN-86: Frontend: competition label per game, cup round, extra time / penalties, next-match card across all competitions; turn on cup games

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `0fbaa28`, the KAN-85 merge, PR #64). If it doesn't, stop and report. Then create the branch `feature/KAN-86-cup-games-frontend`.

## Context

Jira KAN-86, epic KAN-14 ("Match & Scraping Integration"). Read the Jira comments 10136, 10140, 10144 and 10148 in the summary below; they're folded into this prompt.

- **KAN-84** (done, PR #63): the backend stores cup games (State Cup only, no Toto) and merges them into `GET /matches/fixtures`. Each game now carries `competition` (`LEAGUE` / `STATE_CUP`), `round` (the cup list's free text, `null` for league), `extraTime` / `penalties` (`{ownScore, opponentScore}` from our side, or `null`) and `decidedBy` (`EXTRA_TIME` / `PENALTIES` / `null`). `ownScore` / `opponentScore` stay the **90-minute** score. `result` = **who went through** (shootout, else extra time, else 90 minutes; `null` while not played and for a level shootout, which is bad data). `GET /matches/games/{id}` carries the same `result` / `extraTime` / `penalties` / `decidedBy` for a cup game found in the current cup snapshots. One rule for both: `match/MatchOutcome.java`.
- **KAN-85** (done, PR #64): the scraper fetches the State Cup list and sends the `cupGames` part, and played cup games join the game-details pick. It accepts the trigger property `cupGames` but **the backend doesn't send it yet**: `ScrapeTriggerService.REQUEST_CUP_GAMES` is `false`.
- **This ticket** teaches the frontend to show cup games, adds two fields to the game-details read, and, **as its last commit**, flips `REQUEST_CUP_GAMES` to `true`. Cup games must never reach `GET /matches/fixtures` while the frontend can't label them.
- Related, **out of scope**: KAN-82 (penalty-goal / shootout game pages fail as `PARSE_ERROR`, so such games end up `UNAVAILABLE`), KAN-87 (regional cup), KAN-83 (link style), KAN-78 (Node 24 frontend tests).

The backend is the source of truth for the shapes: read `match/FixturesResponse.java`, `match/GameDetailsResponse.java`, `match/MatchOutcome.java`. Don't copy shapes from this prompt.

## Facts found before this prompt (checked on `0fbaa28`; re-check, don't trust blindly)

1. `frontend/src/lib/matches/types.ts`: `Game` has none of `competition`, `round`, `extraTime`, `penalties`, `decidedBy`. `PlayedGameDetails` has none of `extraTime`, `penalties`, `decidedBy`, and types `result` as `Result` (never `null`), but the backend can send `null` (level shootout).
2. "Played" is decided from `result` in **two** places: `gameStatus` in `lib/matches/games.ts`, and `played` in `Scoreline` (`components/matches/GameRow.tsx`). A played game with `result: null` would show as upcoming / "no result", could become the next-match card, and its scores would be hidden.
3. **The cup round differs by source, with no fixed mapping.** From the fixtures on master (`scraper/test/fixtures/natcup-7617-s28.xml`, `game-1123491.html`, `game-1142007.html`):

   | Game | Cup list round | Game page `h1` |
   |---|---|---|
   | `1123491` | `ה' - ליגות א' דרום` | `גביע המדינה ווינר מחזור 2` |
   | `1142007` | `ו` | `גביע המדינה ווינר מחזור 4` |

   The page's competition includes the sponsor (`ווינר`).
4. `GameDetailsResponse` has `competition` (the `h1` text) and `round` (an `int` from `מחזור N`), but no cup round and nothing saying it's a cup game: a cup game decided in 90 minutes looks exactly like a league game. `GameDetailsReadService.outcome(...)` already finds the cup snapshot's game with the same `gameId`.
5. Sderot's two real cup games were decided in 90 minutes, so the extra-time / penalties display can only be proven by tests.

## Rules (from the ticket and earlier decisions; not up for change)

- **No football.org.il link, id, photo or logo** anywhere (spec §09). No `gameId` in any response.
- Hebrew / RTL, every string through i18n (CLAUDE.md rule 6), the existing test rules (MSW, console guard, unmocked-request guard).
- **Scores are never one string** like `"3–1"`: each score is its own element in home → away DOM order (the `Scoreline` comment explains the RTL reason). This applies to the extra-time and penalties scores too.
- Every time and "today" in `Asia/Jerusalem` through `lib/matches/israelTime.ts`.

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Backend: two additive fields on `GET /matches/games/{id}`

- `GameDetailsResponse` gains:
  - `competitionType`: `FixturesResponse.Competition` (`LEAGUE` / `STATE_CUP`) or `null`. `STATE_CUP` (etc.) when the game is found in the club's current cup snapshots; otherwise `null` (a league game, or a cup game no longer in the current snapshots: we can't tell them apart reliably, and we don't guess from the `h1` text).
  - `cupRound`: the cup list's round string for that game, `null` otherwise.
- Take both from the lookup that already exists in `GameDetailsReadService.outcome(...)`: one query, not a second one. Refactor it to return the matched cup game together with its snapshot's competition (e.g. a small private record), then build `MatchOutcome` from it as today. `Competition.of(CupCompetition)` already maps the enum.
- Additive only: no field removed or renamed, `round` / `competition` unchanged. Javadoc on the new params, in the style of the existing ones.
- Tests: a league game → both `null`; a cup game in the current snapshot → `STATE_CUP` + its round string; a cup game whose snapshot no longer has it → both `null` (and `result` from the page's score, as today); club isolation of the cup lookup unchanged (it already uses `ClubContext`: prove another club's cup snapshot with the same `gameId` is never used, if no test covers that yet). Update the contract / API doc where `GET /matches/games/{id}` is described (search `docs/` and `README.md` for it).

### 2. Types (`lib/matches/types.ts`)

- `COMPETITIONS = ["LEAGUE", "STATE_CUP"] as const`, `DECIDED_BY = ["EXTRA_TIME", "PENALTIES"] as const`, a `Score` type `{ ownScore: number; opponentScore: number }`.
- `Game` gains `competition`, `round: string | null`, `extraTime: Score | null`, `penalties: Score | null`, `decidedBy: DecidedBy | null`, with comments matching `FixturesResponse.Game` (the 90-minute score; `result` = who went through).
- `PlayedGameDetails` gains `extraTime`, `penalties`, `decidedBy`, `competitionType: Competition | null`, `cupRound: string | null`, and `result` becomes `Result | null`.

### 3. "Played" from the scores

- `gameStatus`: played = `ownScore !== null && opponentScore !== null`. Not `result`. Update its comment.
- `Scoreline`: `played` from the two scores only. `ScorelineGame` stays a narrow `Pick` that both callers satisfy.
- `nextGame` keeps using `gameStatus`, so it now spans league and cup and never picks a played cup game.

### 4. Matches screen rows (`GameRow`)

- **A competition label on every row**, league too: `ליגה` / `גביע המדינה`, from i18n keyed by the enum (a `COMPETITION_KEYS` map in `labels.ts`, typed `Record<Competition, …>` so a new enum value doesn't compile without a label). Never the site's text. A cup row adds the round: `גביע המדינה · סיבוב ו` (`round` as given, inside the same element). Place it with the other row metadata (badges / details line); keep it a text badge, not colour alone.
- **Extra time / penalties:** the main scoreline stays the **90-minute** score. When `decidedBy` is set, a secondary line under the scoreline: `אחרי הארכה` + the extra-time score, or `בפנדלים` + the shootout tally (with penalties, also show the extra-time score when it's present: the site's 1123518 had both). Home first, each score its own element, the same grid idea as `Scoreline` (reuse or extract a small piece of it; don't fork the whole component). Muted, smaller than the main score.
- **Result badge:** `result` as today (ניצחון / תיקו / הפסד). It now means who went through, so a shootout win next to a level 90-minute score is correct, and the secondary line explains it. Scores set but `result` `null`: the scores, **no** result badge, no crash.
- Keep `DetailsFooter` as is (cup games get `detailsId` / `detailsState` like league games).

### 5. Dashboard next-match card (`MatchCards.tsx`)

The competition label (and a cup's round) next to the opponent and `SideBadge`. Same component / keys as the row. Nothing else changes; existing dashboard tests pass unchanged, plus new ones.

### 6. Game page (`GameHeader`)

- `competitionType` set (a cup): heading `<our label> · סיבוב <cupRound>` (e.g. `גביע המדינה · סיבוב ו`). **Never** `מחזור N` for a cup game. If `cupRound` is somehow `null` with a cup type: our label only.
- `competitionType` `LEAGUE` (if the backend ever sends it) or `null`: exactly as today, `<competition> · מחזור <round>` from the response.
- **Scope note:** uses the same competition text as the heading (our label for a cup, the response's `competition` otherwise). Keep its wording.
- **Score:** the same secondary extra-time / penalties line as the rows.
- `result` `null` → no `ResultBadge`, no crash.

### 7. The flip (backend, its own **last** commit)

- `ScrapeTriggerService.REQUEST_CUP_GAMES = true`; update its Javadoc (no longer "not sent until KAN-86"; keep the deploy-order note: the scraper must be at KAN-85 or later, or every refresh is `SCRAPER_UNAVAILABLE`).
- `ScraperClientTest.cupGamesIsInTheBodyOnlyWhenTrue`: the constant is now `true`; keep the `NON_DEFAULT` check for `false`. Check every other test that relies on the trigger body or the run record's `cupGames` flag (`"0"` today) and update what the flip changes, not more.
- `docs/scraper-backend-contract.md`: the trigger section (line ~25, ~48, ~135 on master: "NOT sent by the backend until KAN-86", "not requested by any trigger until KAN-85", `cupGames` `"0"` today). Make them true after the flip. Check `README.md`, `scraper/README.md` and `CLAUDE.md` for the same claim.
- The frontend changes must be committed **before** this commit on the branch.

## Out of scope

KAN-82 (penalty / shootout game pages and their events), KAN-87, KAN-83, KAN-78. No change under `scraper/`. No new screen, route or flow. No change to the stats screen. No sorting change in the fixtures read.

## Verify against reality, not memory

Check against what's installed, and say in the summary **how** you checked each:

- **Frontend:** run the frontend tests on **Node 22** (the CI job; `node --version` in the shell you run them in). Don't fix KAN-78 here.
- **Backend:** Boot / Jackson versions from `backend/pom.xml` (resolved): how a `null` record component and an enum are serialized in the response today (prove the new fields' JSON with a test on the real `ObjectMapper` / MockMvc, not prose). Run Maven with Temurin 21.
- **TypeScript:** the `Record<Competition, …>` exhaustiveness actually fails the build on a missing key (check once, then restore).
- **i18n:** extend `lib/matches/i18n.test.ts` if it checks key coverage.

## Read first

`CLAUDE.md` (rules 3, 6, 7), then only:

- Backend: `match/FixturesResponse.java`, `match/GameDetailsResponse.java`, `match/GameDetailsReadService.java` and its test, `match/MatchOutcome.java`, `match/StoredCupGame.java`, `match/CupFixturesSnapshot.java` (the competition getter), `scrapingintegration/ScrapeTriggerService.java`, `scrapingintegration/ScraperClient.java`, `ScraperClientTest.java`
- `docs/scraper-backend-contract.md` (trigger section; the `GET /matches/games/{id}` description if it's there)
- Frontend `lib/matches/`: `types.ts`, `games.ts` (+ test), `labels.ts`, `queries.ts`, `i18n.test.ts`
- Frontend components: `matches/GameRow.tsx`, `matches/GamesList.tsx`, `matches/game/GameHeader.tsx`, `dashboard/MatchCards.tsx`, `pages/matches/GamePage.tsx`
- `frontend/src/test/msw/matches.ts` and the tests of the files above
- The Hebrew locale file
- `docs/spec.md` status line, §07, §08, §13: to list what goes out of date, **not** to edit

## Test data (MSW)

- `gameBody` / `playedGame` defaults: `competition: "LEAGUE"`, `round`, `extraTime`, `penalties`, `decidedBy` all `null`, so existing tests keep their meaning.
- Helpers for cup games (made-up names): one upcoming, one decided in 90 minutes (round `ו`), one after extra time (`ownScore 2, opponentScore 2`, `extraTime {3, 2}`, `EXTRA_TIME`, `WIN`), one on penalties (`3–3`, `extraTime {3, 3}`, `penalties {1, 3}`, `PENALTIES`, `LOSS`), each both as a home and an away game where the order matters, and one played with `result: null`.
- `gameDetailsBody()` gets the new fields (`null` by default) plus a cup variant.

## Tests

Follow the existing style. At least:

**Logic**
- `gameStatus`: a game with both scores and `result: null` is `played`; a game with no scores is `upcoming` / `noResult` as before.
- `nextGame`: skips a played cup game with `result: null`; picks an upcoming cup game when it's the earliest.

**Matches screen**
- The competition label on a league row and a cup row; the cup row shows its round as given.
- Extra time and penalties lines: text, both scores in home → away DOM order for a home **and** an away game; no secondary line when `decidedBy` is `null`.
- Penalties win with a level 90-minute score → `ניצחון` badge + the shootout line; `result: null` with scores → scores shown, no result badge.

**Dashboard**
- The next-match card shows the label (and a cup's round) for a cup game that is next; existing tests unchanged.

**Game page**
- A cup game: heading `גביע המדינה · סיבוב ו`, no `מחזור` anywhere in the header, scope note with our label.
- `competitionType: null`: heading and scope note exactly as today.
- Extra time / penalties line; `result: null` → no badge, no crash, no console error.

**Backend**
- As in Decision 1, plus the flip tests in Decision 7. Run the whole backend `verify`.

**Mutation checks** (break on purpose, confirm a test fails, restore). At least:
1. "played" from `result` again in `gameStatus`;
2. the same in `Scoreline`;
3. show the site's text (or nothing) instead of our competition label;
4. drop the cup round from the row;
5. render the extra-time score as one string;
6. swap the extra-time scores for an away game;
7. show `מחזור N` for a cup game on the game page;
8. `competitionType` from the `h1` text instead of the snapshot;
9. use another club's cup snapshot in the lookup;
10. `REQUEST_CUP_GAMES` back to `false`.

Report each and whether it was caught.

Run in `frontend/` on Node 22: lint, format check, tests, build. In `backend/`: `spotless:check` and `verify`. Report test counts before (on master) and after, for both.

**E2E:** the E2E club is unmapped and must stay unmapped; no new scenario. Run the existing suite if your setup allows it and report.

## Local end to end (you run it; at most one real refresh)

With the flip in your working copy, the backend, the scraper **from master** (KAN-85 or later; check its commit) and the frontend running locally, and the club mapped to Sderot (7617 / 62 / 28):

1. One refresh (`POST /matches/refresh`, EDIT_FULL). If the cooldown is on, wait; don't work around it. At most 8 requests to football.org.il, and only this one refresh. You may mint a short-lived local access token signed with the dev `JWT_SECRET` (outside the repo, never printed, deleted afterwards), as in KAN-85.
2. The run `SUCCEEDED` with `cupGames` `STORED` / `OK`.
3. Raw `GET /matches/fixtures`: the cup games with `competition`, `round`, the 90-minute scores and `result` correct against the cup list fixture; league games unchanged; the order by date.
4. Raw `GET /matches/games/{id}` for one cup game with details (if one is `AVAILABLE`): `competitionType` `STATE_CUP`, `cupRound` the list's round; and one league game: both `null`.
5. No football.org.il id in either response.

Then write the steps for **Gal's** visual check in the browser (matches screen, dashboard card, a cup game's page, phone width, no horizontal scroll).

## Docs

- `README.md` / `CLAUDE.md`: only what lists what the frontend shows and whether cup games are requested. Nothing else.
- **`docs/spec.md`: do NOT edit it.** Gal provides the updated file before the PR (rule 7). In the summary, list **every** out-of-date place: section, a few quoted words, what's wrong. I expect at least the status line, §07 (cup games not requested / not shown, the game-details read's fields, "played"), §08 (matches screen, dashboard card, game page) and §13.

## Commits

Small, focused, in this order, for example:

- `feat(backend): cup competition and round on the game details read (KAN-86)`
- `feat(frontend): cup games types, played from the scores (KAN-86)`
- `feat(frontend): competition label, cup round, extra time and penalties on the matches screen and dashboard (KAN-86)`
- `feat(frontend): cup game heading and score on the game page (KAN-86)`
- `docs: README and CLAUDE.md for cup games (KAN-86)` (or with the code, per rule 7)
- **last:** `feat(backend): request cup games on every refresh (KAN-86)` (the flip, its tests, the contract doc)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Don't add this prompt to the repo yet.

## Summary to return

1. Branch, commits (hash + message), every file changed (one line each). Confirm no change under `scraper/` and that the flip is the last commit.
2. Versions verified and **how**, with findings for every "Verify against reality" item.
3. The design: the backend lookup refactor, the new types, "played", the label component and keys, the extra-time / penalties line (and how `Scoreline` was reused), the game page heading rules.
4. Raw examples: the fixtures JSON of one cup game from the end-to-end run, the game-details JSON of one cup and one league game (or why none was available), and the rendered text of a penalties row.
5. Tests: new tests (name + one line), the mutation checks and results, counts before / after (frontend and backend), lint / format / build / verify results, E2E result or why not run.
6. The end-to-end run: requests made, blocks, run outcome, what matched.
7. Gal's visual-check steps.
8. Docs changed, one line each.
9. Spec: every out-of-date place.
10. Deviations from this prompt, and why.
11. Open ends and risks (anything KAN-82 / KAN-87 will need from these screens; anything you thought of but didn't add).
12. Print in full, at branch HEAD: `GameDetailsReadService.java`, `games.ts`, `GameRow.tsx`, `GameHeader.tsx`, and the diff of `types.ts`, `MatchCards.tsx`, `ScrapeTriggerService.java`.
