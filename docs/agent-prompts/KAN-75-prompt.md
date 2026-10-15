# KAN-75: Game-details spike: capture game pages, pin the markup, propose the per-game contract (no production code)

**Credit budget is tight.** Read only the files this prompt names. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

**Run the scraper tooling on Node 24** (`scraper/` requires ≥ 24). Report `node --version`.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `bcf7972`, the KAN-77 merge, PR #57). If it doesn't, stop and report. Then create the branch `chore/KAN-75-game-details-spike`.

## Context

Jira KAN-75, epic KAN-14 ("Match & Scraping Integration"). The player-statistics slice (KAN-70, 72, 73, 74, 77) is done: the club has its team's **season** stats per player. KAN-75 was re-scoped with Gal into a **research spike: no production code**, exactly like KAN-64 and KAN-70. Its output decides how these are built:

- KAN-79: backend — store per-game details (our lineup, per-game shirt numbers, goals, cards, subs), the contract part, the "already stored games" list in the trigger, a read endpoint.
- KAN-80: scraper — fetch up to 2 finished, not-yet-stored game pages per scrape, parse our side, send it.
- KAN-81: frontend — a game details screen opened from the matches screen.
- KAN-76 (later, related): cup games in the fixture list, with a game identity of competition + round + teams.

### What KAN-70 already found (§7 of `scraper/docs/KAN-70-findings.md`)

From **one** game only (`game_id=1122631`, Tira–Sderot, round 1), no fixture saved:

- Everything is in the initial HTML; one document request per game; passed Cloudflare 1 of 1.
- `h1`: competition and round, e.g. `ליגה א' דרום מחזור 1`.
- Groups under `section.game-details .players-cont`: `div.home.Active` / `div.guest.Active` (starters), `.Replacement` (subs), `.Bench` (unused), plus coaches.
- Per player `a[href*="player_id="] > div.player`: `span.number` = the shirt number in that game (`מס' 1`), `span.name > b` = name, first name first; `- (GK)` for the goalkeeper; `span.moves` with `span.yellow`, `change-down`, `change-up`, and goals as bare `sr-only` spans with a minute.
- Starters' links carry `&season_id=28`; substitutes' and bench links don't.
- **No minutes played per player.**

### Why a second look

KAN-70 saw no red card, no second yellow, no own goal or penalty, no captain marker, no goal after 90'. A parser built on one sample will break on the first such game. Gal sent screenshots of **Dimona–Sderot, 2026-10-09** (Dimona home, 4-1), which shows at least:

- a **straight red card** at 40' (a Dimona player), drawn as a red card icon with the minute;
- the captain marker: `(C) (GK) - <name>` on Dimona's goalkeeper;
- a goal at **93** (shown as `93`, check whether the HTML is `93` or `90+3`);
- substitutes with a yellow card after coming on, and a starter with a goal and then subbed off;
- a "שערים:" scorers line per team under the score, and two tabs: הרכב / מהלך משחק.

The markup is the same for both teams, so a Dimona-side event is a valid sample of that event's markup.

## The repo today (read these, nothing else)

- `CLAUDE.md` (standing rules).
- `scraper/docs/KAN-70-findings.md` §7, §9, §10 and "Open questions and risks" (KAN-75 and KAN-76 parts). `scraper/docs/KAN-64-findings.md` only for the method if §Method of KAN-70 isn't enough.
- `scraper/src/browserSource.js`, `scraper/src/scrape.js` and `scraper/src/parseFixtures.js`: the route rule, the honest User-Agent, the request order, pauses, timeouts, the 90 s cap, and how a played game and its `game_id` are parsed today.
- `docs/scraper-backend-contract.md`: the trigger and the result parts, only to shape your proposal in the same style.
- `scraper/package.json` and `scraper/test/fixtures/README.md`.

## Decisions (agreed with Gal; context, not up for change in this ticket)

- The shirt number belongs to the **per-game appearance**, never to `Player`. Scraping never writes `Player`.
- **No per-game minutes.** We keep sub-on / sub-off minutes as shown; season minutes stay from the stats table (KAN-72).
- **Which games**: the backend sends the scraper the `gameId`s it already stores; the scraper takes finished games from the team-games page it already loads, skips the stored ones, **at most 2 per scrape, oldest first**. A game is fetched once, never on later refreshes.
- **League games first** (KAN-75 before KAN-76). Each stored game carries competition + round from the `h1`, so cup games use the same path later.
- **Only our team's group is kept.** The other club's players are never stored or sent (still an open point with Gal for the UI, but the parser must be able to pick one side; documenting the other side's markup is fine since it's identical).
- Photos and logos are never imported. No football.org.il id or link is shown in the app (spec §09).
- Same scraping policy as KAN-64 / KAN-67 / KAN-70: honest headless browser, no stealth, no browser-UA spoofing, no proxies. A block stops; no retry storm.

## Verify against reality, not memory

For each, say in the summary how you checked:

