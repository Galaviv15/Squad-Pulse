# KAN-74: Frontend: player statistics screen, linking scraped players to squad players, linked-player delete error

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

**Run every frontend command on Node 22** (what CI uses). On Node 24, 12 existing tests fail in `client.test.ts` (FormData / File); that's KAN-78, not this ticket. Report `node --version`.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `19de8bf`, the KAN-73 merge, PR #55). If it doesn't, stop and report. Then create the branch `feature/KAN-74-player-stats-screen`.

Take the frontend test count on master **before your first change** (`npm test` from `frontend/`).

## Context

Jira KAN-74, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-72 (backend: stats, links, suggestions, `409` on deleting a linked player) and KAN-73 (scraper: every refresh now fetches the stats). Both are merged. This ticket is **frontend only**: no change under `backend/` or `scraper/`. If you find you need a backend change, stop and report why.

KAN-77 (create a squad player from an unlinked scraped player, in one step) comes **after** this ticket and hangs off this screen. Don't build it, but keep the "squad player" cell easy to extend with one more action.

### The endpoints (read the code, not only this list; see "Verify against reality")

- `GET /matches/player-stats` (`VIEW_ONLY`) → `match.PlayerStatsResponse`: `state` (`NOT_MAPPED` / `NO_DATA` / `OK`), `lastUpdatedAt` (instant or `null`), `players[]`:
  - `id`: our opaque id;
  - `name`: as the site shows it, **surname first**;
  - `games`, `goals`, `yellowCardsLeagueAndCup` (league + State Cup), `yellowCardsToto`, `redCards`, `starts`, `cameOnAsSub`, `subbedOff`, `minutes`;
  - `linkedPlayer`: `{ id, fullName, active }` or `null`;
  - `suggestedPlayer`: the same shape, only while not linked, or `null`.

  Rows come in the site's order. There is **no** `mappingProblem` field here (unlike fixtures and the league table).
- `PUT /matches/player-stats/{id}/link` (`EDIT_FULL`), body `{ "playerId" }`.
  - `200` with the row, as in the `GET`. It sets or replaces the link; the same pair again changes nothing.
  - `400` for a blank `playerId`.
  - `404` if `{id}` isn't in the club's current stats (for example, a refresh replaced the snapshot), or if the player isn't the club's.
  - `409` `PLAYER_ALREADY_LINKED` if that squad player is linked to another scraped player.
- `DELETE /matches/player-stats/{id}/link` (`EDIT_FULL`) → `204`, also when there was no link. `404` for an unknown `{id}`.
- `DELETE /squad/players/{id}/stats-link` (`EDIT_FULL`) → `204` whether or not there was a link. `404` if the player doesn't exist (`PlayerService.removeStatsLink` calls `get(id)` first). This is the only way to unlink a player whose scraped row is no longer in the site's table.
- `DELETE /squad/players/{id}` (`ADMIN`) can now answer `409` `PLAYER_STATS_LINKED` (KAN-72). This check runs **before** the photo is touched.

### Facts this UI must respect

- **No football.org.il id or link anywhere** (spec §09). The `id` is ours; never show it. No photos, logos or links from the site.
- Statistics are for **all competitions together**. A yellow card is split into **league + State Cup** vs **Toto**: label them exactly so, never "league vs cups".
- **Minutes can exceed 90 × games** (added time). Never flag it.
- `games = starts + cameOnAsSub` always holds. Rows with 0 games / 0 minutes are kept and shown.
- A link survives a scraped player's absence from a later scrape. A link to a deleted player counts as none (the backend handles it). The backend's suggestion is a **suggestion only**: nothing links without a user's click.
- A link or unlink can change **other rows' suggestions**: a linked player is never suggested. So after any link write, refetch the whole list, never patch one row.
- **At a season start** the site's table can be empty: every refresh is then `FAILED PARSE_ERROR` (accepted in KAN-73), and the stats stay `NO_DATA` until the site registers players.
- **Today's delete dialog** (KAN-59) maps any unknown `409` to the generic "הפעולה נכשלה". A linked player's delete therefore fails with no explanation today. This ticket fixes that.

