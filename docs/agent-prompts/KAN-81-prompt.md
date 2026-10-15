# KAN-81: Frontend: game details screen from the matches screen (lineup, per-game shirt numbers, goals, cards, subs)

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `a119cee`, the KAN-80 merge, PR #60). If it doesn't, stop and report. Then create the branch `feature/KAN-81-game-details-screen`.

## Context

Jira KAN-81, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-79 (done: the backend stores one played game's details, our side only, adds `detailsId` / `detailsState` to every fixture, and serves `GET /matches/games/{id}`) and KAN-80 (done: the scraper fetches up to 2 finished game pages per scrape and sends them; every refresh now requests game details). Related: KAN-69 (the matches screen this builds on), KAN-74 (the stats screen: the linked-player name pattern), KAN-76 (cup games, later), KAN-82 (own goal / penalty / no-lineup pages, later: until then such games stay `PENDING` / `UNAVAILABLE`), KAN-78 (frontend tests fail on Node 24: open).

The backend is the source of truth for the shapes: `backend/src/main/java/com/squadpulse/match/GameDetailsResponse.java` and `FixturesResponse.java` (`Game.detailsId`, `Game.detailsState`, `DetailsState`). Read them; don't copy shapes from this prompt.

**This is a frontend-only ticket.** No change under `backend/` or `scraper/`.

A mockup was approved by Gal before this prompt (Design canvas "KAN-81 Game Details Mockup"). The decisions below are what it shows; where the mockup and this prompt differ, this prompt wins.

## Rules (from the ticket and earlier decisions; not up for change)

- **Our side only.** The opponent is a name and a score. Nothing about the opponent's players is shown (the API doesn't send it anyway).
- **No per-game minutes played** anywhere. Only the sub-on / sub-off minutes, as events.
- **No football.org.il link, id, photo or logo** anywhere. The only id in a URL is our opaque `detailsId`.
- **The per-game shirt number is not the squad's jersey number.** Never read or show `Player.jerseyNumber` here, and never write anything to the squad.
- **Minutes as the site gives them:** `93′`, never converted to `90+3′` (an absolute minute can't be split into stoppage time reliably: `46` can be `45+1` or the second half's first minute).
- **`GK` stays in English** in the Hebrew UI (CLAUDE.md rule 3). The captain marker is Hebrew (`קפטן`: not a position code).
- Hebrew / RTL, every string through i18n (rule 6), the existing test rules (MSW, console guard, unmocked-request guard).
- Every time and "today" in `Asia/Jerusalem` through the existing helpers (`lib/matches/israelTime.ts`), never the browser's zone.

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Route and title

- New lazy route `matches/games/:detailsId` under the same protected layout as `matches` (its own chunk, like `matches` and `stats`), `handle.titleKey` = a new key, `פרטי משחק`. The shell renders that title as the page's `<h1>` (see `app/pageTitle` / `RouteHandle`): **don't add a second `<h1>`**; the game header below is a section with its own `<h2>`-level heading.
- A path helper next to `MATCHES_PATH` (`lib/matches/tab.ts`, or a new `lib/matches/paths.ts` if that reads better): `gamePath(detailsId)` → `/app/matches/games/<encodeURIComponent(id)>`.
- A "חזרה למשחקים" link at the top, to `MATCHES_PATH` (the games tab is the default). A link (`<Link>`), not `navigate(-1)`: a user may land here from a shared URL.
- The sidebar keeps "משחקים" as the current item on this route: check how the sidebar decides `aria-current` (`components/shell/Sidebar.tsx`) and make it match `matches/games/*` too, with a test.

### 2. Types (`lib/matches/types.ts`)

- `Game` gains `detailsId: string | null` and `detailsState: DetailsState | null`, and `DETAILS_STATES = ["AVAILABLE", "UNAVAILABLE", "PENDING"] as const`, mirroring `FixturesResponse` (comments say when each is `null`).
- New types mirroring `GameDetailsResponse`, `Appearance`, `Event`, `ScrapedGameDetails.Role` and `EventKind` (`GOAL`, `YELLOW`, `SECOND_YELLOW`, `RED`, `SUB_ON`, `SUB_OFF`), and the linked player ref (reuse the existing `StatsPlayerRef` type if it is the same shape as `PlayerStatsResponse.PlayerRef`; say which).
- **Name clash:** `components/matches/GameRow.tsx` already exports a component named `GameDetails` (date, time, venue), used by the dashboard. Don't add a second export called `GameDetails`. Name the new things distinctly (e.g. type `PlayedGameDetails`, page `GamePage`, components under `components/matches/game/`). Renaming the existing component is **not** in scope.

