# KAN-70: Player-stats spike: investigate football.org.il team player-statistics table, game details and player card (no production code)

**Credit budget is tight.** Read only the files this prompt names. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `cc0a734`, the KAN-69 merge, PR #52). If it doesn't, stop and report. Then create the branch `chore/KAN-70-player-stats-spike`.

## Context

Jira KAN-70, epic KAN-14 ("Match & Scraping Integration"). The first scraping slice (KAN-64 … KAN-69: fixtures, league table, refresh, screens) is done. KAN-70 was re-scoped with Gal into a **research spike: no production code**, exactly like KAN-64. Its output decides how these are built:

- KAN-72: backend — store the team's player season stats, the scraper ↔ backend contract for them, links from a scraped player to our `Player`.
- KAN-73: scraper — parse the player-statistics table and send it with the scrape result.
- KAN-74: frontend — stats screen and linking.
- KAN-75 (later): per-game data from game details (lineups, minutes, per-game shirt number).
- KAN-76 (later): cup games in the fixture list, with a new game identity key (competition + round + teams).

### Why a second spike

KAN-64 §7 (`scraper/docs/KAN-64-findings.md`) located the league page's `Components.asmx/LeagueStatistics`: it has only top-N lists (scorers, cards) and **no minutes or starts**. Gal found the real source: the **team page** has a "סטטיסטיקות שחקנים" table with every registered player. KAN-64 listed the team page as a target but never investigated it.

### Pages in scope (Sderot FC, `team_id=7617`, `season_id=28`, `league_id=62`)

