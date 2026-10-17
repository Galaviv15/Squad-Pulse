# KAN-76: Cup-games spike: capture the State Cup / Toto lists, pin the markup, our side, identity and budget (no production code)

**Credit budget is tight.** Read only the files this prompt names. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

**Run the scraper tooling on Node 24** (`scraper/` requires ≥ 24). Report `node --version`.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `5e37d5b`, the KAN-81 merge, PR #61). If it doesn't, stop and report. Then create the branch `chore/KAN-76-cup-games-spike`.

## Context

Jira KAN-76, epic KAN-14 ("Match & Scraping Integration"). Fixtures and standings (KAN-64 … 69), player season stats (KAN-70 … 77) and per-game details (KAN-75, 79, 80, 81) are done, **for league games only**: the team-games page lists league games only, so the club's cup games (State Cup, Toto) never appear. Gal: they must come into the schedule.

KAN-76 was re-scoped with Gal into a **research spike: no production code**, exactly like KAN-64, KAN-70 and KAN-75. Its output decides how these are built:

- KAN-84: backend — the cup part of the contract, its own club-scoped snapshot, one merged fixtures read with competition and round.
- KAN-85: scraper — fetch and parse the cup lists, send the part, take played cup games into game details.
- KAN-86: frontend — competition label per game, the next-match card across all competitions.

### What's already known

From `scraper/docs/KAN-70-findings.md` §9 (described only, **no response saved**):

