# KAN-75: game-details spike — findings

Investigated on 2026-10-14 (19:51–19:52 Israel time) against Sderot FC (מ.כ. שדרות), Liga Alef South: `team_id=7617`, `season_id=28`. No production code. Four trimmed game pages are in `../test/fixtures/game-*.html` (see its README). Method and policy as in `KAN-70-findings.md`; this extends its section 7, which was based on one game.

## Summary

- **Everything is in the game page's initial HTML**: competition and round (`h1`), both teams with their `team_id`, the score, both lineups grouped as starters / substitutes / unused, per player the shirt number in that game, the name, `(GK)` / `(C)`, and every event with a minute: goal, yellow, **second yellow** (`yellow2`), **red**, sub off, sub on. A timeline (`#gameMoves`) repeats the events with the team.
- New against KAN-70 §7: the captain marker (`- (C)`, also `- (GK) (C)`), second yellow, straight red, minutes after 90 written as plain numbers (`91`, `93`, `98`, never `90+N`), a minute of **`-1`** (unknown), a **masked name** (`*****`), and a lineup that can miss an event the timeline has. No own goal or penalty was seen; the page's legend has classes for both (`own-goal`, `penalty`), so their markup is still unknown.
- **Cloudflare: passed 5 of 5** requests (team-games page + 4 game pages), production route rule, 0 blocks.
- **Cost: 1 document per game**, about 0.5–0.6 s each. A simulated scrape of team-games page + 2 game pages took **8.2 s**. Proposed: game pages last in the scrape, at most 2, so a typical scrape with everything is about 23 s.
- The cup game's page (`גביע המדינה ווינר מחזור 4`) has exactly the league game's structure.

## Method