### 3. Data (`lib/matches/queries.ts`)

- `gameDetailsQuery(detailsId)`: key `[...MATCHES_DATA_QUERY_KEY, "game", detailsId]`, `GET /matches/games/{encodeURIComponent(id)}`. Under `MATCHES_DATA_QUERY_KEY` on purpose: the end of a refresh run already invalidates that prefix, so an open game page refetches like the stats do (KAN-74). No refresh control on this page.
- **404 is the not-found state**, not an error with a retry. Check how `apiJson` rejects (status on the error) and what `shouldRetryQuery` (`lib/queryClient.ts`) does with a 404; a 404 must not be retried. Prove both with tests.
- **Our team's name is not in the game response** (only `ownSide` and `opponentName`). Read it from the fixtures query (`useFixtures`, `teamName`), already cached when the user comes from the matches screen. Render rule:
  - show the loading state until the game query has data **and** the fixtures query has settled (success or error);
  - fixtures failed, or `teamName` is `null` → a fallback label `הקבוצה שלנו` (i18n), **no** error alert for the fixtures (the page's subject is the game);
  - the game query's own error / 404 decides the page state, whatever the fixtures did.

### 4. Matches screen: the action and the row states (`GameRow` / `GamesList`, matches screen only)

Per game, from `detailsState` only:

| `detailsState` | Shown under the scoreline |
|---|---|
| `AVAILABLE` (and `detailsId` not `null`) | a **visible link styled as an outline button**, `פרטי משחק` + a chevron icon, to `gamePath(detailsId)`. Not a clickable row. Its accessible name includes the opponent and the date (several identical buttons on one page), e.g. `פרטי המשחק מול מכבי יבנה, 05.09.2026`. |
| `PENDING` | muted text: `פרטי המשחק עוד לא נאספו. הם יתווספו באחד הרענונים הבאים.` |
| `UNAVAILABLE` | muted text: `פרטי המשחק לא זמינים כרגע.` |
| `null` (not played) | nothing |

- `AVAILABLE` with a `null` `detailsId` can't happen per the backend; treat it as nothing shown (no broken link), with a test.
- **The dashboard is out of scope**: the next-match card shows an upcoming game. Check that whatever you change in `GameRow.tsx` doesn't change the dashboard cards (`MatchCards.tsx` imports `GameDetails` and `SideBadge` from it); the existing dashboard tests must pass unchanged.
- Keep the row's existing structure and tests; the action goes in a footer of the row (as in the mockup), on the inline-end side.

### 5. The game page: header

A card with:

- **Competition and round:** `<competition> · מחזור <round>` (both from the response, e.g. `ליגה א' דרום · מחזור 6`), as the section's heading.
- **Badges:** home / away (`SideBadge` pattern) and the result (the existing win / draw / loss badge and its variants).
- **The scoreline:** home team, home score, separator, away score, away team, each its own element in DOM order: reuse `Scoreline` (it already handles the RTL reason in its comment). It takes a `Game` today: adapt it with the smallest change (e.g. a narrower props type both callers satisfy), not a copy. Our team in bold. Larger than in the list.
- **Date and time:** the date with its weekday (the existing `GameDate` formatting) and the kickoff time, or the existing "not set yet" text when `kickoffTime` is `null`. No venue (the game response has none; don't pull it from the fixtures).
- **A scope note** (muted, small): `נתוני משחק זה בלבד, במסגרת <competition>. מסך הסטטיסטיקה סופר את כל המסגרות יחד.` (the ticket: per-game data is labelled by competition and doesn't add up to the all-competitions table until KAN-76).
- **Last updated** with the existing `matches.lastUpdated` text and `formatInstant(lastUpdatedAt)` (spec §07).

### 6. The game page: our lineup

A card headed `ההרכב שלנו`, then three groups **in this order**, each with a heading and its count, e.g. `הרכב פותח (11)`:

| Role | Heading |
|---|---|
| `STARTER` | `הרכב פותח` |
| `SUBSTITUTE` | `נכנסו מהספסל` |
| `UNUSED` | `לא שותפו` |

- Players **in the response's order** within a group (the backend's order; don't re-sort). A group with no players is not rendered.
- No players at all → an empty state inside the card: `אין הרכב שמור למשחק הזה.` (The contract requires players today; this is defensive, for KAN-82's no-lineup pages.)
- Each group is a list (`<ul>`), one `<li>` per player.

**Per player row:**

- **Shirt number** in a round chip, `tabular-nums`. `null` → `–`. The chip has screen-reader text (`מספר חולצה 9` / `מספר חולצה לא ידוע`).
- **Name:**
  - `linkedPlayer` set → a `<Link>` to the player card (`playerPath(linkedPlayer.id)`) showing `linkedPlayer.fullName`, plus the existing `ReleasedBadge` when `linkedPlayer.active` is `false`: the same pattern as `SquadPlayerCell`'s `PlayerName` (KAN-74). Extract and share it if that's a small move; otherwise mirror it and say why.
  - not linked → `name` as plain text.
  - `name` `null` and not linked → muted italic `שם לא מוצג באתר`.
- **Markers:** `GK` (English, `lang="en"`) when `goalkeeper`; `קפטן` when `captain`. Text badges, not colour alone.
- **Events**, in the response's order (the backend sorts by minute, unknown last), wrapping on narrow screens. Each event = an icon + the minute:
  - `GOAL`: a football icon. Check whether the **installed** `lucide-react` has a fitting football icon (open its type definitions; don't guess an icon name). If it doesn't, a small inline SVG component with `aria-hidden`.
  - `YELLOW` / `RED`: a small card shape in `--card-yellow` / `--card-red` (the tokens from KAN-74).
  - `SECOND_YELLOW`: its own icon, yellow and red cards overlapping. One event = one icon (don't add a separate red).
  - `SUB_ON`: an up arrow; `SUB_OFF`: a down arrow (lucide). Direction carries the meaning, so colour (`--success` / `--danger` or the existing equivalents: no new hex) is a second cue, never the only one.
  - **Minute:** `93′` (prime, U+2032), inside `<bdi dir="ltr">` so the mark stays on the right side of the digits in RTL. `null` → icon only, no minute.
  - **Accessibility:** the icon is `aria-hidden`; each event has **visually hidden text** (`sr-only`), e.g. `שער בדקה 23`, `כרטיס צהוב שני בדקה 81`, `נכנס בדקה 60`, `שער, דקה לא ידועה`. Not `aria-label` on a `<span>` (not reliably read).
- **A legend** at the bottom of the card: each icon with its label, plus the line `הדקות כפי שבאתר ההתאחדות.`

Mobile (≤ phone width): the events go on their own line under the name, as in the mockup's phone board. No horizontal page scroll.

### 7. The game page: states

| State | Shown |
|---|---|
| loading (see 3) | a skeleton in the header card's place, `aria-busy` on the region, no layout jump when data comes |
| `404` | a card: `המשחק הזה לא נמצא.` + `ייתכן שהקישור ישן או שהנתונים עודכנו מאז.` + a `חזרה למשחקים` link. Covers an unknown id, another club's, and one of an earlier mapping (all `404` from the backend). |
| any other error | an alert `לא הצלחנו לטעון את פרטי המשחק.` with a `ניסיון נוסף` button that refetches (the KAN-74 pattern) |
| data | header + lineup |

Use the existing state components (`MatchDataStates.tsx`) where they fit; don't fork them.

### 8. Permissions

`VIEW_ONLY` and up see everything on this page, including the player-card links (the card itself is `VIEW_ONLY`). Nothing on this page writes. Prove `VIEW_ONLY` with a test.

## Out of scope

Any change under `backend/` or `scraper/`; the dashboard; the opponent's lineup or scorers; per-game minutes played; a refresh control on the game page; linking / unlinking players from this page; renaming the existing `GameDetails` component; cup games (KAN-76); own goal / penalty display (KAN-82); KAN-78 (Node 24 test failures).

## Verify against reality, not memory

Check against what's installed (`frontend/package.json`, `frontend/package-lock.json`, `node_modules`), not general knowledge, and say in the summary **how** you checked each:

- **Node:** the frontend CI job runs on **Node 22** (`.github/workflows/ci.yml`); run the frontend tests on Node 22 (`node --version` in the shell you actually run them in). KAN-78 makes some tests fail on Node 24: don't "fix" that here, and don't report a Node 24 failure as yours.
- **react-router** (resolved version from the lockfile): the lazy-route API and nested path matching you rely on, as the existing routes in `app/router.tsx` use them.
- **TanStack Query v5** (resolved version): that invalidating `MATCHES_DATA_QUERY_KEY` reaches the new key (prefix match), and the retry decision on a `404` through `shouldRetryQuery`. Prove both with tests, not prose.
- **lucide-react** (resolved version): every icon name you import exists in the installed type definitions; whether a football icon exists.
- **i18n:** check the existing matches i18n test (`lib/matches/i18n.test.ts`) and extend it to the new keys if it checks key coverage.

## Read first

`CLAUDE.md` (standing rules, especially 3, 6 and 7), then only:

- Backend shapes: `match/GameDetailsResponse.java`, `match/FixturesResponse.java`, `match/MatchController.java` (the `games/{id}` mapping and its Javadoc), `match/ScrapedGameDetails.java` (the `Role` and `EventKind` enums)
- `frontend/src/lib/matches/`: `types.ts`, `queries.ts`, `tab.ts`, `labels.ts`, `israelTime.ts`, `i18n.test.ts`
- `frontend/src/components/matches/`: `GameRow.tsx`, `GamesList.tsx`, `MatchDataStates.tsx`
- `frontend/src/components/stats/SquadPlayerCell.tsx` (`PlayerName`), `frontend/src/components/squad/PlayerBadges.tsx` (`ReleasedBadge`), `frontend/src/lib/squad/paths.ts`
- `frontend/src/pages/matches/MatchesPage.tsx` and its test; `frontend/src/pages/stats/StatsPage.tsx` (the error / retry pattern)
- `frontend/src/app/router.tsx`, `app/pageTitle` (the `RouteHandle`), `components/shell/Sidebar.tsx`
- `frontend/src/lib/api/client.ts` (how errors carry the status), `frontend/src/lib/queryClient.ts`
- `frontend/src/test/msw/matches.ts` and the test setup (console guard, unmocked-request guard)
- `frontend/src/index.css` (the colour tokens; no new hex in components)
- The Hebrew locale file the matches strings live in
- `docs/spec.md` sections 07, 08, 13 and the status line: to list what goes out of date, **not** to edit

## Test data (MSW)

- `test/msw/matches.ts`: `gameBody` / `playedGame` gain `detailsId` and `detailsState` with sensible defaults (played → `AVAILABLE` with an id; not played → both `null`), so existing tests keep their meaning. `fixturesBody` gets one `PENDING` and one `UNAVAILABLE` played game **only if** that doesn't change what existing tests assert; otherwise add them in the new tests.
- A handler for `GET /matches/games/:id` with a full `gameDetailsBody()`: 11 starters (one `GK`, one captain), 3 substitutes, 4 unused (one `GK`, one with `name: null`, one with `shirtNumber: null`), linked players (one released), and events covering every kind, two goals by one player, `YELLOW` then `SECOND_YELLOW` for one player, a sub off and on at the same minute, a minute of `93`, and one `null` minute. Made-up names only.
- An unknown id → `404` with the backend's error body shape (check how `MatchController` / the global handler answers a 404 today).

## Tests

Follow the existing style (Vitest, Testing Library, MSW). At least:

**Matches screen**

- `AVAILABLE` → the link exists, its accessible name includes opponent and date, and its `href` is `/app/matches/games/<id>`; clicking it renders the game page.
- `PENDING` / `UNAVAILABLE` → their texts, no link; not played → neither.
- `AVAILABLE` with `detailsId: null` → nothing.
- The dashboard tests pass unchanged.

**Game page**

- Header: competition and round; home / away and result badges; scores in DOM order home → away for both a home and an away game (the KAN-69 RTL rule); our team's name from the fixtures; the fallback name when the fixtures fail (and no fixtures error shown); date with weekday; "not set" time; last updated in Israel time; the scope note.
- Lineup: three groups in order with their counts; an empty group not rendered; players in response order; shirt number and `–`; `GK` and `קפטן`; linked → link to the player card with our name; released → the badge; masked name text.
- Events: every kind's sr-only text, with and without a minute; `93′` (not `90+3`); the order kept; `SECOND_YELLOW` = one event.
- Legend present.
- States: loading; `404` → not-found card with the back link, and **no retry request** (count requests in the handler); `500` → alert, `ניסיון נוסף` refetches and then shows the data.
- Refresh: invalidating `MATCHES_DATA_QUERY_KEY` refetches the game query (a direct test on the query client is fine).
- `VIEW_ONLY` sees everything; no write request is made anywhere on the page.
- No football.org.il URL or id in the rendered DOM (assert no `football.org.il` substring and no `href` outside `/app/`).
- Sidebar: "משחקים" is current on `/app/matches/games/<id>`.

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. show the button for `PENDING`;
2. drop the opponent / date from the button's accessible name;
3. take the team name from `opponentName` (or always `HOME`);
4. render the score as one `"3–1"` string;
5. sort players by shirt number;
6. render `UNUSED` before `SUBSTITUTE`;
7. drop the `<bdi>` around the minute (the test should check the element, not the visual);
8. show `90+3′` for `93`;
9. retry on `404`;
10. put the game query outside `MATCHES_DATA_QUERY_KEY`;
11. show `name` instead of `linkedPlayer.fullName` for a linked player;
12. translate `GK`.

Report each and whether it was caught.

Run `npm run lint`, `npm run format:check` (or the repo's equivalents: check `package.json`), `npm test` and `npm run build` in `frontend/` on Node 22. Report the frontend test count before (on master) and after, and the new route's chunk size from the build output.

**E2E:** the E2E club is unmapped and must stay unmapped (KAN-69: the backend jar loads the root `.env`), so it has no games and no game details: **don't add an E2E scenario**. Say so in the summary. Run the existing E2E suite if your setup allows it and report the result.

## Real-stack check (on Gal's machine, manual; Gal does it, you write the steps)

Write the steps in the summary, don't run them against football.org.il yourself. With backend + scraper + frontend running locally and the club mapped to Sderot:

1. One refresh (EDIT_FULL) if no game has details yet; KAN-80 fetches at most 2 per refresh.
2. Raw `GET /matches/fixtures`: count `detailsState` per value; raw `GET /matches/games/{id}` for one `AVAILABLE` game, checked against the types (field names, `null`s, `round` a number, minutes as numbers).
3. The matches screen shows the button only on `AVAILABLE` rows and the texts on the others.
4. The game page against the raw JSON: groups and counts, shirt numbers, markers, every event and minute, linked names.
5. A made-up id in the URL → the not-found card.
6. Phone width: no horizontal page scroll, events wrap under the name.
7. No football.org.il id in the page or in the network tab's responses.

## Docs

- **`README.md` and `CLAUDE.md`:** only the status line / the frontend paragraph that lists what the frontend has (rule 7): add the game details screen. Nothing else.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words) and what's wrong. I expect at least the status line, §07 (anything saying game details aren't shown yet / KAN-81 pending), §08 (screens / navigation) and §13. Add any I missed.

## Commits

Small, focused commits, for example:

- `feat(frontend): game details types, query and route (KAN-81)`
- `feat(frontend): game details action and states on the matches screen (KAN-81)`
- `feat(frontend): game details screen, header and our lineup (KAN-81)`
- `docs: README and CLAUDE.md status for the game details screen (KAN-81)` (or with the code, per rule 7)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each. Confirm no change under `backend/` or `scraper/`.
2. Versions verified and **how** you checked each, with the findings for every item in "Verify against reality" (evidence, not assumptions), including the football icon decision.
3. The final design: route and path helper, types (names chosen and why), the query and its key, how the team name is resolved and the loading rule, the 404 handling, the row states, the page structure (headings, lists), the event rendering and its accessible text, how `Scoreline` and `PlayerName` were reused.
4. Raw examples from tests: the MSW `gameDetailsBody()` (made-up names) and the rendered accessible names / sr-only texts for one player with three events.
5. Tests: the new tests (name + one line each), the mutation checks and their results, frontend test count before and after, lint / format / build results, the new chunk size, E2E result or why not run.
6. The real-stack check steps (as above, adjusted to what you built).
7. Docs changed, one line each.
8. Spec: every out-of-date place (section + quoted words + what's wrong).
9. Deviations from this prompt or the mockup, and why.
10. Open ends and risks (including anything you thought of but didn't add, and anything KAN-76 / KAN-82 will need from this screen).
11. Print in full, at branch HEAD:
    - the game page component(s);
    - the changes in `GameRow.tsx` / `GamesList.tsx`;
    - the new types and the query in `types.ts` / `queries.ts`;
    - the route addition and the sidebar change.