- The team page loads `GET /Components.asmx/GetTeamNatCupData?team_id=7617&season_id=28` (State Cup) and `GET /Components.asmx/GetTeamTotoCupData?teamId=7617&seasonId=28` (Toto; `Games: null` then). Note the two parameter styles.
- Seen as JSON whose `d` is a **JSON string** holding `{StandingTable, Comments, Games}`, `Games` being HTML. (The stats call, `GetTeamPlayersStatisticsList`, answered the XML `ResponseData` envelope with the scraper's own `fetchInPage` headers but JSON for the site's own call. Check which one you get for each cup call with `fetchInPage`'s headers.)
- Columns: סיבוב, תאריך, משחק, אצטדיון, שעה, תוצאה, הארכה, ב.הכרעה. The `sr-only` labels are **shifted by one cell**; the game cell **glues the home name twice**; team names are **full club names** (`מועדון כדורגל שדרות`), not the short names of the league pages (`מ.כ. שדרות`).
- Two State Cup rows then: `1123491` (27/08, round `ה' - ליגות א' דרום`, result `0-1`) and `1142007` (12/10, round `ו`, Rahat–Sderot, result `4-1`).

From `scraper/docs/KAN-75-findings.md` §1–§3 and the KAN-76 notes:

- Cup game `1142007` isn't on the team-games page. Its game page has the league page's structure; its `h1` is `גביע המדינה ווינר מחזור 4`, while the cup list says round `ו` for the same game. The game page's header `team_id` (7617) picks our side, cup included. The game page says Sderot won **1-4 away**, so the list's `4-1` looks away-first like the team-games page: **confirm, don't assume.**

### What the code does today (why the spike is needed)

Read these to understand the constraints; change none of them:

- `backend/.../match/ScrapeIngestService.java` (around lines 440–500): the fixtures part finds **our team by the standings name** and rejects a game without it (`INVALID`); a home / away pairing twice → `DUPLICATE_GAME`. With full club names, every cup row would be `INVALID`.
- `backend/.../match/StoredGame.java`: the league key is (home, away) names. There's **no league round** on the team-games page (KAN-64 findings, "Not shown"), so a league game can't be keyed by its round from this source.
- `scraper/src/selectGames.js` and `scraper/src/parseGameDetails.js` (lines ~70–90): game details take games from team-games rows only, and check the page's names and score against the row. Cup rows (full names) vs the game page (short names) would fail that check.
- `scraper/src/browserSource.js` (`fetchInPage`, the route rule: same-origin documents plus one exact fetch URL at a time) and `scraper/src/scrape.js` (order, pauses, timeouts, the 90 s cap, one retry, game pages last with a 15 s guard).

## Decisions (agreed with Gal; context, not up for change in this ticket)

- **The user flow doesn't change**: same refresh button, lock, run status, matches screen, next-match card.
- **Cup games get their own club-scoped snapshot and their own result part.** A failed cup fetch never deletes the league games, and the reverse. The league snapshot and its key stay as they are: no migration.
- **Identity, as a starting proposal**: a league game stays (home, away) within the league snapshot; a cup game = (competition, round **from the cup list**, home, away). The `h1` round is never mixed in. Propose something better if the evidence calls for it (e.g. `game_id` for cup games if it's never `-1` and never changes), with the trade-off.
- Read side: one list merged by date, each game with its competition (and a cup round). The next-match card covers every competition.
- Played cup games go into the game-details queue (KAN-79/80) too.
- **Toto is fetched as well**, even though it's empty today. **Regional cup**: only if this spike finds a source.
- Same scraping policy as KAN-64 / 67 / 70 / 75: honest headless browser, no stealth, no browser-UA spoofing, no proxies. A block stops; no retry.
- No football.org.il id or link in the app (spec §09). Photos and logos are never imported.

## The repo today (read these, nothing else)

- `CLAUDE.md` (standing rules).
- `scraper/docs/KAN-70-findings.md` §9 and its KAN-76 open questions; `scraper/docs/KAN-75-findings.md` §1–§3 and its KAN-76 notes; `scraper/docs/KAN-64-findings.md` only the team-games "Not shown" paragraph.
- The code files named above, only the parts named.
- `docs/scraper-backend-contract.md`: the trigger, the fixtures part and the game-details part, only to shape your proposal in the same style.
- `scraper/package.json`, `scraper/test/fixtures/README.md`.

## Verify against reality, not memory

For each, say in the summary how you checked:

- Use the Playwright and Chromium **actually installed** for `scraper/` (`scraper/package.json` + lockfile, `npx playwright --version` inside `scraper/`). Investigation scripts live **outside the repo** and import from `scraper/node_modules` by absolute path, as in KAN-70 / KAN-75. No lockfile or `node_modules` change.
- Build each cup URL with `new URL(…).href`, so it equals, character for character, what the route rule compares against (KAN-73's finding).
- Don't trust KAN-70 §9's description: check every column, quirk and row on today's live response.

## The investigation

Same setup as KAN-75: headless, User-Agent `SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)`, locale `he-IL`, the **production route rule** (same-origin documents only, plus the exact URL you are about to fetch in-page), everything else aborted. Sequential requests, random 3–5 s apart within a run; separate runs at least 60 s apart. **Budget: at most 12 requests to football.org.il in total.** On a Cloudflare `403`: stop, note it, don't retry.

Requests:

1. The team-games page: `https://www.football.org.il/team-details/team-games/?team_id=7617&season_id=28`.
2. From inside it, in-page: `GetTeamNatCupData` and `GetTeamTotoCupData` (exact URLs as KAN-70 found them). If either fails from the team-games page, say exactly how (status, body start) before trying anything else; you may then try from the team page (`/team-details/?team_id=7617&season_id=28`), and report the difference.
3. **One simulated full scrape** with the proposed sequence (below), to measure the budget: team games → stats → **the two cup calls** → league page → `LeagueTable`, **without** game pages unless the budget allows one. Use real pauses.
4. Only if it answers question 3 and fits the budget: the page where a regional cup would be listed (look for it in the team page's own `Components.asmx` calls in the KAN-70 notes first; don't browse to search).

Answer each question with evidence (URL, status, content type, a short excerpt):

1. **Response format.** XML envelope or JSON for each call with `fetchInPage`'s headers; where the `Games` HTML sits; how to read it with the existing `asmx.js` reader or what it would need. `StandingTable` / `Comments`: present, and anything useful (a Toto group table?).
2. **Rows.** Every column by **position**, with the exact markup: round, date (format), the game cell (the glued home name: how to split home / away reliably, names with hyphens included), venue, time (a placeholder like `00:00`?), result (**score order**: prove it with `1142007` against the game page's 1-4), extra time, penalties (how a tie decided on penalties shows). The `game_id` link: present on every row? `-1` on an unscheduled one? Rows of a future, scheduled cup game (if any). Header row, empty list, `Games: null`.
3. **Our side.** How to tell which side is ours in a cup row, reliably and without name guessing. Candidates to check, in this order, with evidence: a team id or link in the row; our full club name taken from a page we already load (where exactly?); the team-games page; anything else. If none is reliable, say so and propose the least bad rule with its failure mode.
4. **Competitions.** Is there a regional cup for this team, and where? Toto: what an empty answer looks like exactly (to parse it as "no games", not an error).
5. **Identity.** For each cup game: competition, round string, `game_id`, home / away. Is the round string stable between two fetches? Could a pairing repeat within one cup (two-legged ties)? Propose the cup game's key and say why, with the trade-off against `game_id`.
6. **Game details for cup games.** Using the saved `game-1142007.html` fixture (don't fetch it again): what in `selectGames` and in `parseGameDetails`' row check would break for a cup row, and what a cup row must carry so it works (e.g. the short names, or checking by `team_id` and score only). Don't change code: describe.
7. **Request budget.** Measured times for the full sequence. Proposed position of the cup calls, their timeout, and whether the **worst case** (every request at its timeout, one retry) still leaves the 15 s game-page guard inside the 90 s cap with up to 2 game pages. If not, what fits.
8. **Good citizen.** Caching headers, Cloudflare behaviour, total requests, the delays used, blocks hit.

## Deliverables

### 1. Findings doc

`scraper/docs/KAN-76-findings.md`, in English, same structure as `KAN-75-findings.md`:

- A short summary on top: what the cup lists give, Cloudflare result, cost per scrape.
- Method and key commands (scripts not committed).
- Answers to questions 1–8, with evidence.
- A **proposed cup part** for the result contract (field names, types, nullability, enums for competition), our side marked as decided by question 3, marked as a proposal; KAN-84 decides the contract. Follow the style of the existing parts.
- A **proposed identity** for cup games and a proposed scrape sequence.
- Open questions and risks for KAN-84, KAN-85 and KAN-86.

### 2. Test fixtures

Under `scraper/test/fixtures/`:

- `natcup-7617-s28.<xml|json>` and `totocup-7617-s28.<xml|json>`: the responses **as received**, trimmed only of loading animations or similar noise (say what). Team names, scores and `game_id`s are public match data and stay real. No images, no logos.
- Update `scraper/test/fixtures/README.md`: source URL, capture date (Israel time), the headers it was fetched with, what was trimmed.

### No production code

No changes to `scraper/src/`, `scraper/package.json`, the lockfile, tests other than fixtures, CI, Dependabot, docker-compose, `backend/` or `frontend/`.

## Docs

- `CLAUDE.md` / `README.md` / `scraper/README.md` / the contract doc: I expect no change. If you think one is needed, say so in the summary instead.
- **`docs/spec.md`: do NOT edit it.** In the summary, list anything now out of date, with the section and what's wrong. I expect §07's statements about cup games / the fixture key to be worth listing; confirm or correct.

## Commits

Small, focused commits, for example:

- `test(scraper): add State Cup and Toto list fixtures (KAN-76)`
- `docs(scraper): add cup-games investigation findings (KAN-76)`

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), files added or changed, one line each.
2. Node, Playwright and Chromium versions used, and how you checked them.
3. The answer in three lines: what the cup lists give, Cloudflare result (n of n), cost per scrape.
4. Answers to questions 1–8, short (details in the findings doc). Every KAN-70 §9 claim: confirmed or contradicted.
5. The rule for our side, and how sure you are (evidence, failure mode).
6. The proposed cup part, the identity and the scrape sequence.
7. Fixtures: each file, size, source URL, headers, what was trimmed; confirm no images.
8. Total requests to football.org.il, delays used, blocks hit.
9. Spec: each out-of-date place (section + what's wrong), or "none".
10. Deviations from this prompt, and why.
11. Open ends and risks for KAN-84, KAN-85, KAN-86.
12. Print the findings doc in full.