- Use the Playwright and Chromium **actually installed** for `scraper/` (`scraper/package.json` + lockfile, `npx playwright --version` inside `scraper/`). Investigation scripts live **outside the repo** and import from `scraper/node_modules` by absolute path, as in KAN-70. No lockfile or `node_modules` change.
- Don't assume the KAN-70 selectors hold on other games; check each on every captured page and report any difference.
- Read the `game_id`s from the **live** team-games page (the committed fixture `team-games-7617-s28.html` is from 2026-10-11 and doesn't have the Dimona game).

## The investigation

Same setup as KAN-70 "docs" mode: headless, User-Agent `SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)`, locale `he-IL`, the **production route rule** (same-origin documents only), everything else aborted. Sequential requests, random 3–5 s apart within a run; separate runs at least 60 s apart. **Budget: at most 10 requests to football.org.il in total.** On a Cloudflare `403`: stop, note it, don't retry.

Pages:

1. The team-games page: `https://www.football.org.il/team-details/team-games/?team_id=7617&season_id=28` (to get the played games' `game_id`s).
2. **Dimona–Sderot, 2026-10-09** (Sderot away). Required.
3. One more played **league** game where Sderot is **home**. Required.
4. One more played league game, any, preferably one with a penalty, an own goal or a second yellow if the scorers line or result hints at it (don't fetch extra pages to search for one).
5. The State Cup game `https://www.football.org.il/leagues/games/game/?game_id=1142007` (Rahat–Sderot, 2026-10-12): **only** for its `h1` and whether its structure matches a league game. KAN-76 context.

Run 2 + 3 (two game pages after the team-games page, as one simulated scrape) at least once with the exact proposed sequence, to measure the budget.

Answer each question with evidence (URL, status, a short HTML excerpt):

1. **`game_id` in the team-games list.** For a played game: where it is, its format, whether it equals the game page's `game_id`. How a finished game is recognised (score shown) versus a scheduled or postponed one. Anything in `parseFixtures.js` that already gives it to us.
2. **Our side.** How the game page marks home and guest (classes, order). Confirm that the team-games row's home / away tells us which group is ours on the game page. Report the team names as each page shows them (short vs full names).
3. **The `h1`.** Exact text and structure for each captured page: competition, round (league) or stage (cup). Is the round always `מחזור N`? What does the cup page show?
4. **Per player, every field and marker**, with the exact markup: shirt number (and any player with no number), name, `(GK)`, `(C)`, role group (starter / sub / unused), and each event: goal, own goal, penalty goal, yellow, second yellow, red, sub off, sub on, with its minute. Minutes after 90: `93` or `90+3`? Extra time in a cup game? Any event shown without a minute?
5. **Totals and consistency.** Per captured game: the per-player goals of our side vs the score and the "שערים:" line; starters count (11?); a player appearing twice; coaches excluded.
6. **Request budget.** Measured times for team-games + 2 game pages. Proposed position of the game pages in the existing sequence (`scrape.js`: team games → players stats → league page → `LeagueTable`), their timeout, and whether the worst case still fits the 90 s cap with today's single retry. If 2 doesn't fit the worst case, say what does.
7. **Good citizen.** Any change in `robots.txt` (only if you fetch it; it counts in the budget), caching headers, Cloudflare behaviour. Total requests, the delays used, blocks hit.

## Deliverables

### 1. Findings doc

`scraper/docs/KAN-75-findings.md`, in English, same structure as `KAN-70-findings.md`:

- A short summary on top: what's on the page, Cloudflare result, cost per game and per scrape.
- Method and key commands (scripts not committed).
- Answers to questions 1–7, with evidence.
- A **proposed parsed output** for one game, our side only (field names, types, which are nullable, enums for role and event kind), marked as a proposal; KAN-79 decides the contract. Follow the style of the KAN-70 proposal.
- A **proposed trigger addition** (the stored `gameId`s) and a proposed scrape sequence with the "at most 2, oldest first" selection.
- Open questions and risks for KAN-79, KAN-80, KAN-81 and KAN-76.

### 2. Test fixtures

Under `scraper/test/fixtures/`, one file per captured game page (required: Dimona–Sderot and the home game; the others if captured):

- Keep the `h1`, the score block and **both** teams' `players-cont` groups unchanged (the parser must be tested on picking one side). Drop scripts, styles, navigation, ads, tracking, the timeline only if it isn't needed by a parser (say which), and **every image** (no player photos, no logos; remove `<img>` elements entirely).
- Personal data: names, `player_id` links, shirt numbers and events only. No birth dates or anything else.
- Name them in the existing style, e.g. `game-<game_id>.html`.
- Update `scraper/test/fixtures/README.md`: source URL, capture date (Israel time), what was trimmed. Update its "Not captured" sentence about game details.

### No production code

No changes to `scraper/src/`, `scraper/package.json`, the lockfile, tests other than fixtures, CI, Dependabot, docker-compose, `backend/` or `frontend/`.

## Docs

- `CLAUDE.md` / `README.md` / `scraper/README.md`: I expect no change. If you think one is needed, say so in the summary instead.
- **`docs/spec.md`: do NOT edit it.** In the summary, list anything now out of date, with the section and what's wrong. I expect §07's mentions of per-game data / "minutes" from the game page, if any, to be worth listing; confirm or correct.

## Commits

Small, focused commits, for example:

- `docs(scraper): add game-details investigation findings (KAN-75)`
- `test(scraper): add trimmed game-details fixtures (KAN-75)`

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), files added or changed, one line each.
2. Node, Playwright and Chromium versions used, and how you checked them.
3. The answer in three lines: what's on the page, Cloudflare result (n of n), cost per scrape for 2 games.
4. Answers to questions 1–7, short (details in the findings doc). Every KAN-70 §7 selector: confirmed or contradicted on each page.
5. The proposed parsed output, the trigger addition and the scrape sequence.
6. Fixtures: each file, size, source URL, what was trimmed; confirm no images and no birth dates in them.
7. Total requests to football.org.il, delays used, blocks hit.
8. Spec: each out-of-date place (section + what's wrong), or "none".
9. Deviations from this prompt, and why.
10. Open ends and risks for KAN-79, KAN-80, KAN-81, KAN-76.
11. Print the findings doc in full.