- **Node** 24.21.0 (`~/.nvm/versions/node/v24.21.0/bin/node --version`; the shell's default is 22, not used). **Playwright** 1.64.0 (`scraper/package.json` `^1.64.0`, lockfile `node_modules/playwright-core` 1.64.0, `npx playwright --version` in `scraper/` under Node 24 → `Version 1.64.0`), **Chromium** build `chromium-1248` / `chromium_headless_shell-1248` from the local Playwright cache. Cheerio 1.2.0 (lockfile). Scripts lived in a scratch folder outside the repo and imported Playwright, Cheerio and `scraper/src/parseFixtures.js` by absolute path. No install, no lockfile or `node_modules` change.
- **Setup:** "docs" mode only: headless, User-Agent `SquadPulse-research/0.1 (+https://github.com/Galaviv15/Squad-Pulse)`, locale `he-IL`, a context `route` that let through same-origin documents only and aborted everything else (209 + 148 subresources aborted). `waitUntil: "domcontentloaded"`, 30 s timeout.
- **Runs:**
  - **A** (one simulated scrape): the team-games page, parsed with the production `parseGamesPage`, then Dimona–Sderot (`date === 2026-10-09`) and the latest Sderot home game, chosen by the script from that parse, with a random 3–5 s pause before each.
  - **B**: `1122634` (Sderot home, 1-2, chosen so our side has a goal at home, since A's home game was 0-0) and the cup game `1142007`.
- Every page was then dumped with one Cheerio script (groups, per-player number / name / suffix / moves, timeline nodes), and each fixture was checked to give the identical dump.

Key commands (scripts not committed):

```js
const ctx = await browser.newContext({ userAgent: UA, locale: "he-IL" });
await ctx.route("**/*", (r) =>
  r.request().resourceType() === "document" && r.request().url().startsWith(ORIGIN) ? r.continue() : r.abort());
const list = await page.goto(`${ORIGIN}team-details/team-games/?team_id=7617&season_id=28`, { waitUntil: "domcontentloaded", timeout: 30000 });
const played = parseGamesPage(await list.text()).filter((g) => g.homeScore !== null);
// pick, wait 3–5 s, then for each game:
await page.goto(`${ORIGIN}leagues/games/game/?game_id=${gameId}`, { waitUntil: "domcontentloaded", timeout: 30000 });
```

## Requests

Times UTC (Israel = UTC+3). Status / body length in characters. All `text/html; charset=utf-8`, `cache-control: private`, `cf-cache-status: DYNAMIC`, no `ETag` / `Last-Modified`.

| Run | At | Page | Status / size | Load |
|---|---|---|---|---|
| A | 16:51:07.6 | team-games `?team_id=7617&season_id=28` | 200 / 167,258 | 347 ms |
| A | 16:51:11.4 | game `1122670` (Dimona–Sderot) | 200 / 267,292 | 606 ms |
| A | 16:51:15.2 | game `1143937` (Sderot–Azor) | 200 / 264,687 | 517 ms |
| B | 16:52:08.5 | game `1122634` (Sderot–Kiryat Malachi) | 200 / 265,801 | 630 ms |
| B | 16:52:13.8 | game `1142007` (Rahat–Sderot, State Cup) | 200 / 263,294 | 619 ms |

Run A: 8.2 s from browser start to the last body, 3 sent / 209 aborted. Run B: 5.8 s, 2 sent / 148 aborted.

## Answers

### 1. `game_id` in the team-games list

- Each row is `<a class="table_row link_url" title="קישור לעמוד משחק" href="/leagues/games/game/?game_id=1122670">`, a relative link with `game_id` as the only parameter. `parseFixtures.js` already reads it (`parseGameId`, `gameId` in each game; `-1` → `null`).
- **It equals the game page's id**: the page is `?game_id=1122670` and its section is `<section class="game-details …" id="gfd_1122670" data-onlive="False">` (all 4 pages).
- **Finished vs not:** today's live list has 34 rows: 6 with an id and a result (all played), 28 with `game_id=-1`, time `00:00` and no result. No row has an id without a result, and no postponed game exists, so that case is unseen. Rule proposed: a game is finished when the row has a `gameId` **and** a result (`homeScore !== null`). On the game page, a finished game has `div.teams-names.past`, `#gTimeHolder.game-time.past` and `data-onlive="False"`; the parser should refuse a page without them.
- **Ids aren't chronological:** `1143937` (round 5, 01/10) is newer by id than `1122670` (round 6, 09/10). "Oldest first" must sort by the row's date (and time), not the id.
- The live list holds the same 6 played games as the committed `team-games-7617-s28.html` (2026-10-11), which **does** contain the Dimona game (`1122670`); the ids were read live anyway.
- Cup games aren't in this list (`1142007` isn't); KAN-76.

### 2. Our side

- The game page marks the sides in three consistent ways: the header `div.team-home` / `div.team-guest`, each with `<a href='/team-details/?team_id=…&season_id=28'><span>name</span></a>`; the groups' classes `home` / `guest`; and the links' `title` (`קבוצה - ביתית` / `קבוצה אורחת`). The score is `<div class='total'><i class='sr-only'>home</i>4 : <i class='sr-only'>guest</i>1</div>`, **home first** (the team-games list shows the result away-first).
- **The team-games row's home / away matches the game page on all 3 league games** (Dimona home / Sderot guest; Sderot home in `1143937` and `1122634`). Better: **the header's `team_id` is our `teamId`** on all 4 pages (`7617`), cup included, so the scraper can pick our side from the page itself without the list. Proposed: pick by `team_id`; fail if neither or both sides match.
- **Names:** the game page shows the same **short** names as the team-games list (`מ.ס. דימונה`, `מ.כ. שדרות`, `מכבי קרית מלאכי עירוני`, `הפועל אזור`, `הפועל רהט`). The timeline uses **full** club names (`מועדון ספורט דימונה`, `מועדון כדורגל שדרות`), like the cup list in KAN-70 §9.

### 3. The `h1`

`<h1>ליגה א' דרום מחזור 6\n   <span>פרטי המשחק</span>\n</h1>`: the text before the `span`, whitespace collapsed.

| Page | `h1` text |
|---|---|
| `1122670` | `ליגה א' דרום מחזור 6` |
| `1143937` | `ליגה א' דרום מחזור 5` |
| `1122634` | `ליגה א' דרום מחזור 2` |
| `1142007` (cup) | `גביע המדינה ווינר מחזור 4` |

- Always `<competition> מחזור <N>` here, the cup too. Parse with `^(.+) מחזור (\d+)$`; anything else is a parse error.
- **The cup's round disagrees between sources:** the game page says `מחזור 4`, the cup list (`GetTeamNatCupData`, KAN-70 §9) says `ו` for the same game. KAN-76 must not join the two by round.
- One page only per competition; no extra time in the cup sample (90 minutes, 1-4).

### 4. Per player

Groups under `section.game-details #teams .players-cont`, all 4 pages identical in order: header `div.home.clearfix` / `div.guest.clearfix` (`aria-hidden`, text `ביתית` / `אורחת`, no players), then per role a `div.title` and the two groups: `div.home.Active` / `div.guest.Active` (`aria-labelledby="GAME_PLAYER_TYPE_ACTIVE_HOME"`), `.Replacement` (`…_Replacement_HOME`), `.Bench` (`…_Bench_HOME`), and the coaches `div.home` / `div.guest` (`GAME_COACH_HOME`). Select a group by its role class (and side class), never by `div.home` alone, which matches the header and the coach too.

A player (the opponent's goalkeeper, name and id anonymised as in the fixture):

```html
<a href="/players/player/?player_id=900001&season_id=28" title='קבוצה - ביתית'>
    <div class="player">
        <span class="number">מס' 36</span>
        <span class="name"><b>שחקן יריב 1</b> - (GK) (C)</span>
        <span class="moves"></span>
    </div>
</a>
```

| Field | Markup | Seen |
|---|---|---|
| Player id | `a[href*="player_id="]`; starters `?player_id=N&season_id=28`, substitutes and unused `?player_id=N` | all 4 pages, both sides |
| Shirt number | `span.number`, `מס' N` | every player on every page (1–99); none missing. Coaches: `מ` |
| Name | `span.name > b`, first name first, 2–4 words | **`*****` once** (an Azor starter, `1143937`): masked, `player_id` still present |
| Goalkeeper | text after `b`: `- (GK)` | one starter per side, every page |
| Captain | `- (C)` or `- (GK) (C)` (GK first in the HTML; the screenshot's `(C) (GK)` is the RTL rendering) | one per side, every page; also on outfield players |
| Role | the group: `Active` = starter, `Replacement` = substitute who came on, `Bench` = unused | |

Events in `span.moves` (inner quotes as in the HTML):

| Event | Markup |
|---|---|
| Goal | no wrapper: `<img src='…/football.svg' class='svg-img' alt='' /><span class="sr-only">שער</span><span class="sr-only">דקה</span>34 ` (minute = the text node after the second `sr-only`). Two goals = the sequence twice (`1142007`, 88 and 92). |
| Yellow | `<span class='yellow'><span class="sr-only tooltip">כרטיס צהוב</span><span class="sr-only">דקה</span>28</span>` |
| Second yellow | the yellow, then `<span class='yellow2'><span class="sr-only tooltip">2 כרטיס צהוב</span><span class="sr-only">דקה</span>88</span>`. No `red` span added (2 cases, both opponents). |
| Red (straight) | `<span class='red'><span class="sr-only tooltip">כרטיס אדום</span><span class="sr-only">דקה</span>40</span>` (`1122670`: one per side, Sderot's after his own goal at 38) |
| Sub off | `<span class='change-down'><span class="sr-only">יצא</span><span class="sr-only">דקה</span>50</span>` |
| Sub on | `<span class='change-up'><span class="sr-only">נכנס</span><span class="sr-only">דקה</span>57</span>` |
| Own goal, penalty goal | **not seen.** The timeline's legend has `<span class="own-goal"><img src="…/own_goal.svg" alt="שער עצמי" />שער עצמי</span>` and `<span class="penalty"><img src="…/penalty.svg" alt="פנדל" />פנדל</span>`, so they likely differ from a goal only by the image (and maybe the `sr-only` text). |

**Goal detection.** A goal has no wrapper class, so the icon is the only thing that tells its kind. The parser must read, in `span.moves` and in the timeline, the icon's `src` **file name** together with the `sr-only` texts:

- `span.moves`: `<img src='SVTemplate/img/League/football.svg' class='svg-img' alt='' />` + `<span class="sr-only">שער</span>` + `<span class="sr-only">דקה</span>` + the minute as a text node.
- Timeline: `<div class='node goal team1 '> <img src = 'SVTemplate/img/League/football.svg'  alt='' /><span class="sr-only">שער</span>…`.
- **`football.svg` = goal.** Any other icon file in a goal-like sequence (an `img` followed by `sr-only` texts in `span.moves`, or an icon in a timeline event node), e.g. `own_goal.svg` or `penalty.svg`, is a **parse error for that game** until its markup is pinned with a fixture. Keying on the `sr-only` `שער` alone would silently count an own goal as our player's goal.
- **Where the rule applies:** only inside `span.moves` and inside timeline **event nodes**, `#gameMoves > div.timeline > div.node`. **Never the legend**: `#gameMoves > div.keys.row-has-icons`, a sibling of `div.timeline` (not inside it), which is on every page and always holds `football.svg`, `own_goal.svg` and `penalty.svg`. Exclude it by structure (select event nodes as children of `div.timeline`), not by position:

  ```html
  </div></div><div class="keys row-has-icons"><span class="bold">מקרא: </span><span class="change-up">נכנס</span><span class="change-down">יצא</span>…<span class="own-goal"><img src="SVTemplate/img/general/he/own_goal.svg"  alt="שער עצמי" />שער עצמי</span><span class="penalty"><img src="SVTemplate/img/general/he/penalty.svg" alt="פנדל" />פנדל</span></div>
  ```

  Checked on all 4 fixtures: every `own_goal.svg` / `penalty.svg` (1 each per page) is in the legend; the event nodes' icons are all `football.svg` (5, 0, 3, 5).
- The fixtures keep every event icon as captured (`football.svg` in moves, event nodes and legend; `own_goal.svg` and `penalty.svg` in the legend only), so this rule is testable; only logos were removed.

- **Minutes after 90 are plain numbers**: goals at `93` (Dimona) and `98`, `92`; subs at `91`, `98`; second yellow at `92`. No `90+N` anywhere on the 4 pages. So a first-half stoppage minute (e.g. 45+2) would also be a plain `47`, indistinguishable from the second half.
- **Minute `-1`**: a Sderot substitute's yellow in the cup game (`<span class='yellow'>…<span class="sr-only">דקה</span>-1</span>`; the timeline shows `-1` too). Treat as unknown (`null`). No event without any minute.
- **Events can belong to any role**: an unused Rahat player has a yellow (88); a Kiryat Malachi substitute was booked at 50 and came on at 65. Don't validate events against the role.
- Order inside `span.moves` was chronological in every case, but sort by minute anyway.

KAN-70 §7's selectors, per page:

| Selector / claim | 1122670 | 1143937 | 1122634 | 1142007 |
|---|---|---|---|---|
| `section.game-details .players-cont` | ✓ | ✓ | ✓ | ✓ |
| `div.home.Active` / `div.guest.Active`, `.Replacement`, `.Bench`, coaches | ✓ | ✓ | ✓ | ✓ |
| `a[href*="player_id="] > div.player` | ✓ | ✓ | ✓ | ✓ |
| `span.number` = `מס' N` | ✓ | ✓ | ✓ | ✓ |
| `span.name > b`, first name first | ✓ | ✓ (one `*****`) | ✓ | ✓ |
| `- (GK)` | ✓ | ✓ | ✓ | ✓ (and `- (C)` / `- (GK) (C)` added) |
| `span.yellow`, `change-down`, `change-up`, bare goals | ✓ | ✓ | ✓ | ✓ |
| starters' links `&season_id=28`, others none | ✓ | ✓ | ✓ | ✓ |
| No minutes played per player | ✓ | ✓ | ✓ | ✓ |

No contradiction; the additions are `(C)`, `yellow2`, `red`, `-1`, `*****`.

### 5. Totals and consistency

| Game | Our side | Score (ours) | Goals in our players' `moves` | "שערים:" line (ours) | Starters / subs / unused | Subs on / off (ours) |
|---|---|---|---|---|---|---|
| `1122670` Dimona–Sderot | guest | 1 | 1 (38) | `אלדדה עמאר 38` | 11 / 5 / 4 | 5 / 5 |
| `1143937` Sderot–Azor | home | 0 | 0 | empty: `<span class="goalers"></span>`, no `h4` | 11 / 5 / 4 | 5 / 5 |
| `1122634` Sderot–Kiryat Malachi | home | 1 | 1 (78) | `קוזניצוב רוסלן 78` | 11 / 5 / 4 | 5 / 5 |
| `1142007` Rahat–Sderot (cup) | guest | 4 | 4 (36, 51, 88, 92) | `שרעבי לירון 36, קוזניצוב רוסלן 51, אבוקסיס ירין דוד 88, אבוקסיס ירין דוד 92` | 11 / 5 / 1 | 5 / 5 |

- Goals match the score and the scorers line everywhere, on both sides. The scorers line is **surname first** (like the stats table), comma-separated, one entry per goal. Without own goals that's expected; with one, "our players' goals = our score" will no longer hold.
- Starters are 11 in all 8 groups; substitutes 4–5, unused 1–4.
- No `player_id` appears twice on a page; coaches (`a[href*="coach_id="]`, `span.number` = `מ`, `b.name`) are never matched by the `player_id` selector.
- **The lineup can miss an event**: in `1143937`, Azor made 5 changes but its starters show only 4 `change-down`; the timeline has the fifth (`77'`, `***** (77)` off). Sub-on and sub-off counts can differ; don't fail on it.
- Referees (`div.judge`, names and `referee_id`) are in the same `#teams` panel; not needed.

### 6. Request budget

- Measured: team-games page 0.35 s, game pages 0.52–0.63 s, ~265,000 characters each. Run A (team-games + 2 game pages, production pauses) 8.2 s.
- **Proposed position: last**, after `LeagueTable`: team games → player stats → league page → `LeagueTable` → game 1 → game 2.
  - The selection needs only the team-games page's body, which step 1 already parsed; a game page is a plain document and needs no page before it.
  - Game details are the lowest priority and are fetched once; last means a slow site loses them (to the cap) before anything else, and they're simply picked again next refresh.
- **Timeout:** 30 s, like the other documents. The scraper's `request()` already shrinks it to the time left.
- **Typical time:** about 14 s (KAN-70, 4 requests) + 2 × (3–5 s pause + 0.6 s) ≈ **23 s**, well inside 90 s.
- **Worst case:** 30 + 5 + 20 + 5 + 30 + 5 + 20 + 5 + 30 + 5 + 30 = 185 s of timeouts, against the 90 s cap. That doesn't fit, but neither does today's (115 s with 4 requests): the cap is what bounds a scrape, not the count, and with the game pages last the cap only ever cuts them. The single retry per scrape stays shared (a game page gets it only if nothing earlier used it). Proposed: start a game page only if at least 15 s of the cap remain, otherwise report it "not attempted: time limit". So 2 is fine; no number of game pages would make the worst case fit.
- **Volume:** +2 requests per scrape at most, and only while finished, unstored games exist. Once caught up: about 1 extra request per game week. A season of ~30 league games costs ~30 game requests in total. With the 10-minute lock: at most 36 requests per club per hour (24 today).

### 7. Good citizen

- `robots.txt`: not fetched (unchanged in KAN-70, 2026-10-13, `Allow: /`).
- Caching: unchanged; game pages are `cache-control: private`, `cf-cache-status: DYNAMIC`, no validators, so nothing to revalidate with. A finished game is fetched once by design.
- Cloudflare: 5 of 5 passed, no challenge, no `403`.
- **Requests: 5 in total** (budget 10), 0 blocks, 0 retries. Delays within runs: 3.2 s, 3.4 s (A) and 4.6 s (B) between a response and the next request. **Between runs: 53 s** (A's last request 16:51:15, B's first 16:52:08), short of the 60 s asked; a mistake on my side.
- The later fixture changes (event icons restored, opponent anonymised) were made from the saved captures and needed no new requests.

## Proposed parsed output (a proposal; KAN-79 decides the contract)

```ts
// One game's details, our team's side only
type GameDetails = {
  gameId: number;                       // the id we requested; checked against section#gfd_<id>
  competition: string;                  // h1 before " מחזור N", e.g. "ליגה א' דרום", "גביע המדינה ווינר"
  round: number;                        // N from "מחזור N" (a parse error if absent)
  side: "HOME" | "AWAY";                // the header side whose team_id = the job's teamId
  homeTeamName: string;                 // short names as on the page
  awayTeamName: string;
  homeScore: number;                    // from div.total, home first
  awayScore: number;
  players: Array<{                      // our side's three groups only; never the other team's, never coaches
    playerId: number;                   // from the link (player_id only; season_id may be absent)
    name: string | null;                // first name first, trimmed; null when the site masks it ("*****")
    shirtNumber: number | null;         // from "מס' N"; null if missing or empty (unseen)
    role: "STARTER" | "SUBSTITUTE" | "UNUSED";   // Active / Replacement / Bench
    goalkeeper: boolean;                // "(GK)"
    captain: boolean;                   // "(C)"
    events: Array<{
      kind: "GOAL" | "YELLOW" | "SECOND_YELLOW" | "RED" | "SUB_OFF" | "SUB_ON";
      minute: number | null;            // as shown (91, 93, 98 are plain); null for "-1"
    }>;                                 // sorted by minute, unknown last
  }>;
};
// Key: (teamId, gameId). Written once; a game is never fetched again.
```

- `OWN_GOAL` / `PENALTY_GOAL` are added once their markup is pinned (see "Goal detection" in 4).

**Checks.** Two kinds, so a real but unusual game (a forfeit, an abandoned game, an own goal) is still stored instead of becoming permanently unreadable:

- **Parse error** (the game fails, nothing of it is sent):
  - no `section#gfd_<id>` matching the id requested;
  - the page isn't marked finished (`div.teams-names.past`, `#gTimeHolder.past`, `data-onlive="False"`);
  - neither or both header sides match our `team_id`;
  - an `h1` not matching `^(.+) מחזור (\d+)$`;
  - an unknown event: a `span` class in `span.moves` outside `yellow`, `yellow2`, `red`, `change-down`, `change-up`, an `sr-only` text other than `שער` / `דקה` in the bare sequence, or an icon file other than `football.svg` in `span.moves` or in a timeline event node (`#gameMoves > div.timeline > div.node`; the legend `#gameMoves > div.keys` is never checked, see "Goal detection" in 4);
  - a player link without a `player_id`.
- **Warning only** (the game is stored and sent; the scraper logs it, and the part may carry it):
  - starters ≠ 11;
  - no `(GK)` among the starters;
  - our players' goals ≠ our score;
  - subs on ≠ subs off (seen on a real page, 5 above).

**Left out:** minutes played (not on the page), the other team's players, coaches, referees, venue, the timeline, any photo or logo. `homeTeamName` / `awayTeamName` / scores repeat the fixtures and may be dropped by KAN-79.

## Proposed trigger addition and scrape sequence

Trigger (same style as `docs/scraper-backend-contract.md`):

```
{
  …,
  "gameDetails":    boolean,   // optional, absent = false
  "storedGameIds":  [int]      // optional, absent = []; only with gameDetails; at most 100, each 1..2147483647, no duplicates
}
```

| Field | Type | Rules | Meaning |
|---|---|---|---|
| `gameDetails` | boolean | optional; absent = `false` | Fetch the details of up to 2 finished games not yet stored (KAN-79, KAN-80). Needs the team-games page, like `fixtures`. |
| `storedGameIds` | int array | optional; absent = `[]`; ≤ 100 entries | The games the backend already has details for (this club's team and season). The scraper never fetches them. |

A season's ~30–40 ids are well under the trigger's 4 KB body limit (100 ids ≈ 0.8 KB).

Sequence:

1. Team-games page (document, 30 s), if `fixtures`, `playerStats` or `gameDetails`.
2. Player stats (in-page fetch, 20 s), if `playerStats`.
3. League page + `LeagueTable`, if `standings`.
4. If `gameDetails`: from step 1's rows, take those with a `gameId`, a result and a kick-off at least 24 h ago (Israel time; see "Decided for KAN-79"), drop `storedGameIds`, sort by date, then kick-off time, then `gameId`, take **the first 2** (oldest first). For each: pause 3–5 s, `GET /leagues/games/game/?game_id={id}` (document, 30 s), only if ≥ 15 s of the cap remain. Parse our side.
5. A block stops everything left, as today. If step 1 failed, the game details are `FAILED` "not attempted", like the stats.

Result: a fourth part `gameDetails`, `OK` with `games: GameDetails[]` and a per-game failure list (`{gameId, reason, detail}`), so one unreadable game doesn't drop the other; KAN-79 decides.

## Decided for KAN-79 (Gal, 2026-10-14)

These are **decided**, not proposals.

- **Fetch only games at least 24 h after kick-off** (Israel time, the row's date and time). A game is fetched once and never again, so a page read minutes after the final whistle, before the association has entered every event, would stay wrong for good. Unverified how fast the site fills a page in; 24 h is a margin, not a measurement.
- **Failed games.** The backend keeps an attempt count per `gameId`. After 3 failed attempts it stops asking for that game and shows it as "details unavailable". Without this, one broken page (new markup, a page error) takes one of the 2 slots on every refresh, forever. How the skip reaches the scraper (inside `storedGameIds` or a separate list) is KAN-79's call. Suggestion for KAN-79, not a decision: a new scraper version can reset the counts.
- **Our side is picked by the header `team_id`** (`div.team-home` / `div.team-guest` link = the job's `teamId`); neither or both matching is a parse error.
- **Selection order:** finished, unstored games sorted by date, then kick-off time, then `gameId` (ids aren't chronological); at most 2 per scrape, oldest first.

## Open questions and risks

- **KAN-79 (backend):**
  - The decisions above.
  - Shirt numbers per appearance only (decided); a player can wear a different number per game.
  - `playerId` is the stats link key (KAN-72), so appearances map to squad players through the existing links; a masked name (`null`) still has a `player_id`.
  - Never expose `game_id` / `player_id` (spec §09); the game needs an id of our own, as in KAN-72.
  - Store `minute` as shown, nullable; no `90+N` exists to model. A first-half stoppage minute can't be told from the second half's.
  - "Our goals = our score" breaks with own goals; don't reject a game for it.
- **KAN-80 (scraper):**
  - Own goal and penalty markup unknown: fail loudly, add a fixture from the first such game.
  - Pin in tests (fixtures): picking our side by `team_id` (home in `1143937` / `1122634`, away in `1122670` / `1142007`); `(C)` and `(GK) (C)`; `yellow` + `yellow2`; `red`; minute `-1` → null; minutes 91–98; two goals by one player; a goal and a red for the same player; a booked unused player (opponent side, `1142007`); a 1-player bench; `*****`; coaches and the header `div.home` excluded; links with and without `season_id`; a page whose only `own_goal.svg` / `penalty.svg` are in the legend (`#gameMoves > div.keys`) parses fine (all 4 fixtures).
  - Key goals on the icon's file name plus the `sr-only` texts ("Goal detection" in 4); the fixtures keep the icons. An own-goal or penalty icon is a parse error until a fixture pins it.
  - The fixtures' opponent side is anonymised (`שחקן יריב N`, ids `9000NN`, see the fixtures README); it exists only to test side-picking.
  - The goal's minute is a bare text node: walk `span.moves`' child nodes in order, don't use `.text()` on the whole.
  - Sort selected games by date, not id.
- **KAN-81 (frontend):**
  - Minutes as shown (`93'`), unknown minute shown as no minute, never `-1`.
  - A masked name must render as something neutral (e.g. "שם לא מוצג"), never `*****`.
  - Shirt number from the appearance, never `Player.jerseyNumber`.
  - Decided (Gal, 2026-10-14): our side only. The opponent appears by name and score; its players are never stored, sent or shown.
- **KAN-76 (cup games):**
  - The cup game page has the league page's structure; its `h1` gives `גביע המדינה ווינר מחזור 4`, while the cup list says round `ו` for the same game. Identify a cup game by `game_id` where it has one; a "competition + round + teams" identity must use one source's round consistently.
  - Cup lists use full club names; game pages use short names; the timeline full names.
  - Extra time and penalties in a cup game were not seen (the sample ended in 90).
