# KAN-64: Scraping spike: investigate football.org.il pages, data sources and rendering (no production code)

**Credit budget is tight.** Read only the files this prompt names. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `2ac411e`, the KAN-42 merge, PR #46). If it doesn't, stop and report. Then create the branch `chore/KAN-64-scraping-spike`.

## Context

Jira KAN-64, the first ticket of epic KAN-14 ("Match & Scraping Integration", Phase 5 on the roadmap; Gal chose to do it before Phase 4). It is a **research spike: no production code**. Its output decides how the rest of the epic is built:

- KAN-65: football.org.il team / league / season ids on `Club`, set by the system owner.
- KAN-66: backend storage for fixtures and league standings, an internal ingest endpoint (service token), read endpoints.
- KAN-67: the scraper as a small, always-running internal **Node** service: the backend triggers it, it scrapes and posts the parsed result back to KAN-66's endpoint. It never touches MongoDB.
- KAN-68: the backend refresh endpoint behind a button (`EDIT_FULL` and up), with a 10-minute lock per team (fixtures) and per league + season (standings).
- KAN-69: frontend cards, matches screen, refresh button.
- KAN-70 (later): per-player statistics.

**First scope is fixtures/results and league standings only.** Lineups, jersey numbers, minutes and player stats are out of scope (only *located* here, see question 7).

Decided with Gal (context, not up for change in this ticket): Node for the scraper, triggered by a button, no cron for now, the scraper never writes to the DB.

### Target (a real club)

- Team: Sderot FC (מ.כ שדרות), Liga Alef South — `team_id=7617`, `season_id=28`, `league_id=62`.
- Team page: `https://www.football.org.il/team-details/?season_id=28&team_id=7617`
- Team games (fixtures + results): `https://www.football.org.il/team-details/team-games/?team_id=7617&season_id=28`
- League page (standings + player stats): `https://www.football.org.il/leagues/league/?league_id=62&season_id=28`

### Preliminary findings — mine, from a text-only fetch tool. Verify, don't trust.

- The team-games list **appears** to be in the initial HTML: a table of date, home – away, venue, time, result, with `/leagues/games/game/?game_id=…` links (e.g. 1122631, 1122634). Unscheduled games link to `game_id=-1` with time 00:00 and no result. The venue text seemed concatenated with the team names.
- The league page **appears** to load its standings and statistics later: the text "המתן לטעינת נתונים" and empty "clickMe" placeholders. The fetch tool saw no script URLs or XHR endpoints, but it doesn't show raw HTML or network traffic, so this proves nothing either way.
- `docs/spec.md` section 07 says `robots.txt` was fully open (`Allow: /`, no Crawl-delay) when it was reviewed. Re-check it today.

### The repo today

- `scraper/` is a skeleton: `package.json` (`"type": "module"`, deps `playwright ^1.47.2`, `cheerio ^1.0.0`, no lockfile), `src/index.js` (prints a message), `.gitignore`.
- `.github/dependabot.yml` says `scraper/` isn't listed yet because it has no lockfile. Leave it as is; that's KAN-67.
- No CI job for `scraper/`. Leave it; that's KAN-67.

Read first: `CLAUDE.md` (standing rules, especially 5 and 7), `docs/spec.md` sections 02, 07, 13 and 14 only, and the three files in `scraper/`.

## Verify against reality, not memory

For each, say in the summary how you checked:

- The Node version on your machine (`node --version`), and the **current** Node LTS line from nodejs.org (fetch it, don't assume). Recommend one for KAN-67.
- The **current** published versions of `playwright` and `cheerio` (`npm view <pkg> version`), and of any HTTP client you'd recommend (or that Node's built-in `fetch` is enough). The skeleton's `^1.47.2` is stale; don't assume what the latest is.
- If you use Playwright for the investigation, install it **outside the repo** (or in a temporary folder you delete), so no lockfile or `node_modules` is committed by this ticket.

## The investigation

Use a real browser (Playwright, headed or headless) with network logging, plus plain HTTP requests (`curl` or Node `fetch`) to compare what the server sends with what the browser ends up showing.

**Be a good citizen while researching.** An identifiable User-Agent (e.g. `SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)`), at least a few seconds between requests, no parallel requests, no crawling beyond the pages above and the requests they make themselves. Keep the total small; report roughly how many requests you made.

Answer each question with evidence (request URL, method, status, headers, a short excerpt):

