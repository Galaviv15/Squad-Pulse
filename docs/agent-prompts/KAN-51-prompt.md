# KAN-51: Frontend dashboard (squad summary + placeholders)

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `8af0cbf` (the KAN-59 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-51-dashboard`.

## Context

This is Jira KAN-51, in epic **KAN-43 "Frontend MVP"** (Phase 3). It replaces the stub on the home route `/app` (`src/pages/DashboardPage.tsx`, which today renders only `dashboard.stub`) with the real dashboard:

- squad KPI tiles and a donut of the squad by line, from `GET /squad/summary`;
- "coming soon" placeholder cards for the parts that have no backend yet.

**Frontend only. No backend change.** `GET /squad/summary` exists since KAN-28. If you believe a backend change is needed, stop and ask.

Reuse what's there; don't rebuild it:

- `apiJson` (`src/lib/api/client.ts`), `ApiError` / `NetworkError`.
- `src/lib/squad/players.ts`: `SQUAD_QUERY_KEY`. Its comment already reserves **`["squad", "summary"]`** for this ticket's query. Every player write already invalidates `SQUAD_QUERY_KEY`, so the summary refreshes after any player write with no extra code. Keep that key exactly.
- `src/lib/squad/paths.ts`: `SQUAD_PATH` for the link to the squad table.
- The loading / error / retry pattern of `SquadPage.tsx` (`role="status"` loading text, an error message with a retry button that calls `refetch()`).
- `components/ui/card.tsx`, `button.tsx` (`buttonVariants` for a link styled as a button, if needed), `cn`.
- Test helpers: `renderWithProviders`, `src/test/msw/auth.ts`, `src/test/msw/squad.ts`, `deferred()`.

Read these first:

- `CLAUDE.md`: all of it, especially the frontend paragraph. Every rule applies. **A test asserting that no request is sent must spy on `fetch`.**
- `docs/design/ui-conventions.md`: all of it. The "Dashboard (KAN-51)" bullet, the placeholder ("coming soon") card rule, the type scale (KPI numbers 40/700), the chart tokens and the mandatory-legend rule.
- `docs/spec.md` sections 02 (frontend paragraph), 05 (summary), 08 (dashboard). **Read only** (see "Spec").
- Backend: `squad/SquadSummaryController.java`, `SquadSummaryResponse.java`, `Line.java`, `PlayerService.summary()`, and the summary tests in `PlayerApiIntegrationTest` / `PlayerServiceSummaryTest` (they show the exact JSON).

## Facts I checked on master `8af0cbf` (re-confirm each against the code; don't take them on faith)

**The endpoint**

- `GET /squad/summary`, `VIEW_ONLY` (every logged-in user). Club-scoped, active players only (any medical status).
- Response: `{ playerCount: int, averageAge: number | null, lines: { GOALKEEPERS, DEFENSE, MIDFIELD, ATTACK } }`.
  - `lines` always has all four keys, in `Line` order, `0` for an empty line.
  - `averageAge` is a `BigDecimal` with scale 1, serialized as a JSON **number** (the integration test asserts `isNumber()`), and written as `null` (not omitted) when no active player has a date of birth. **Consequence:** `25.0` arrives in the browser as the number `25`. The UI must always show exactly one decimal (`25.0`, `25.8`). Don't use a raw `String(n)`.
  - **`playerCount` can be larger than the sum of `lines`**: a player without a primary position (only possible in data not written through the API) is counted but in no line.
- Empty club: `{"playerCount":0,"averageAge":null,"lines":{...all 0}}`.

**Frontend state**

- No frontend code calls `/squad/summary` yet. There's no summary type.
- The tokens exist: `--chart-1..4` and the Tailwind colors `chart-1..4` (`fill-chart-2`, `stroke-chart-2`, `bg-chart-2`, …) in `index.css`. GK = chart-1, DEF = chart-2, MID = chart-3, ATT = chart-4.
- **Tests:** `src/test/setup.ts` runs MSW with `onUnhandledRequest: "error"`. Several existing tests land on `/app` and render the dashboard: `router.test.tsx`, `routeError.test.tsx`, `AppShell.test.tsx`, possibly `sessionRouting.test.tsx` and others. Once the dashboard fetches the summary, they'll hit an unhandled request. Fix each affected test explicitly, e.g. with a summary builder from `src/test/msw/squad.ts`. **Don't** add a default handler to `handlers.ts`: it stays empty on purpose. Also remove or replace the assertions on `he.dashboard.stub`.

## Decisions (agreed with Gal — implement these)

### 1. Data layer (`src/lib/squad`)

- A `SquadSummary` type mirroring the response. Use `Record<Line, number>` over a `Line` union, so a new backend line is a compile error until it has a label and a color.
- `useSquadSummary()`: `useQuery` with key `["squad", "summary"]` (built from `SQUAD_QUERY_KEY`, exported as a constant), `apiJson` on `/squad/summary`. Leave TanStack's defaults (no custom `staleTime`) unless you have a reason; say what you chose.
- A pure, unit-tested formatter for the average age: `null` → `"—"` (the em dash), a number → exactly one decimal. Say whether you used `toFixed(1)` or `Intl.NumberFormat` and why. Either way the output must be a plain `25.8`, not localized digits or a comma.

### 2. Layout (per the approved mockup; the mockup's look is below in text, since you can't open it)

The page title "דשבורד" already comes from the route (the shell's `<h1>`). Don't add another `<h1>`. Section titles inside the cards are `<h2>`.

**Row 1, a responsive grid** (mockup: `repeat(auto-fit, minmax(240px, 1fr))`, gap 20px):

- **Tile "שחקנים פעילים"**: a card, title as a label (14/500, muted), the number at 40/700 with tabular figures, and a link "לטבלת הסגל ←" to `SQUAD_PATH`. The link is a real `<a>` (`Link`), not a click handler on the card.
- **Tile "גיל ממוצע"**: the formatted average (dash when `null`), caption "שחקנים פעילים בלבד" (13, muted).
- **Card "פילוח לפי קווים"**, wider (mockup: `grid-column: span 2`): the donut (168×168) and the legend beside it. Legend = a list (`<ul>`) in two columns of rows: a 12×12 color swatch (radius 3px), the line's Hebrew name (500), the count at the end (600, tabular). Order: שוערים, הגנה, קישור, התקפה. **No percentages.** **Make sure the span-2 card doesn't overflow or create a phantom column at narrow widths** (at one column it must span one). Say how you solved it.

**Row 2**: three placeholder cards (mockup: `minmax(260px, 1fr)`, min-height 140px).

**Row 3**: the wide weekly-schedule placeholder.

**Placeholder style** (ui-conventions): a transparent background, a 1px dashed `--input` border, `--radius`, the card title 16/600, a muted body line, and a muted "בקרוב" pill (12/500, rounded-full). Not clickable, not focusable, no fake data, no `href`.

| Card | Title | Body |
| --- | --- | --- |
| Next training | האימון הבא | לוח האימונים יופיע כאן כשמודול האימונים יהיה מוכן. |
| Next match | המשחק הבא | המשחק הקרוב ופרטיו, מתוך לוח המשחקים. |
| League table | טבלת הליגה | מיקום הקבוצה בטבלת הליגה יופיע כאן. |

**Note:** the league-table body is **deliberately different from the mockup**. The mockup says the table updates from the FA site, a feature that hasn't been decided (Phase 5). Use the text above.

**Weekly schedule ("לו״ז שבועי")**: the same dashed style, with the title and the "בקרוב" pill on one row. Under it, a 7-column grid of day columns ראשון, שני, שלישי, רביעי, חמישי, שישי, שבת: ראשון at the start side (right in RTL). Each column has the day name (13/600, muted, with a bottom border) over an empty muted block (about 120px high, reduced opacity). Below the grid: "השבוע הקרוב, נגזר מהלו״ז המלא: אימונים, משחקים ואירועי מועדון." On narrow screens the grid scrolls horizontally inside its card (columns have a min width ~90px). The page itself must not scroll sideways.

Use tokens only (`bg-card`, `border-border`, `border-input`, `text-muted-foreground`, `bg-muted`, …): no hex, no raw palette colors. If a muted pill or block color has no exact token, use the closest existing one and say which. Don't add theme tokens without asking.

### 3. The donut (plain SVG, Gal's decision; no chart library)

- **No new dependency.** Don't add the shadcn `chart` component (it brings recharts).
- Put the geometry in a **pure, unit-tested function** (e.g. `donutSegments(lines)` → one segment per non-zero line, with its start / length), and keep the component thin.
- The mockup's technique: 4 `<circle>`s of r=60, stroke-width 22, in a 168×168 viewBox, rotated −90° so the ring starts at the top, each with `stroke-dasharray` / `stroke-dashoffset`, with a small gap (~3px) between segments. Colors via the chart tokens (`stroke-chart-1` …). An arc-path implementation is fine too; say which you chose.
- **Direction:** say which way the segments run in RTL (clockwise from the top is fine). The legend order is what users read; the ring must use the same order.
- **Edge cases (each one tested):**
  - **All zero** (or `playerCount` 0) → an empty ring: one full circle in a muted token, no colored segments, no NaN anywhere in the DOM (assert it). The center shows `0`.
  - **One non-zero line** → a full ring of that color, **no gap**.
  - **A small segment must never disappear.** With a gap subtracted, a 1-player line in a big squad can reach length ≤ 0. Clamp so any count > 0 stays visible (a minimum arc), and test it, e.g. `{1, 60, 60, 60}`.
- **Center:** the total, which is **`playerCount`** (the same number as the tile, even when it's larger than the sum of the lines), at 28/700 tabular, with "שחקנים" under it (12, muted). The ring itself is built from `lines` only.
- **Accessibility:** the `<svg>` has `role="img"` and an `aria-label`, e.g. "פילוח הסגל הפעיל לפי קווים". The data itself is read from the legend, which is real text. Each segment can carry a `<title>` "{{line}}: {{count}}". Don't make the segments focusable.

### 4. States

- **Only the three squad cards depend on the data.** The placeholders render at once, always, also while loading and on error.
- **Loading** (no data yet): the three squad cards show a loading state with `role="status"` text "טוען…" (reuse the existing key if one exists). Don't show `0` or an empty ring before the data arrives.
- **Error** (no data, not fetching): one message in place of the squad cards, "לא הצלחנו לטעון את נתוני הסגל.", with a "נסו שוב" button that calls `refetch()`. Reuse existing keys if the wording matches; say which.
- **Background refetch error with data present** → keep showing the data (TanStack's default). Don't flash the error.
- A 401 is the API client's business (refresh / logout). Don't handle it here.

### 5. Copy (Hebrew; keys are yours, all in `he.json` under `dashboard.*`)

| Where | Text |
| --- | --- |
| Tiles | שחקנים פעילים / לטבלת הסגל ← / גיל ממוצע / שחקנים פעילים בלבד |
| Donut card | פילוח לפי קווים / שחקנים (center) / פילוח הסגל הפעיל לפי קווים (aria-label) |
| Lines | שוערים / הגנה / קישור / התקפה |
| Placeholders | the table in section 2 / בקרוב |
| Weekly | לו״ז שבועי / ראשון … שבת / השבוע הקרוב, נגזר מהלו״ז המלא: אימונים, משחקים ואירועי מועדון. |
| States | טוען… / לא הצלחנו לטעון את נתוני הסגל. / נסו שוב |

- Remove `dashboard.stub` if nothing uses it any more.
- Keep the gershayim exactly as written ("לו״ז" uses U+05F4).
- The line labels use a `Record<Line, string>` of keys, like `labels.ts` does for the other enums.
- The arrow "←" in "לטבלת הסגל ←" points left = forward in RTL, as in the mockup. Check that it renders at the end of the text in Chrome.

## Out of scope (don't build)

- Any data for training, matches, the league table or the schedule.
- Making the legend or the segments link to a filtered squad: the API has no line filter.
- An injured count or a name search (Gal: not needed now).
- Dark mode, animations on the chart.
- Lazy-loading the dashboard. It's the home route; keep it in the main bundle unless it grows the bundle noticeably. Report the size change.
- Any backend change.

## Tests

Vitest + React Testing Library + MSW, through `renderWithProviders`.

- Add a summary builder to `src/test/msw/squad.ts` (e.g. `summaryBody(overrides)` + `summaryReturns(answer)`). `handlers.ts` stays empty.

At minimum:

- **Pure functions:**
  - the age formatter: `null`, `25`, `25.8`, `30.05` if relevant to your choice;
  - the donut geometry: a normal squad (segments sum to the circumference minus the gaps, in line order), all zero, one non-zero line (no gap), and a tiny line kept visible.
- **Rendering:**
  - a normal summary → both tiles, the center total, the four legend rows with names and counts, no `%` anywhere;
  - `averageAge: 25` → "25.0";
  - **a club with zero players and `averageAge: null`** → `0`, the dash, an empty ring, the legend with four zeros, no NaN in the DOM;
  - `playerCount` larger than the sum of the lines → the center and the tile show `playerCount`.
- **Navigation:** clicking "לטבלת הסגל" lands on the squad table at `/app/squad` (with the list request handled).
- **States:**
  - loading (deferred): the status text shows, and the placeholders are already there;
  - error → message → retry succeeds;
  - a background refetch error keeps the data.
- **Refresh after a write:** the summary query is invalidated by a player write. One test via `onPlayerWritten` / the existing invalidation is enough.
- **The placeholders:** present, contain no links or buttons, and the weekly card has the seven day names in order.
- **The existing tests that land on `/app`** pass with explicit summary handlers (list which ones you changed).

## Manual verification (in Chrome — Safari can't refresh on http://localhost until KAN-56)

You can't drive a browser, so write **exact, numbered steps for Gal** in your summary. He isn't a frontend developer: give the terminal commands and what to click and expect. Cover:

- **Setup:** `docker compose up -d`; the backend with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` + `./mvnw spring-boot:run`; `npm run dev` on the branch.
- **The dashboard with his real squad:** the numbers match the squad table's active count; the legend counts match the table filtered by position (say which positions belong to which line).
- **After a write:** release a player in the squad table, then go back to the dashboard. The count and the donut update without a reload.
- **Average age:** compare with the table's ages.
- **Error state:** stop the backend, reload the dashboard. The placeholders show, the squad cards show the error. Start the backend and click "נסו שוב".
- **Widths:** 1440, 1024, 768 and 375 px. No sideways page scroll; the weekly grid scrolls inside its card; the donut card never overflows.
- **Look:** compare against the mockup description in section 2.

## Constraints

**Language and copy**

- English in code, comments and commits (CLAUDE.md rule 2).
- Every user-visible string goes in `he.json` (rule 6). List all new Hebrew strings in your summary.

**Checks**

- `npm run lint`, `npm run format:check`, `npm test` and `npm run build`, all green with zero warnings.
- Report the frontend test count before → after (it was 693 on master).
- Report the main bundle size before / after.

**Styling**

- Grep every new or changed component for physical-direction classes (`ml-`, `mr-`, `pl-`, `pr-`, `left-`, `right-`, `text-left`, `text-right`, `border-l`, `border-r`, `rounded-l`, `rounded-r`, …) and paste the result. It must be empty.
- Note: the SVG's internal geometry (`rotate`, `cx`) is not a layout direction and is fine. Mention it if it shows up in the grep.
- Tokens only. No hex in components.

**Dependencies**

- **No new npm dependencies.** `git diff package.json package-lock.json` must be empty. Paste it.

**Verify library behavior against the installed versions, not memory**

Say in the summary what you checked and where:

- TanStack Query 5: `isPending` / `isError` / `isFetching` with data present on a failed background refetch, and the invalidation by prefix;
- React Router 8.4 (`Link` inside the shell);
- Tailwind 4.3: that `stroke-chart-*` / `fill-*` utilities are generated from the `--color-chart-*` theme variables (check the built CSS);
- jsdom: SVG attribute assertions.

**Commits**

- Small, focused commits in Conventional Commits style with `(KAN-51)`.
- Stage files by path, never `git add -A`.

**Documentation (rule 7)**

- Update `CLAUDE.md` in the same change: the summary query and its key, the donut helper, and that a test landing on `/app` must now handle `GET /squad/summary`.
- Replace any "KAN-51 …" forward reference (e.g. in `players.ts`'s comment) with what's true now.
- Add a short "Dashboard (KAN-51)" implementation note to `docs/design/ui-conventions.md`: the SVG donut, the placeholder classes, and the league-table text deviation from the mockup.

**Don't push and don't open a PR.** Stop after committing locally.

## Spec

**Don't edit `docs/spec.md`.** In your summary, list every place this ticket makes outdated, or that should now mention it, with line numbers and what's wrong or missing. I expect at least:

- line 5 (status: "the dashboard comes next");
- section 02's frontend paragraph (the dashboard screen);
- section 08 (what the MVP dashboard actually shows);
- section 13's Phase 3 row.

I'll prepare the spec update.

## Summary to report back

- Branch, commits (hash + message), files added / changed / deleted.
- The data layer (type, hook, key, formatter) and the donut geometry function: paste them.
- How the span-2 card behaves at narrow widths.
- The SVG technique, the direction of the segments, and how a tiny segment is kept visible.
- The existing tests you had to change and why.
- Test counts before → after, and the new tests by name.
- All new Hebrew strings (key → text), and which existing keys you reused.
- The physical-class grep result, the `package.json` / lockfile diff (empty), bundle sizes.
- The manual-verification steps for Gal, complete.
- Every version-specific fact you relied on, and how you verified it.
- Spec places to update. Open ends, risks, anything you weren't sure about.