## Decisions (agreed with Gal; a mockup was approved; not up for change in this ticket)

### 1. A screen of its own

- New route `/app/stats`, title key `nav.stats` = "סטטיסטיקה". Lazy, like `matches` (route `lazy`, function form, handle static; see `app/router.tsx`).
- Sidebar: a new `NAV_ITEMS` entry "סטטיסטיקה" **after** "משחקים", linking to `/app/stats`.
  - Icon: a stroke column-chart icon from the installed `lucide-react`. Names changed across versions (`BarChart3` vs `ChartColumn`, …), so verify the name exists before using it and report which one you picked.
- The matches screen is **not** changed (no third tab).
- Header layout, like the matches screen:
  - `<h1>` from the route.
  - A description line: "נתוני השחקנים בעונה הנוכחית, בכל המסגרות יחד."
  - The data's "עודכן לאחרונה" line (the existing `LastUpdated`, Israel time).
  - On the end side, the **existing `RefreshControl`**, for `EDIT_FULL` and up only. It's the same component and hook as on the matches screen, and below `EDIT_FULL` no `GET /matches/refresh` is sent.
  - A run's end already invalidates `MATCHES_DATA_QUERY_KEY`. That refetches the stats too, **if** the stats query key is under that prefix (decision 6). Test it.

### 2. The table

- **One row per scraped player, in the backend's order. No sorting.**
- Columns, in this order, with these headers:
  - שחקן (כפי שבאתר)
  - שחקן בסגל
  - משחקים
  - בהרכב
  - נכנס כמחליף
  - הוחלף
  - דקות
  - שערים
  - three card columns