- **Layer A (main question):** team page, player-statistics table — `https://www.football.org.il/team-details/?season_id=28&team_id=7617`
- **Layer B (locate only):** a game details page — `https://www.football.org.il/leagues/games/game/?game_id=…` (take one played game's id from `scraper/test/fixtures/team-games-7617-s28.html` or the live team-games page).
- **Layer C (locate only):** a player card — `https://www.football.org.il/players/player/?player_id=85286&season_id=28`
- Not needed: the squad page (`/team-details/?itemid=…`). Gal confirmed the stats table already lists every registered player. Don't fetch it unless the stats table turns out not to.

### Known from Gal — capture in fixtures and confirm, don't re-argue

- Columns, right to left: player name (a link to `/players/player/?player_id=…`), games (מס. משחקים), goals, yellow cards league/cup, yellow cards Toto, red cards, starts (הרכב פותח), came on as sub (נכנס כמחליף), subbed off (הוחלף), minutes (דקות משחק).
- It covers **all competitions** (league, State Cup, regional cup, Toto). Totals won't match our league-only fixture list; that's expected.
- It lists **every registered player**, also those with 0 minutes.
- `player_id` is **stable across seasons**.
- `games = starts + came on as sub` on every row.
- Names are shown **surname first** (e.g. "לוי תומר").
- Minutes can exceed 90 × games (added time, extra time).
- The header shows sort chevrons, which suggests client-side sorting and possibly a JS-loaded table.
- In Liga Alef and below a player's shirt number can change from game to game; the number per game is in the game details.

If anything you observe contradicts one of these, report it with evidence — don't silently follow either.

### The repo today (read these, nothing else)

- `CLAUDE.md` (standing rules).
- `docs/spec.md` sections 05, 07, 09 and 14 only.
- `scraper/docs/KAN-64-findings.md` — the method, the Cloudflare finding, the verified route rule and the good-citizen budget. **Follow the same method.**
- `scraper/src/browserSource.js` and `scraper/src/scrape.js` — how the production scraper opens the browser, its route rule (same-origin documents + the one exact `LeagueTable` fetch), the honest User-Agent, and the request order and delays. Your answer to question 3 must fit into this.
- `scraper/package.json` and `scraper/test/fixtures/README.md`.

## Decisions (agreed with Gal; context, not up for change in this ticket)

- Layer A (season stats per player) is the next implementation; B and C are later and only located here.
- Scraped stats are club-scoped (our team only), shown as "all competitions".
- Linking a scraped player to our `Player` is manual (`EDIT_FULL` and up), with a suggestion by name, word order ignored. Never automatic.
- Scraping **never writes `Player`**. `Player.jerseyNumber` stays club-managed; a per-game number belongs to the per-game appearance (layer B).
- **Photos are never imported.** Don't save any image in fixtures. No logos either.
- No football.org.il id or link is shown in the app (spec §09).
- Same scraping policy as KAN-64 / KAN-67: honest headless browser, no stealth, no browser-UA spoofing, no proxies. A block stops; no retry storm.

## Verify against reality, not memory

For each, say in the summary how you checked:

- Use the Playwright and Chromium **actually installed** for `scraper/` (`scraper/package.json` + lockfile, `npx playwright --version` inside `scraper/`), not a version you remember. If you need a separate install for the investigation, install it **outside the repo** or in a temporary folder you delete. No lockfile or `node_modules` change is committed.
- Don't assume the ASMX parameter names or the table's markup match the `LeagueTable` call from KAN-64; read them from the actual traffic.

## The investigation

Use the same setup as KAN-64: headless Chromium with network logging, the identifiable User-Agent `SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)`, locale `he-IL`, third-party requests, images, fonts, media, stylesheets and SVGs aborted. Sequential requests, at least 5 s apart; separate scrape runs at least 60 s apart. Keep the total small and report it. On a Cloudflare `403` block page: stop, note it, wait, don't hammer.

Answer each question with evidence (request URL, method, status, a short excerpt):

### Layer A — the main question

1. **Delivery.** Is the player-statistics table in the team page's initial HTML, or loaded later? If later: which request exactly (URL, method, parameters, required headers such as `x-requested-with`, response format and encoding). What triggers it (page load, scroll, a click)? Which parameters are fixed and which come from the page (like `round` / `box` for `LeagueTable`)?
2. **Cloudflare and the route rule.** Does it come through with the production setup, extended **only** by the exact URL needed (as `browserSource.js` does for `LeagueTable`)? Run the exact proposed setup at least 3 times, ≥ 60 s apart, and report each result.
3. **Request budget.** Exactly which requests a scrape needs to add for this table, in what order relative to today's three (`scrape.js`), with what delay and timeout, and whether one scrape still fits the 90 s cap. Is a separate team-page document load needed, or can it share a load we already do?
4. **Structure.** For each column: the header label text, the selector or label that identifies it robustly (not by position), and the cell format. The name cell: how the name and the `player_id` link appear (relative or absolute URL, other params such as `season_id`). Empty values (empty string, `-`, `0`?). Any paging, "show more", Top-N cut or hidden rows. The source order of rows.
5. **Confirm Gal's facts** on today's data: row count vs. players with 0 minutes, `games = starts + came on` on every row, surname-first names, minutes > 90 × games somewhere. List any row that breaks a rule.
6. **Quirks.** Names with geresh / gershayim / dots / commas / double spaces, RTL or `ltr` classes, `sr-only` labels glued into text (the KAN-64 venue trap), anything that will trip a parser.

### Layers B and C — locate only, don't parse

7. **Game details page** (one played game): is the data in the HTML or loaded later, and by which request(s)? Does it pass Cloudflare? Which of these are there: both lineups, starters / bench, minutes or sub minutes, **the shirt number per player in that game**, goals, cards, **the round number or cup stage, and the competition name**? Does each player link carry the same `player_id`? How many requests would one game cost?
8. **Player card:** same delivery questions. Which fields are there (birth month and year, position, per-game list with links to games, season totals)? One request per player?
9. **For KAN-76 (cup games), only note:** where, if anywhere, a team's cup games are listed (a link or tab you see from the team page), and whether any page you already loaded shows round / stage / competition per game. Don't go looking for cup pages beyond what's linked from the pages above.

### Good citizen

10. Any change since KAN-64 in `robots.txt`, caching headers or Cloudflare behaviour. Total requests made, the delays used, and how many blocks you hit.

## Deliverables

### 1. Findings doc

`scraper/docs/KAN-70-findings.md`, in English, same structure as `KAN-64-findings.md`:

- A short summary on top: how the table is fetched, whether it passes Cloudflare, and the extra request cost per scrape.
- Method and key commands (scripts not committed), so the investigation can be repeated.
- Answers to questions 1–10, with evidence.
- A **proposed parsed output shape** for layer A (field names, types, optional fields), marked as a proposal; KAN-72 decides the contract. Follow the style of the `FixturesResult` proposal in KAN-64.
- The proposed change to the scrape sequence (requests, order, delays, route rule).
- Open questions and risks for KAN-72, KAN-73, KAN-75 and KAN-76.

### 2. Test fixtures

Under `scraper/test/fixtures/`:

- **Required:** the player-statistics table source (the HTML section or the ASMX response), trimmed to what a parser needs, with every row unchanged. Name it in the existing style (e.g. `team-players-7617-s28.html` or `.xml`).
- **Optional:** one trimmed game-details sample and one trimmed player-card sample, **only if** they're small after trimming and you think KAN-75 will need them. Not required.
- Trimming as in KAN-64: no scripts, styles, ads, tracking, navigation, and **no images at all** (no player photos, no `<img>` with a photo URL; replace any with nothing). Personal data: keep only what the parser needs; drop birth dates and anything else not in the stats table from the required fixture.
- Update `scraper/test/fixtures/README.md`: source URL, capture date (Israel time), what was trimmed. Remove the "Not captured: … `LeagueStatistics`, out of scope until KAN-70" note only if you capture it; otherwise reword it to point to KAN-70's finding.

### No production code

No changes to `scraper/src/`, `scraper/package.json`, the lockfile, tests other than fixtures, CI, Dependabot, docker-compose, `backend/` or `frontend/`.

## Docs

- `CLAUDE.md` / `README.md` / `scraper/README.md`: I expect no change. If you think one is needed, say so in the summary instead.
- **`docs/spec.md`: do NOT edit it.** In the summary, list what is now out of date and where. I already know of these (confirm or correct):
  - §07 says the approved scope includes "lineups, jersey numbers, and playing minutes" and that they're a later ticket (KAN-70). It should now point to KAN-72 … KAN-75, and say the season stats come from the team page and cover all competitions.
  - §14's open question on scraped jersey numbers is now decided: scraping never writes `Player`; a per-game number belongs to the per-game appearance.
  - §05's "Performance data — minutes played … phase 2+, depends on Veo / chips": minutes now also come from scraping.

## Commits

Small, focused commits, for example:

- `docs(scraper): add player-statistics investigation findings (KAN-70)`
- `test(scraper): add trimmed team player-statistics fixture (KAN-70)`

Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Branch, commits (hash + message), files added or changed, one line each.
2. Playwright / Chromium version used and how you checked it.
3. **The answer for layer A** in three lines: how the table is fetched, Cloudflare result (n of n runs), extra requests per scrape.
4. Answers to questions 1–10, short (details in the findings doc).
5. Gal's facts: each confirmed or contradicted, with evidence.
6. The proposed layer-A output shape and the proposed scrape sequence.
7. Fixtures: each file, size, source URL, what was trimmed; confirm no images and no birth dates in them.
8. Total requests to football.org.il, delays used, blocks hit.
9. Spec: each out-of-date place (section + what's wrong).
10. Deviations from this prompt, and why.
11. Open ends and risks for KAN-72, KAN-73, KAN-75, KAN-76.
12. Print the findings doc in full.