1. **Where each piece of data comes from.** For the team-games page and the league standings: is the data in the initial HTML response, or loaded later? If later, by which request: URL, method, parameters, request headers it needs (cookies, tokens, `X-Requested-With`, referer…), response format (JSON or an HTML fragment) and encoding.
2. **Can that request be called directly**, with plain HTTP and no browser, and get the same data? Try it. If it needs a cookie or a token, say where that comes from and how long it lasts.
3. **Is a headless browser required for anything in scope?** Yes/no per data source, with the reason. This is the key answer.
4. **Fields available**:
   - Fixtures/results: every field per game (date, time, home, away, venue, result, round/matchday, status such as postponed or cancelled, game id…). How to identify a game shown with `game_id=-1` stably across scrapes (e.g. round + teams?), and whether such a game keeps an id once scheduled.
   - Standings: every column (position, team, played, won, drawn, lost, goals for/against, difference, points…), and whether each row carries the team's id.
5. **Team identity across pages.** How a team is identified (`team_id`) on the games page and in the standings, so our club can be matched to its row. Note any team that appears without an id.
6. **Formats and quirks.** Character encoding; date and time formats and their timezone; how results are written (`4-1`, home-first or not, in RTL); empty or placeholder values; text concatenation (like the venue); anything that will trip a parser.
7. **Out of scope, only locate:** the league page's per-player statistics table (minutes, starts, cards, goals). Where does its data come from (same question as 1), and is it one request for the whole league or per team? Don't parse it. This is for KAN-70.
8. **Good-citizen data.** Today's `robots.txt` (quote the relevant lines). Response caching headers (`Cache-Control`, `ETag`, `Last-Modified`). Any rate limiting, CAPTCHA, WAF or bot protection you hit. Then propose a request budget for one scrape (one team's fixtures + its league's standings): how many requests, in what order, with what delay and timeout.
9. **Stability.** Anything suggesting the markup or endpoints change often (versioned asset URLs, build hashes, random class names), and which selectors or fields look stable enough to rely on.

## Deliverables

### 1. Findings doc

`scraper/docs/KAN-64-findings.md`, in English, with:

- A short summary on top: **the recommendation** (plain HTTP + parsing, or Playwright, per data source), in a few lines.
- The answers to questions 1–9, with evidence.
- A proposed **parsed output shape** for fixtures and for standings (field names, types, which fields are optional), as the starting point for the KAN-66 contract. Mark it as a proposal; KAN-66 decides.
- The recommended Node version and library list (with the versions you verified), and why.
- Open questions and risks for KAN-65–KAN-67.

### 2. Test fixtures

Save sample responses for the parser tests KAN-67 will write, under `scraper/test/fixtures/` (one file per source, e.g. `team-games-7617-s28.html`, `league-62-s28-standings.json` — names your call). For each:

- **Trim it** to the part the parser needs (the relevant table or the JSON), plus enough surrounding markup to keep the selectors meaningful. Remove scripts, ads, tracking pixels, images, logos and anything unrelated. We don't republish their site, and we don't want large files in the repo.
- A short `scraper/test/fixtures/README.md`: the source URL of each file, the date it was captured, and what was trimmed.
- At least one fixture should include a game with `game_id=-1` and one with a result, if the live page has both.

### No production code

No changes to `scraper/src/`, `scraper/package.json`, CI, Dependabot, docker-compose, backend or frontend. Any script you wrote to investigate stays out of the repo; put the key commands in the findings doc instead, so the investigation can be repeated.

## Docs

- `CLAUDE.md` / `README.md`: this ticket changes no architecture, setup or tooling, so I expect no change. If you think one is needed, say so in the summary instead of changing it.
- **`docs/spec.md`: do NOT edit it.** In the summary, list what is now out of date and where. I already know of these (confirm or correct): section 07 says nothing about how the data is fetched; section 02's diagram says the worker is run "as Cron / internal endpoint"; section 13's roadmap puts scraping (5) after the tactical board (4); section 07's "robots.txt" statement, if today's file differs.

## Commits

Small, focused commits, for example:

- `docs(scraper): add football.org.il investigation findings (KAN-64)`
- `test(scraper): add trimmed page fixtures for the parsers (KAN-64)`

Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Branch, commits (hash + message), files added, one line each.
2. Versions verified (Node, Node LTS, playwright, cheerio, any HTTP client) and how you checked each.
3. **The recommendation**: browser or not, per data source, in one line each, with the deciding evidence.
4. Answers to questions 1–9, short (the details are in the findings doc).
5. The proposed output shapes for fixtures and standings.
6. Fixtures: each file, its size, its source URL, what was trimmed.
7. How many requests you made to football.org.il in total, and the delay you used.
8. Spec: each out-of-date place (section + what's wrong).
9. Deviations from this prompt, and why.
10. Open ends and risks for KAN-65, KAN-66 and KAN-67 (e.g. ids that may change between seasons, a cookie or token needed for the data request, bot protection, markup stability).
11. Print the findings doc in full.