- **The card columns** show a small coloured card mark (decorative, `aria-hidden`) plus the visible text "ליגה + גביע", "טוטו", "אדומים". The two yellow headers carry a visually hidden "כרטיסים צהובים, " prefix (and red: "כרטיסים אדומים") so a screen reader never hears "טוטו" alone. Check football terms against CLAUDE.md rule 3 and report anything you changed.
- **Card colours:** add two theme tokens, `--card-yellow` and `--card-red`, the same way every token is added (`index.css` `:root` + `@theme inline`; the token table and a one-line note in `docs/design/ui-conventions.md`, which is not the spec, so you may edit it). Use them only for the decorative marks: never as the only carrier of meaning, never for text. Propose the hex values: a clear card yellow and a red that differs from `--destructive`. Say what you chose.
- **Numbers** use tabular figures and are centred. Goals are bold. A name cell never wraps the name.
- **Layout:** the table sits in a card with `overflow-x: auto`. Only the box scrolls sideways, never the page. On a narrow screen (< `md`) the first column (the site's name) is sticky on the start side inside the scroll box.
- **Accessibility:** a real `<table>` with `<th scope="col">` and a row header (`<th scope="row">`) for the name cell.

### 3. The "שחקן בסגל" cell

The cell depends on the user's level and on the row.

**Below `EDIT_FULL`** (VIEW_ONLY, EDIT_PARTIAL):
- No action and no suggestion anywhere.
- A linked row shows the squad player's `fullName` as a link to their card (`/app/squad/{id}`), with the existing released badge when `active` is `false`.
- An unlinked row shows "—", visually muted, with an accessible text "לא מקושר".

**`EDIT_FULL` and up:**
- **Linked:** a link icon (decorative), the name as a link to the card, the released badge if released, and a "⋯" row menu (icon button, `aria-label` "פעולות קישור"). The menu follows the existing `PlayerActionsMenu` pattern (Base UI menu, portaled, controlled dialogs rendered outside the menu). It has two items:
  - **"שינוי קישור"** opens the picker (decision 4).
  - **"ביטול קישור"** sends `DELETE /matches/player-stats/{id}/link` at once, with **no confirmation dialog** (it's cheap and reversible). On success, announce "הקישור בוטל." in a `role="status"` region that is already in the DOM.
- **Unlinked with a suggestion:** a dashed chip "הצעה: {fullName}" (add the released badge if the suggested player is released), plus two buttons:
  - **"אישור"** sends `PUT …/link` with the suggested id.
  - **"אחר…"** opens the picker.
  - The accessible names of both buttons include the scraped player's name (e.g. visually hidden "לשחקן {name}"), so a list of 34 "אישור" buttons is usable.
- **Unlinked, no suggestion:** a button "קישור לשחקן…" (link icon) that opens the picker.

**While any link write for a row is in flight,** that row's buttons are disabled. A second click never sends a second request.

**Errors from a link write** (put, unlink) are shown in the row's place or, for writes from the picker, in the picker. Plain Hebrew, never the English message:
- `409 PLAYER_ALREADY_LINKED` → "השחקן הזה כבר מקושר לשחקן אחר מהאתר." Refetch the list.
- `404` → "השורה הזו כבר לא קיימת או שהשחקן נמחק. הרשימה רועננה." Refetch the list.
- `403` → the existing no-permission string.
- Other errors (5xx, network) → a generic failure. Nothing refetched.

### 4. The picker (link / change link)

A dialog titled "קישור: {scraped name}", with the text "בחרו את השחקן מהסגל שזה הוא. אפשר לבחור גם שחקן ששוחרר."

- **A search field** "חיפוש בסגל": client-side, case- and whitespace-insensitive, matching any part of `fullName`.
- **Below it, the club's players** from `GET /squad/players?status=all`:
  - Reuse the squad list query (`squadPlayersQuery({ status: "all" })`), so its cache and invalidation stay as they are. Keep the server's order.
  - Each option shows initials or nothing (no photo requests), `fullName`, position and jersey number when present, and the released badge.
- **A squad player already linked to another row of the current stats** is shown but **disabled**, with "כבר מקושר ל־{that row's name}". Compute this from the `GET` data (`linkedPlayer.id` → row name).
  - It can't cover a player linked to a scraped player that is **absent** from the current snapshot. That case comes back as `409 PLAYER_ALREADY_LINKED`: handle it as in decision 3. Write a test for it.
- **The row's own current linked player** (on "שינוי") is shown as selected.
- **Choosing and saving:**
  - It's a **single-choice list**. Build it from real radio inputs, or as a `radiogroup` that follows an existing pattern in the repo. Don't invent a new listbox widget.
  - Buttons: "קישור" (disabled until a choice) and "ביטול".
  - Saving sends `PUT`. On success, close the dialog and refetch the stats.
- **States:**
  - The squad list loading → a loading line. A failure → an error with retry.
  - An empty squad → "אין עדיין שחקנים בסגל." Don't build any "create player" action here (KAN-77).
- **The dialog component:** use the existing dialog building blocks (`components/ui/alert-dialog.tsx` / `components/squad/ActionDialog.tsx`) if they fit a form body. If they don't, add shadcn's `dialog` with the same generator and version the repo already uses. Verify against `components.json` / `package.json`, and say which you did and why.

### 5. Filter and counts (`EDIT_FULL` and up, state `OK` only)

- **A segmented control** (the existing `segmentedControl` / `ViewToggle` pattern, a radio group, accessible name "הצגה") with two options:
  - "כל השחקנים ({n})" (default).
  - "ממתינים לקישור ({m})": rows with `linkedPlayer === null`, suggested ones included.
- **The filter lives in the URL** as `?filter=unlinked`, with no param for all. Switching replaces the history entry, like the squad view. An unknown value falls back to all.
- **Beside it, a muted line:** "{a} מקושרים · {b} עם הצעה · {c} ללא קישור". Use proper Hebrew plurals through i18next if any word needs them.
- **The unlinked filter with nothing left** shows "כל השחקנים מקושרים." (not an empty table).
- **Below `EDIT_FULL`:** no filter and no counts.

### 6. Data layer

- A new module `lib/matches/playerStats.ts` (or a folder; say which):
  - The types, mirroring `PlayerStatsResponse` exactly.
  - `PLAYER_STATS_QUERY_KEY = [...MATCHES_DATA_QUERY_KEY, "player-stats"]`, the query options and a `usePlayerStats` hook. TanStack defaults, like fixtures.
  - The three mutations: link, unlink by row, unlink by squad player. Each invalidates `PLAYER_STATS_QUERY_KEY` on success, and also on `404` / `409`.
- **The `409` codes:**
  - `PLAYER_STATS_LINKED` goes into `lib/squad/errorCodes.ts` (`PLAYER_ERROR_CODES`, with its backend class in the comment).
  - `PLAYER_ALREADY_LINKED` goes into a stats error-code constant beside the module, the same style.
  - Compare `ApiError.code`, never the message.
- **The router's comment** that lists backend paths: check `/matches/player-stats` is covered by the existing `/matches` prefix (dev proxy `BACKEND_PREFIXES`) and say so.

### 7. Deleting a linked player (squad: card and table row)

- `actionErrorMessage` maps `409 PLAYER_STATS_LINKED` to its own message, with an offer of a new kind, e.g. `"unlink"`.
- **In the delete dialog only, that error shows:**
  - **Title line:** "אי אפשר למחוק: השחקן מקושר לסטטיסטיקה מאתר ההתאחדות."
  - **Text:** "בטלו את הקישור ואז נסו למחוק שוב. אחרי ביטול הקישור הוא יופיע במסך הסטטיסטיקה כשחקן לא מקושר."
  - **A button "ביטול הקישור"**, which sends `DELETE /squad/players/{id}/stats-link`.
- **On `204`:**
  - Clear the delete error (`remove.reset()`).
  - Show a success notice in the dialog: "הקישור בוטל. אפשר למחוק עכשיו."
  - Invalidate `PLAYER_STATS_QUERY_KEY`.
  - **Do not delete automatically:** the user clicks "מחיקה" again. These are two explicit steps by design.
- **On unlink errors:**
  - `404` (player gone) → the existing not-found handling (`squad.player.notFound` with its offer).
  - `403` / others → the existing generic texts.
- While the unlink is in flight, both the unlink and the delete buttons are disabled.
- **It works the same from the card and from the squad table row menu** (both hosts render `PlayerActionDialog`).
- Release / re-activate / photo flows are unchanged. The new error never appears there (the backend only sends it on delete), but the mapping is generic. Say how you kept the unlink offer to the delete dialog.

### 8. States

- **`NOT_MAPPED`:** the existing `matches.notMapped` text. No filter, no counts, no refresh control (the control hides itself on `NOT_MAPPED`; keep it so).
- **`NO_DATA`:** "עוד לא נאספו נתוני שחקנים. הם יופיעו אחרי הרענון הראשון." An `EDIT_FULL` user still sees the refresh control.
- **Read error:** "לא הצלחנו לטעון את סטטיסטיקת השחקנים." with the existing retry, through `QueryContent`.
- **Loading:** the existing loading line.

### 9. i18n and code organisation

- **Strings:** every UI string goes in `he.json`, under a new `stats` namespace (plus `nav.stats`). Reuse the existing strings named above. Use these Hebrew strings exactly, unless rule 3 says otherwise; report any change.
- **Files:**
  - The page: `pages/stats/StatsPage.tsx`.
  - Its parts: `components/stats/`.
  - The picker and the cell are separate components.

## Out of scope

- Any backend or scraper change.
- Creating a squad player from a scraped row (KAN-77).
- Showing link state on the player card or in the squad table (that needs a backend field).
- Per-game data (KAN-75).
- Sorting.
- A dashboard card.
- Any change to the matches screen.
- Photos, logos or links from the site.
- Women's teams.

## Verify against reality, not memory

Check these against what's actually installed (`frontend/package.json`, `package-lock.json` / `node_modules`) and the backend code, not general knowledge. For each, say in the summary **how** you checked it:

- **The raw JSON of `GET /matches/player-stats`** for each state, and of a `PUT …/link` answer: from a backend integration test's raw output or a real response.
  - Include how `lastUpdatedAt` serializes.
  - Confirm that `linkedPlayer` / `suggestedPlayer` are `null` (not absent) when empty.
  - Your types and MSW fixtures must match it exactly.
- **The `409` bodies** for `PLAYER_ALREADY_LINKED` and `PLAYER_STATS_LINKED` (the `code` field), from the backend tests.
- **`lucide-react` (installed version):** the chart icon's export name.
- **TanStack Query (installed version):**
  - Invalidating by key prefix reaches the new key from `MATCHES_DATA_QUERY_KEY`.
  - The mutation `onError` signature.
- **React Router (installed 8.x):** the search param for the filter, the lazy route function form.
- **i18next (installed version):** the Hebrew plural forms for any counted word you use.
- **Base UI / shadcn:** whichever dialog you use. Its focus return after the dialog closes; the existing `focusBack` / `finalFocus` pattern.

## Read first

`CLAUDE.md` (standing rules, especially 2, 3, 6 and 7), `docs/design/ui-conventions.md`, then only:

- **Backend** (read only):
  - `match/PlayerStatsResponse.java`, `match/PlayerStatsController.java`, `match/LinkPlayerRequest.java`, `match/PlayerAlreadyLinkedException.java`.
  - `squad/PlayerController.java` (delete + `stats-link`), `squad/PlayerStatsLinkedException.java`.
  - The "Player statistics" part of `README.md`.
- **Frontend:**
  - Routing and shell: `app/router.tsx`, `components/shell/Sidebar.tsx`.
  - Matches screen: `pages/matches/MatchesPage.tsx`, `components/matches/MatchDataStates.tsx`, `components/matches/RefreshControl.tsx`.
  - Matches data: `lib/matches/queries.ts`, `lib/matches/refresh.ts`, `lib/matches/types.ts`, `lib/matches/israelTime.ts`.
  - Squad data: `lib/squad/players.ts`, `lib/squad/errorCodes.ts`, `lib/squad/types.ts`, `lib/squad/view.ts`.
  - Squad components: `components/squad/playerDialogs.ts`, `components/squad/PlayerActionDialog.tsx`, `components/squad/ActionDialog.tsx`, `components/squad/PlayerActionsMenu.tsx`, `components/squad/PlayerBadges.tsx`, `components/squad/ViewToggle.tsx`, `components/squad/segmentedControl.ts`.
  - UI and shared: `components/ui/table.tsx`, `components/ui/alert-dialog.tsx`, `components/ui/dropdown-menu.tsx`, `components/form/FormMessage.tsx`, `lib/api/errors.ts`, `lib/auth/permissions.ts`, `i18n/locales/he.json`, `index.css`.
- **Tests as a style model:** `pages/matches/MatchesPage.test.tsx`, `pages/squad/SquadRowDialogs.test.tsx`, `pages/squad/PlayerCardActions.test.tsx`, `test/msw/matches.ts`, `test/msw/squad.ts`, `test/liveRegion.ts`.
- **E2E:** `e2e/matches.spec.ts`, `e2e/helpers.ts`.
- **`docs/spec.md`:** the status line, §02, §05, §07, §08, §13. Only to list what goes out of date, **not** to edit.

## Tests

Vitest + MSW, the existing rules:
- Console guard and unmocked-request guard.
- `test/msw/handlers.ts` stays empty: put builders in `test/msw/matches.ts` (or a new `test/msw/playerStats.ts`).

Fixture rows include:
- a linked active player;
- a linked released one;
- a suggested one;
- a plain unlinked one;
- a 0-minute row;
- a row with minutes > 90 × games.

At least:

**Navigation and page**

- "סטטיסטיקה" is a live sidebar link after "משחקים". It navigates to `/app/stats`, `aria-current` moves, and the document title is right.
- The matches screen still has exactly two tabs.
- The table: every column header, including the accessible names of the card columns. Rows in the server's order. A 0-minute row is shown. Minutes > 90 × games are shown with no warning. **No opaque `id` in the DOM text.**
- "עודכן לאחרונה" in Israel time.
- States: `NOT_MAPPED` (no filter, no counts, no control), `NO_DATA`, read error with retry, loading.

**Permissions**

- `VIEW_ONLY` and `EDIT_PARTIAL`:
  - linked names are shown as links to the card;
  - **no** suggestion, button, menu, filter or counts;
  - **no `GET /matches/refresh` and no `GET /squad/players`** (proved by the unmocked-request guard).
- `EDIT_FULL` and `ADMIN`: all controls.

**Linking**

- Confirming a suggestion: exactly one `PUT` with the suggested `playerId`. Then the list is refetched (count `GET`s), not patched. A double click sends one `PUT`.
- "אחר…" and "קישור לשחקן…" open the picker:
  - search filters the list;
  - a player linked to another row is disabled with its "כבר מקושר ל־" text;
  - a released player is selectable with its badge;
  - "קישור" is disabled until a choice is made;
  - saving sends one `PUT`, closes the dialog and refetches.
- "שינוי קישור": the current player is preselected; saving another sends `PUT` with the new id.
- "ביטול קישור": exactly one `DELETE`, no dialog, the status text enters an existing `role="status"` region (`watchLiveInsertion`), and the list is refetched.
- Errors, each with its text and refetch rule (decision 3):
  - `409 PLAYER_ALREADY_LINKED`, in the case the client couldn't foresee (target linked to a row absent from the snapshot);
  - `404`;
  - `403`;
  - `500`;
  - a network error.
- **Refresh:** a refresh run's end (status `RUNNING` → `SUCCEEDED`, and `RUNNING` → `FAILED`) refetches `GET /matches/player-stats` on the stats screen.

**Filter**

- Default all. `?filter=unlinked` shows only rows without a link (suggested included). Switching updates and replaces the URL. An unknown value → all.
- The counts are right. All rows linked + the unlinked filter → "כל השחקנים מקושרים.".

**Delete of a linked player** (from the card **and** from the table row menu)

- `409 PLAYER_STATS_LINKED` → the message and the "ביטול הקישור" button, never the generic failure.
- Clicking the button → exactly one `DELETE /squad/players/{id}/stats-link`, then the notice "הקישור בוטל. אפשר למחוק עכשיו.", and **no second `DELETE /squad/players/{id}` until the user clicks "מחיקה" again**. The next click → `204` → the existing deleted flow (notice, navigation).
- Unlink `404` → not-found handling. Unlink `500` → generic failure, with the delete still possible.
- Both buttons are disabled while the unlink is in flight.
- The stats query is invalidated after an unlink (seed it in the cache and check it's marked stale or refetched).
- Release / re-activate unchanged: the existing tests pass untouched.

**E2E** (extend `e2e/matches.spec.ts` or add `e2e/stats.spec.ts`; one scenario)

The E2E club stays **unmapped**: don't change the seeder or the E2E env (see the comment in `e2e/matches.spec.ts`).

As the seeded admin:
- sidebar "סטטיסטיקה" → `/app/stats`;
- the real `GET /matches/player-stats` answers `200`;
- the not-mapped text is shown;
- there's no refresh button and no link controls.

**Mutation checks:** break the code on purpose, confirm a test fails, then restore. At least:

1. put the stats query key outside `MATCHES_DATA_QUERY_KEY`;
2. apply the suggestion automatically when the list loads;
3. show the suggestion and link controls to `VIEW_ONLY`;
4. update only the edited row instead of refetching the list;
5. delete the player automatically after the unlink in the delete dialog;
6. map `PLAYER_STATS_LINKED` to the generic failure;
7. request `GET /squad/players` before the picker opens;
8. sort the rows by goals.

Report each and whether it was caught.

**Commands** (from `frontend/`, Node 22):
- `npm run lint`, `npm run format:check`, `npx tsc -b`;
- `npm test` (report the count before and after);
- `npm run build` (report that the stats chunk is separate from the main bundle);
- `npm run e2e` if the local stack is up (otherwise say so; CI runs it).

**Manual check** (only if Gal's local stack is up; otherwise say so)

As an `ADMIN` of the Sderot club (mapped) on the dev server:
- The stats screen with the real 34 rows, the "עודכן לאחרונה" line, the filter and counts.
- Confirm **one** suggestion if there is one, then unlink it.
- Link one row through the picker, then unlink it.
- For the delete flow, use **a throwaway player you create for this check**, never a real squad member:
  - create it and link it to a row;
  - try to delete it: you should get the message and the button;
  - unlink, then delete.
- **No refresh against the live site in this check** (none is needed: the data is already stored). If there's no stored stats snapshot yet, say so and stop the manual check there. Don't trigger a refresh.
- View as a `VIEW_ONLY` user if one exists.
- Describe the screen on a phone width (~390 px): sticky name column, sideways scroll of the box only.

## Docs

- **`README.md`** (CLAUDE.md rule 7, same commit as the code), the frontend section:
  - the stats screen;
  - who sees what;
  - the suggestion is confirmed by a click;
  - the linked-player delete flow (unlink, then delete).
- **`CLAUDE.md`:** the status line (KAN-74 done), and one short note where the frontend conventions live:
  - the stats query sits under `MATCHES_DATA_QUERY_KEY` so a refresh refetches it;
  - link writes refetch the whole list;
  - the backend's suggestion is never applied without a click.
  - Nothing else.
- **`docs/design/ui-conventions.md`:** the two card tokens, and a "Stats" line like the "Matches" one (the sticky first column on phones, card marks decorative only).
- **Code comments** in the existing style:
  - why the key is under the matches data prefix;
  - why the whole list refetches;
  - why the delete is never automatic after unlinking;
  - why the picker can still get `PLAYER_ALREADY_LINKED`.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words) and what's wrong. I expect at least:
  - the status line and §13 (KAN-74 done; KAN-77 next);
  - §02 (the `/app/stats` route, the sidebar item);
  - §05 (the delete of a linked player as the user sees it: unlink, then delete);
  - §07 (how the stats are shown: "all competitions", the yellow-card split, "last updated", the suggestion confirmed by a click, link / unlink from `EDIT_FULL`, the refresh control on the stats screen too);
  - §08 (the sidebar / screens list, if §08 lists them).

  Add any I missed.

## Commits

Small, focused commits, for example:

- `feat(frontend): player stats data layer, error codes and test handlers (KAN-74)`
- `feat(frontend): player stats screen with filter, route and sidebar item (KAN-74)`
- `feat(frontend): link scraped players to squad players (KAN-74)`
- `feat(frontend): unlink from the delete dialog of a linked player (KAN-74)`
- `test(e2e): stats screen for an unmapped club (KAN-74)`

README, CLAUDE.md and ui-conventions go with the code they describe (rule 7). Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. `node --version`, the versions verified and **how** you checked each, with the finding for every item in "Verify against reality" (evidence, not assumptions). Include the raw JSON of `GET /matches/player-stats` (`OK` with a linked, a suggested and a plain row) and both `409` bodies.
3. The design:
   - the data module (types, keys, mutations, invalidation);
   - the components;
   - the route and sidebar;
   - the filter in the URL;
   - the dialog component you used and why;
   - the delete-dialog change and how the unlink offer is kept to delete;
   - the two token values.
4. Tests:
   - the new tests (name + one line each);
   - the mutation checks and their results;
   - the test count before and after;
   - the results of lint, format, tsc, build (chunk names / sizes) and e2e.
5. Manual check result, or why it wasn't run.
6. Docs changed, one line each.
7. Spec: every out-of-date place (section + quoted words + what's wrong).
8. Deviations from this prompt, and why. Include any Hebrew string you changed (rule 3).
9. Open ends and risks: for KAN-77 (where its action fits in the cell), the season-start state, and anything you noticed.
10. Print in full, at branch HEAD:
    - the stats data module (types, keys, hooks, mutations);
    - the "שחקן בסגל" cell component;
    - the picker component;
    - the changed `actionErrorMessage` and the delete dialog (with the unlink step).
