# KAN-49: Frontend squad table — player list with filters and photos

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `36c96eb` (the KAN-48 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-49-squad-table`.

## Context

This is Jira KAN-49, in epic **KAN-43 "Frontend MVP"** (Phase 3). KAN-45 (infra), KAN-46 (API client and session), KAN-47 (auth screens) and KAN-48 (app shell) are done and merged. This ticket replaces the `/app/squad` stub with the squad table, the first screen with real data. It blocks **KAN-50** (player card and form: create, edit, release / reactivate / delete, photo upload), **KAN-52** (a cards view of the same list, with a list/cards toggle) and **KAN-53** (Playwright E2E: login → shell → squad table). Build it so those three can reuse it without rewriting (see sections 2 and 9).

Reuse what's there; don't rebuild it:
- `apiJson` / `apiFetch` (`src/lib/api/client.ts`): the only way to call the backend.
- `useAuthorizedImage` through `components/ImageOrInitials.tsx` (made in KAN-48 to be reused for player avatars).
- `useCurrentUser()` (`src/lib/auth/currentUser.ts`): `permissionLevel` for the "הוספת שחקן" button.
- The route `handle` / `RouteHandle` title mechanism (`src/app/pageTitle.ts`): pages never render their own `<h1>`.
- `Badge` variants `default` / `outline` / `success` / `danger` / `muted`, `Button` sizes `default` / `sm` / `icon-sm`, `Input` `size="sm"` (`src/components/ui`).
- Test helpers: `renderWithProviders`, `src/test/msw/auth.ts` (`loggedIn()`, `meReturns()`, `currentUserBody()`, `imageReturns(path, gate?)`), `deferred()` (`src/test/deferred.ts`).

Read these first:
- `CLAUDE.md`: the whole frontend paragraph. Every rule in it applies. Note especially: **a test asserting that no request is sent must spy on `fetch`** (`vi.spyOn(globalThis, "fetch")`, as `AppShell.test.tsx` does) — MSW's `onUnhandledRequest: "error"` alone doesn't prove it when the UI swallows the failure (an image falling back to initials does exactly that).
- `docs/design/ui-conventions.md`: all of it, especially the type scale, density (filter controls 36px, table rows 52px), badges, "Squad table (KAN-49)", RTL / LTR islands, `tabular-nums`, and the implementation notes. The approved mockup is in a design canvas you can't open; everything you need from it is in this prompt.
- `docs/spec.md` sections 01 (positions shown in English), 05 (player fields), 13 (Phase 3). **Read only. Don't edit the spec** (see "Spec").
- Frontend: `src/app/*` (router, `pageTitle.ts`, AppShell + tests), `src/pages/SquadPage.tsx`, `src/components/ImageOrInitials.tsx`, `src/components/shell/Sidebar.tsx`, `src/lib/*`, `src/test/*`, `src/i18n/locales/he.json`, `src/components/ui/*`, `components.json`.
- Backend, to confirm every contract below: `squad/PlayerController.java`, `PlayerResponse.java`, `PlayerFilter.java`, `PlayerService.java` (`list`, `SQUAD_ORDER`, the age matching and `Period`), `PlayerStatus.java`, `Position.java`, `MedicalStatus.java`, `PreferredFoot.java`, `InvalidAgeRangeException.java`, `CreatePlayerRequest.java` (which fields are optional), `common/GlobalExceptionHandler.java` (what a bad query param returns).

## Facts I checked on master `36c96eb` (re-confirm each one against the code; don't take them on faith)

- `GET /squad/players` (`VIEW_ONLY`) returns a **JSON array** of `PlayerResponse`, **not paginated**: `id, fullName, primaryPosition, secondaryPosition, jerseyNumber, dateOfBirth (yyyy-MM-dd), heightCm, weightKg, preferredFoot, medicalStatus, active, version, createdAt, updatedAt, hasPhoto`. **There is no `age` field**: the age is computed on the client from `dateOfBirth`.
- Optional (may be `null`): `secondaryPosition`, `jerseyNumber`, `heightCm`, `weightKg`, `preferredFoot`. `medicalStatus` defaults to `FIT` on create. `dateOfBirth` and `primaryPosition` are required through the API, but raw data (KAN-28) can lack them — render `null` safely ("—" / no chip), don't crash.
- Query params (exact names and spellings):
  - `status`: `active` | `released` | `all`, **lowercase** (custom `PlayerStatus.Editor`), default `active`.
  - `position`: `GK, CB, RB, LB, DM, CM, AM, RW, LW, ST` — matches the **primary** position only.
  - `minAge`, `maxAge`: integers 18–99, inclusive, completed years. `minAge > maxAge` → `400` (`InvalidAgeRangeException`, blamed on `minAge`).
  - `medicalStatus`: `FIT` | `INJURED`. `preferredFoot`: `RIGHT` | `LEFT` | `BOTH`.
  - An unknown value of any of these → `400`.
- **Order:** the server sorts by `SQUAD_ORDER`: primary position in enum order (GK → ST), then jersey number (none last), then full name (plain `String` order, no Hebrew collation), then id. The client keeps the server's order and never re-sorts.
- **Age on the server:** `Period.between(dateOfBirth, today).getYears()`, `today` in the JVM's default zone. So someone born on 29 Feb turns a year older on **1 March** in a non-leap year. The client's age must use the same rule (section 4).
- `GET /squad/players/{id}/photo` (`VIEW_ONLY`): the image, or `404` when there's none. `Cache-Control: no-store`. Call it **only** when `hasPhoto` is true.
- `PermissionLevel` (frontend `src/lib/auth/currentUser.ts`): `ADMIN | EDIT_FULL | EDIT_PARTIAL | VIEW_ONLY`. The backend hierarchy is ADMIN > EDIT_FULL > EDIT_PARTIAL > VIEW_ONLY. There's no permission helper in the frontend yet.
- `/squad` is already in the Vite proxy's `BACKEND_PREFIXES`.
- The query client's defaults: `staleTime` 0, `refetchOnWindowFocus` on (TanStack's), retries never on a 4xx.
- `i18next` installed: 23.16.8 (lockfile). No plural keys exist in `he.json` yet.

## Decisions (agreed with Gal — implement these)

### 1. Routes

- `/app/squad` → the new `SquadPage` (title from its existing handle, `nav.squad`; no own `<h1>`).
- `/app/squad/new` → a **stub** "add player" page (title "הוספת שחקן"), one muted line + a link back to the squad. KAN-50 replaces it (and must guard it by permission then; not now).
- `/app/squad/:playerId` → a **stub** player card page (title "כרטיס שחקן"), one muted line + a link back to the squad. KAN-50 replaces it. Don't fetch the player in the stub.
- The סגל nav item stays active on both (it has no `end`; add a test).
- Verify against the installed React Router 8.4 that `new` wins over `:playerId` (static segments rank higher), and test it.

### 2. Data layer (reusable — KAN-52 and KAN-50 use it)

Put the squad's data code in `src/lib/squad/` (names are yours; say what you picked):
- **Types** mirroring the backend: `Player` (= `PlayerResponse`), and the enums as TS unions **with** `as const` arrays of their values in backend order (`POSITIONS`, `MEDICAL_STATUSES`, `PREFERRED_FEET`, `PLAYER_STATUSES`). The arrays drive both the filter options and the URL validation.
- **Filters:** a `SquadFilters` type (`status`, `position?`, `minAge?`, `maxAge?`, `medicalStatus?`, `preferredFoot?`) and two **pure** functions: `parseSquadFilters(URLSearchParams) → SquadFilters` and `squadFiltersToSearchParams(SquadFilters) → URLSearchParams`. Rules:
  - Same param names and spellings as the API, so the page URL and the API query are the same words.
  - `status=active` (the default) is **left out** of the URL; `released` / `all` are written. Empty filters are left out.
  - Anything invalid is **dropped silently**: unknown keys, unknown enum values, wrong case, non-integers, ages outside 18–99, a repeated param (take the first, or drop — decide and say). If both ages are valid but `minAge > maxAge`, drop **both** age values. The page must never send a request the server would answer with `400`.
  - When parsing changed anything, the page rewrites its URL to the normalized form with `replace` (no extra history entry). Unknown keys in the page URL are removed too.
- **Query hook:** `useSquadPlayers(filters)` on TanStack Query, through `apiJson`. Query key `["squad", "players", <normalized filters>]` (export a key prefix, e.g. `SQUAD_PLAYERS_QUERY_KEY = ["squad", "players"]`, so KAN-50 can invalidate all lists after a write). `placeholderData: keepPreviousData` (verify the export in the installed TanStack Query 5), so a filter change doesn't blank the table; expose whether it's showing the previous filters' data (`isPlaceholderData`) so the page can mark the table `aria-busy`.
- **Filters ↔ URL hook:** `useSquadFilters()` → `{ filters, setFilters(patch), clearFilters() }` over `useSearchParams`. Filter changes use `replace` (Back leaves the squad page, it doesn't step back through filters). Keep it independent of the table so KAN-52's cards view shares it, and so a later `view=cards` param survives (unknown keys are dropped today; say how KAN-52 would add one).
- **Age:** a pure `ageOn(dateOfBirth: string, today: Date) → number` (or similar). Parse `yyyy-MM-dd` **by its parts**, never `new Date("yyyy-mm-dd")` (that's UTC midnight, a different day in some zones). `today` = the browser's local date. Same rule as `Period.getYears()`: 29 Feb → older on 1 March in a non-leap year. Unit-test: birthday today, the day before, 29 Feb on 28 Feb / 1 Mar of a non-leap year and on 29 Feb of a leap year, 31 Dec / 1 Jan. Accepted (write it in a comment): the server filters with the JVM zone and the browser displays with its own, so a filter and a shown age can differ by one around midnight on a birthday.
- **Permission helper:** `hasPermission(level, required)` in `src/lib/auth/` with the hierarchy as one ordered list. Unit-test all 16 pairs (or a full table).

### 3. Page layout (approved mockup)

Top to bottom, inside `<main>` (the shell already gives padding and the `<h1>`):

- **Toolbar row** (`flex`, wraps, gap 12px, `justify-between`):
  - Start side: the **status segmented control** — three options פעילים / משוחררים / הכל, one selected. Semantics of a single-choice group, **not tabs** (it filters one table; there are no tab panels): a radio group (`role="radiogroup"` + `role="radio"` / `aria-checked`, arrow-key navigation) or Base UI's toggle group in single mode if its rendered roles are right — check what it actually renders. Accessible name "סטטוס שחקנים". Look: a 36px pill row on `bg-muted`, `rounded-lg`, 3px inner padding; the selected option `bg-card` with a 1px `border-border`, 600; others `text-muted-foreground`, 500.
  - End side: the **"הוספת שחקן"** primary `Button` (`size="default"`, a 16px lucide `Plus` icon), a link to `/app/squad/new` (render as a link, not a button with a click handler). **Only** when `hasPermission(permissionLevel, "EDIT_FULL")`. Not rendered at all otherwise (not disabled).
- **Filter bar** (a `Card`-like row: `bg-card`, 1px `border-border`, `rounded-lg`, padding 12px 16px, `flex` wrap, gap 12px, items aligned), every control 36px (`sm`), each with a visible label (13/500) or, if the mockup shows none, an accessible name — say which you did:
  - **Position** (label "עמדה ראשית" — it matches the primary position only): "הכל" + the ten codes in enum order, the codes shown **as-is in English** in an LTR island.
  - **Age range:** two number inputs "מגיל" / "עד גיל" (`Input size="sm"`, `inputMode="numeric"`, ~72px wide, `min=18 max=99`, `tabular-nums`). A value is applied (URL + request) on **Enter**, on **blur**, or after a **500ms** pause in typing. Invalid input (not an integer, outside 18–99, or min > max) is **not applied**: show a short inline error under/next to the field (`aria-invalid`, `aria-describedby`, `--danger` text), and the table keeps the last valid filters. Emptying a field removes that bound. When the URL changes from outside (Back / clear), the inputs follow it.
  - **Medical status:** הכל / כשיר / פצוע.
  - **Foot:** הכל / ימין / שמאל / שתיהן.
  - **Clear** ("ניקוי סינון", `Button variant="ghost" size="sm"`, lucide `X`): clears the filter-bar filters (position, ages, medical status, foot); **keeps** `status`. Disabled (or hidden — say which) when none is set.
  - For the three selects: use shadcn's `select` (Base UI) if the CLI can add it **without new npm dependencies**; otherwise a styled native `<select>` is fine — say which and why. Check any added component under RTL (popup direction, the check mark side) per CLAUDE.md.
- **Count line** above the table: "מוצגים N שחקנים" with correct Hebrew plurals through i18next (`_one` / `_two` / `_other` for `he` — verify which suffixes the installed i18next 23.16.8 + the runtime's `Intl.PluralRules("he")` actually produce; test 1, 2, 3, 0, 11). 13/500 `text-muted-foreground`, `tabular-nums`. `aria-live="polite"` so a screen reader hears the new count after a filter change. Hidden while the first load is pending and on error.
- **Table** in a `Card`-like frame with `overflow-x-auto` (a wide table scrolls inside it; the page never scrolls sideways at 375px). Use a semantic `<table>` (`<thead>` / `<th scope="col">`). If you add shadcn's `table` component, the same no-new-dependency rule applies.

### 4. Columns (in this order, start → end)

| Header | Content |
| --- | --- |
| `#` | Jersey number, LTR island, `tabular-nums`, 600; `null` → "—" (muted) |
| שם | 32px round avatar + full name. Avatar = `ImageOrInitials` with `path` = `/squad/players/{id}/photo` **only when `hasPhoto`**, else `null`; fallback icon lucide `User`; `alt=""` (the name is beside it). The **name is a `Link`** to `/app/squad/{id}` (14/600, the keyboard path to the card). For a released player, a `Badge variant="muted"` "משוחרר" after the name. |
| עמדה | Primary: `Badge` default; secondary (if any): `Badge variant="outline"`; both `dir="ltr"`, codes as-is |
| גיל | `ageOn(dateOfBirth, today)`, `tabular-nums`; `null` DOB → "—" |
| גובה (ס״מ) | `heightCm`, `tabular-nums`; `null` → "—" |
| משקל (ק״ג) | `weightKg`, `tabular-nums`; `null` → "—" |
| רגל | ימין / שמאל / שתיהן; `null` → "—" |
| מצב רפואי | `Badge variant="success"` כשיר / `variant="danger"` פצוע |

- **No row-actions column in this ticket** (Gal's decision: the real actions — edit, release, delete — are KAN-50's, which adds the column with them). Leave a one-line comment where KAN-50 adds it.
- Rows 52px high, a 1px `border-border` between rows, header 13/500 `text-muted-foreground` on `bg-muted/50` (tokens only), cells 14/400, `text-start`.
- **Released players' rows are dimmed** (e.g. `opacity-60` on the row's cells; the "משוחרר" pill makes it not color-only).
- **Row click:** clicking anywhere on a row opens `/app/squad/{id}` (a convenience for the mouse; the name link is the accessible path, so the `<tr>` gets no `role`/`tabIndex`). Don't navigate when the click was on the link itself (no double navigation), or when the user was selecting text (non-empty `window.getSelection()`). Hover: `bg-muted/50`, `cursor-pointer`.
- Gershayim in "ס״מ" / "ק״ג" is U+05F4, not an ASCII `"`. Check the bytes.

### 5. States

- **First load** (no data yet): a loading state inside the table frame — skeleton rows or a centered "טוען שחקנים…" with `role="status"`. No count, no empty message.
- **Refetch after a filter change** (placeholder data shown): the previous rows stay, the table frame gets `aria-busy="true"` and a subtle visual cue (e.g. reduced opacity). No flash of the loading state.
- **Error** (no data): a message "לא הצלחנו לטעון את רשימת השחקנים." + a "נסו שוב" button (`refetch`). The filter bar stays usable. A background refetch failing while data is shown keeps the data (TanStack's default) — don't replace the table with the error then.
- **Empty**, two kinds:
  - No filter-bar filter set → by status: active "אין עדיין שחקנים פעילים בסגל.", released "אין שחקנים משוחררים.", all "אין עדיין שחקנים במועדון."
  - Some filter set → "אין שחקנים שמתאימים לסינון." + the clear button.
- Photos load **independently** of the table and of each other: the table renders as soon as the list arrives; each avatar shows initials until its image loads, and a `404` / error just stays on initials. No lazy loading or concurrency limit (a squad is ~30 players; say if you think otherwise).

### 6. Copy (Hebrew — use these exactly; keys are yours, all in `he.json`)

| Where | Text |
| --- | --- |
| Status group name | סטטוס שחקנים |
| Status options | פעילים / משוחררים / הכל |
| Add button + new-player stub title | הוספת שחקן |
| Player stub title | כרטיס שחקן |
| Stub lines | טופס הוספת שחקן יופיע כאן בקרוב. / כרטיס השחקן יופיע כאן בקרוב. |
| Stub back link | חזרה לסגל |
| Filter labels | עמדה ראשית / מגיל / עד גיל / מצב רפואי / רגל |
| "Any" option | הכל |
| Medical | כשיר / פצוע |
| Foot | ימין / שמאל / שתיהן |
| Clear | ניקוי סינון |
| Age errors | גיל בין 18 ל-99 / הגיל ההתחלתי גדול מהגיל הסופי |
| Count | מוצג שחקן אחד / מוצגים 2 שחקנים / מוצגים {{count}} שחקנים (and 0: "מוצגים 0 שחקנים") |
| Released pill | משוחרר |
| Columns | # / שם / עמדה / גיל / גובה (ס״מ) / משקל (ק״ג) / רגל / מצב רפואי |
| Loading | טוען שחקנים… |
| Error + retry | לא הצלחנו לטעון את רשימת השחקנים. / נסו שוב |
| Empty | as in section 5 |

- Enum → key mappings exhaustive (`Record<MedicalStatus, …>`, `Record<PreferredFoot, …>`, …), so a new enum value is a compile error.
- Replace the KAN-48 `squad.stub` string (delete it if unused).

## 7. Out of scope (don't build)

- The cards view and the list/cards toggle (KAN-52). The player card, the form, row actions, photo upload (KAN-50).
- Client-side sorting, sortable headers, search by name, pagination, the summary / KPI numbers (KAN-51).
- Any backend change. Dark mode. Mobile polish beyond "nothing overflows".

## Tests (Vitest + React Testing Library + MSW, through `renderWithProviders`)

Add squad answer builders (e.g. `playersReturn(list, { assertQuery? })`, `playerBody(overrides)`) in a new `src/test/msw/squad.ts` (`handlers.ts` stays empty). Use `deferred()` for in-flight states. Fake timers only for the 500ms age debounce; say how you combined them with MSW / `waitFor`.

At minimum:
- **Pure functions:** `parseSquadFilters` / `squadFiltersToSearchParams` (round trip; default status omitted; every invalid case in section 2 dropped; min > max drops both), `ageOn` (section 2's cases), `hasPermission` (all pairs).
- **Filters → query params** (the ticket's "Done when"): for each filter, changing the control sends a request with exactly the expected query string (assert the full `URLSearchParams` in the handler, not just "contains"); the default sends `GET /squad/players` with no `status` param or with `status=active` — pick one and assert it exactly.
- **URL sync:** rendering at `/app/squad?status=released&position=CB&minAge=20` sets the controls and sends that query; changing a control updates the URL (`replace`); clear keeps `status`; an invalid URL (`?position=XX&minAge=10&foo=1&status=ACTIVE`) sends a clean request (no 400) and the URL is rewritten to the normalized form.
- **Age inputs:** Enter applies; blur applies; a pause of 500ms applies; out of range / min > max shows the error, sets `aria-invalid`, and sends **no** request (spy on `fetch`).
- **Table:** rows in the server's order (never re-sorted — give the handler an order no client sort would produce), every column's content incl. "—" for each nullable field, both position chips with `dir="ltr"`, the medical pills, a released row dimmed with the "משוחרר" pill (status `all`).
- **Photos** (the ticket's "Done when"): `hasPhoto: false` → initials and **no** request to that player's photo URL (**spy on `fetch`**); `hasPhoto: true` → `<img>` once loaded; one photo held by `deferred()` doesn't hold back the table or the other avatars; `404` and `500` → initials, no error text anywhere.
- **States:** loading (deferred list) → `role="status"`; error (500, after the client's retries — or use a 4xx-free path that doesn't retry; say which) → message + retry → retry succeeds; empty with no filters (each status's text) vs. empty with filters (the "match" text + clear); a filter change with a deferred answer keeps the old rows and sets `aria-busy`.
- **Count:** 0, 1, 2, 3, 11 render the right Hebrew forms.
- **Add button:** shown for `ADMIN` and `EDIT_FULL`, absent for `EDIT_PARTIAL` and `VIEW_ONLY`; it's a link to `/app/squad/new`.
- **Navigation:** clicking the name link → `/app/squad/{id}` (player stub, `<h1>` "כרטיס שחקן", סגל nav item still `aria-current="page"`); clicking another cell of the row → same; `/app/squad/new` → the add stub, not the player stub.
- **Logout clears it:** after logout and a new login, the squad list is fetched again (the Query cache was cleared by `bindSessionToQueryClient`; one test is enough).

## Manual verification (in Chrome — Safari can't refresh on http://localhost until KAN-56)

You can't drive a browser, so write **exact, numbered steps for Gal** in your summary (he isn't a frontend developer: give the terminal commands and what to click / expect):
1. Make sure he's on `feature/KAN-49-squad-table` (`git branch --show-current`).
2. Start: `docker compose up -d`; backend with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` and `./mvnw spring-boot:run`; `npm run dev` in `frontend/`; open `http://localhost:5173/app` and log in.
3. **Seed data:** a ready-to-paste DevTools console snippet that gets an access token (as in the KAN-47/48 steps) and `POST`s ~12 varied players to `/squad/players` (Hebrew names, all four lines, some without jersey / secondary / height / weight / foot, a couple `INJURED`, a 29-Feb birthday), then releases two of them (`POST /squad/players/{id}/release` with their `version`), and uploads a photo for one (an exact `fetch` with `FormData`; tell him to pick any small JPEG/PNG from his disk, or generate one in the snippet with a `<canvas>` → `toBlob`).
4. Open סגל: the table matches the mockup (Gal has it); the order is GK → ST, then jersey number.
5. Each filter, the status control, the clear button; the URL changes; reload keeps the filters; copy the URL into a new tab → same view.
6. Age inputs: type 30 / 20 → the error, no change; fix it → applies.
7. Invalid URL: `/app/squad?position=XX&minAge=5` → clean view, URL rewritten, no error.
8. A player with a photo shows it; the rest show initials. In DevTools → Network, no photo request for players without `hasPhoto`.
9. Row click and name click → the player stub, סגל still active. "הוספת שחקן" → the add stub.
10. Phone width (375px): the table scrolls inside its frame, the page doesn't.
11. Keyboard: Tab through the status control (arrow keys inside it), the filters, the add button and the name links; focus visible everywhere.

## Constraints

- English everywhere in code, comments, commits (CLAUDE.md rule 2). Every user-visible string in `he.json` (rule 6); list all new Hebrew strings in your summary.
- Run `npm run lint`, `npm run format:check`, `npm test`, `npm run build` in `frontend/`. All green, zero warnings. Don't touch CI or the backend.
- Grep every new / changed component for physical-direction classes (`ml-`, `mr-`, `pl-`, `pr-`, `left-`, `right-`, `text-left`, `text-right`, `border-l`, `border-r`, `rounded-l`, `rounded-r`, ...) and paste the (empty) result.
- **No new npm dependencies.** shadcn components (`select`, `toggle-group`, `table`, ...) are fine **only** if the pinned CLI adds files without touching `package.json` / the lockfile; check `git diff package.json package-lock.json` after each `npx shadcn add`. If one needs a dependency, stop and ask first with the reason and the exact version. Prettier-format added components and check them under RTL.
- Tokens only (no hex, no raw palette colors). No new theme tokens without asking.
- Verify every library behavior you rely on against the **installed** versions, not memory: TanStack Query 5 (`keepPreviousData`, `isPlaceholderData`), React Router 8.4 (`useSearchParams` with `replace`, route ranking `new` vs `:playerId`), i18next 23.16.8 (Hebrew plural suffixes), Base UI (the roles the segmented control / select actually render), lucide-react (every icon name). Say in the summary what you checked and where.
- Small, focused commits in Conventional Commits style with `(KAN-49)`, e.g. `feat(frontend): add the squad table with URL filters (KAN-49)`. Stage files by path, never `git add -A`.
- Update `CLAUDE.md` in the same change (rule 7): in the frontend paragraph, the squad data layer (`src/lib/squad`: types + value arrays, `parseSquadFilters` / `squadFiltersToSearchParams`, invalid params dropped and the URL normalized, `useSquadFilters`, `useSquadPlayers` + `SQUAD_PLAYERS_QUERY_KEY` for invalidation, `keepPreviousData`, `ageOn` mirrors `Period.getYears()`), `hasPermission`, the squad routes (`/app/squad`, `/new` and `/:playerId` stubs until KAN-50), the server's order is never re-sorted, player photos only when `hasPhoto`. As dense as the existing text; replace "`pages/SquadPage`, a stub until KAN-49" with what's true now. Update the "Status" line's frontend part. Add a short "Squad table (KAN-49)" bullet to `docs/design/ui-conventions.md`'s implementation notes (where it lives, the filter bar, the segmented control's semantics, how KAN-52 reuses the hooks).
- **Don't push and don't open a PR.** Stop after committing locally.

## Spec

**Don't edit `docs/spec.md`.** In your summary, list every place in the spec this ticket makes outdated or that should now mention the squad table (e.g. the status line, section 02's frontend paragraph, section 13's Phase 3 row, anything about the squad screen, filters in the URL, ages computed on the client). Give line numbers and what's wrong or missing. I'll prepare the spec update.

## Summary to report back

- Branch, commits (hash + message), files added / changed / deleted.
- The route table (paste it).
- `src/lib/squad` (paste the types, the parse / serialize functions, `ageOn`, the two hooks) and `hasPermission`.
- Which components you added via shadcn (if any), the `package.json` / lockfile diff (must be empty), and how the segmented control and the selects are marked up (roles a screen reader gets).
- How the age inputs apply (Enter / blur / debounce) and the error behavior.
- Test count before → after (frontend was 242), and the new tests by name.
- All new Hebrew strings (key → text), incl. the plural keys and what `Intl.PluralRules("he")` returned for 0, 1, 2, 3, 11.
- The physical-class grep result.
- The manual-verification steps for Gal (section above), complete, with the exact console snippets.
- Every version-specific fact you relied on, and how you verified it.
- Spec places to update. Open ends, risks, anything you weren't sure about.
