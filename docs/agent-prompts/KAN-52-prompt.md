# KAN-52: Frontend squad cards view (list / cards toggle on the squad screen)

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `5164d47` (the KAN-51 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-52-squad-cards`.

## Context

This is Jira KAN-52, in epic **KAN-43 "Frontend MVP"** (Phase 3). It adds a second way to show the squad on `/app/squad`: a grid of player cards, next to the existing table (KAN-49). A view toggle (list / cards) switches between them. Both views show **the same list**: same `GET /squad/players` call, same `useSquadPlayers` hook, same filters, status control, count and server order. Only the rendering differs.

**Frontend only. No backend change, no new API, no new sorting or filtering.** If you believe one is needed, stop and ask.

Reuse what's there; don't rebuild it:

- `src/pages/squad/SquadPage.tsx`: the page (status control, "add player" link, filter bar, count, loading / error / empty messages, the deleted-player notice, the dialog host).
- `src/lib/squad/useSquadFilters.ts`, `filters.ts`: the URL-backed filters and their normalization.
- `src/lib/squad/players.ts`: `useSquadPlayers`, `SQUAD_QUERY_KEY`.
- `src/components/squad/SquadTable.tsx`: the row-click logic (`openFromRow`), the cells.
- `src/components/squad/PlayerBadges.tsx`: `PositionChips`, `MedicalStatusBadge`, `ReleasedBadge`, `None`.
- `src/components/squad/PlayerActionsMenu.tsx` + `playerActions.ts`: the actions menu and which items a user sees. `playerDialogs.ts`: `useDialogHost`, `focusBack`. `PlayerActionDialog.tsx` (`place="table"`).
- `src/components/squad/StatusControl.tsx`: the segmented-control pattern (Base UI `RadioGroup`).
- `src/components/ImageOrInitials.tsx`, `lib/squad/age.ts` (`ageOn`), `lib/squad/labels.ts`, `lib/squad/paths.ts`.
- Test helpers: `renderWithProviders`, `src/test/msw/auth.ts`, `src/test/msw/squad.ts`, `deferred()`.

Read these first:

- `CLAUDE.md`: all of it, especially the frontend paragraph. Every rule applies. In particular: **a test asserting that no request is sent must spy on `fetch`**, and **a test file whose first test opens a lazy page preloads it in `beforeAll`** (KAN-51).
- `docs/design/ui-conventions.md`: all of it. The "Squad table (KAN-49)", "Player card and form (KAN-50)" and "Player actions and photo (KAN-59)" notes, the badges rule, the type scale, RTL / LTR islands.
- `docs/spec.md` section 02 (frontend paragraph: the squad screen). **Read only** (see "Spec").

## Facts I checked on master `5164d47` (re-confirm each against the code; don't take them on faith)

1. **`useSquadFilters` would strip the view from the URL.** Its effect rewrites any URL that isn't in normalized form, and the normalized form is `squadFiltersToSearchParams(filters)`: filters only, so an unknown key like `view` is removed. `setFilters` also rebuilds the whole query from `parseSquadFilters(previous)`, so even if `view` survived the first rewrite, the next filter or status change would drop it. Its own comment says a non-filter page parameter "must be added to that normalization". **There must be exactly one place that normalizes the squad page's URL.** Two hooks that each "correct" the URL with their own idea of normal form will fight (an infinite replace loop, or a lost parameter). Prove with a test that changing the status, a filter, and "clear filters" all keep `view=cards`.
2. **`view` must not be part of `SquadFilters`, the API query or the query key.** `useSquadPlayers(filters)` keys the query on the filters; if `view` leaks in, switching views refetches the list (and sends `view` to the server, which ignores unknown params today, but must not get it). Test: switching views sends **no** new `GET /squad/players` (spy on `fetch`).
3. **Photos are refetched on every switch.** `useAuthorizedImage` has deliberately no cache (object URLs must be revoked), so the cards' avatars request each photo again when the view mounts. Accepted (Gal): a squad is a few dozen players. Don't add an image cache. Mention it in the summary.
4. **The list frame.** Today the page wraps `content` (the table, or the loading / error / empty message) in one `bg-card` bordered `rounded-lg` frame with `overflow-hidden`, which also gets `opacity-60` + `aria-busy` while placeholder data shows (`isPlaceholderData`). Cards must not sit inside a card frame. Restructure so: the messages keep their frame in both views; the table keeps its frame; the cards grid has no frame; **the busy state (`aria-busy` + `opacity-60` while a new filter's list loads) applies to whichever view shows.**
5. **"חזרה לסגל" doesn't keep the view.** Every in-app link back to the squad goes to a bare `SQUAD_PATH`, so it already loses the filters and will now lose the view too. **Out of scope here** (Gal): it's Jira KAN-61. Don't change those links. Browser Back keeps both, since filters replace the history entry.
6. **Dialogs.** `PlayerActionDialog`'s `place="table"` means "on the squad page" (a 404 offers a refetch instead of a link). The cards view uses `place="table"` too. Don't rename it unless trivial; if you do, say so.

## Decisions (agreed with Gal — implement these)

### 1. The view in the URL

- Parameter **`view`**. Values: `cards`, or absent = list (the default, **never written to the URL**, like the default status). Any other value (`view=table`, `view=CARDS`, a repeated `view`) is treated as the default and removed from the URL by the normalization (with `replace`), the same way an invalid filter is.
- The normalized order: the filters as today, then `view` last. Say what you chose if you deviate.
- Switching the view **replaces** the history entry (like the filters): Back leaves the page.
- Keep the filters' hook and the view in one URL-ownership unit. A reasonable shape: a pure, unit-tested `parseSquadView` / serialize pair next to `filters.ts`, and one hook (extend `useSquadFilters`, or a `useSquadPageQuery` that owns both) whose single normalization effect covers filters + view and whose `setFilters` / `clearFilters` / `setView` keep the other part. Your call; explain it, and update the hook's doc comment.
- The view is a page preference, not a filter: "ניקוי סינון" never resets it, and `hasFilterBarFilters` must not count it.

### 2. The view toggle (layout per the mockup; you can't open it, so it's described here)

- Placement: in the toolbar's end group, **before** "הוספת שחקן" (in RTL: to its right), with a 12px gap. When the user may not add players (below `EDIT_FULL`), the toggle is alone at the end. The status control stays at the start.
- Same component pattern and look as `StatusControl`: a Base UI `RadioGroup` (one tab stop, arrow keys, `role="radiogroup"` / `role="radio"` + `aria-checked`), 36px high, `bg-muted`, `rounded-lg`, 3px padding; the selected option `bg-card` with a 1px `border-border`. Two **icon-only** options, about 36px wide, 18px lucide icons: list = `List`, cards = `LayoutGrid`. Each option has an accessible name (`aria-label`) and a `title` tooltip: "רשימה" / "כרטיסיות". The group's label: "תצוגה". Option order: list first (at the start).
- If you can share the segmented-control styling with `StatusControl` without changing its behavior or its tests, do; otherwise keep the classes consistent by hand and say so.

### 3. The cards grid

- `grid-template-columns: repeat(auto-fill, minmax(232px, 1fr))`, gap 16px. **Guard the narrow end**: at a width under 232px the card must not overflow (`minmax(min(232px, 100%), 1fr)` or equivalent). The page scrolls; the grid has no inner scroll.
- A `<ul>` of `<li>` cards (or a list of `<article>`s inside `<li>`), so a screen reader hears "list, N items". Say what you chose.
- Order = the array's order (the server's). Never re-sort.

### 4. A card

`bg-card`, 1px `border-border`, `rounded-lg`, 16px padding, a column with 14px gaps, no shadow. Top to bottom:

- **Header row** (`flex`, `items-start`, 12px gap):
  - the avatar: 56px round, `ImageOrInitials` with `path` only when `hasPhoto` (else `null`: no request), `bg-secondary text-secondary-foreground`, initials 18/600, `object-cover`, `fallbackIcon={User}` like the table;
  - a column (4px gap, `min-w-0`, grows): the **name** 15/600, one line with an ellipsis when too long (`truncate`), as a **`Link` to the player's card** (the keyboard path, like the table's name link, same focus ring); under it `PositionChips` (primary filled, secondary outlined, LTR codes, "—" when neither). If the chips wrap or need `flex-wrap` at 232px, say what you did;
  - the **jersey number** at the end: `#9` at 26/700, `tabular-nums`, `text-primary`, `leading-none`, as an LTR island (`dir="ltr"`). No number → `None` ("—", muted), **without `#`**.
- **Stats** (`<dl>`): 4 equal columns (`grid-cols-4`), centered, 4px gap, 10px vertical padding, a 1px `border-border` top and bottom (the mockup uses a lighter divider; no exact token, `border-border` is fine, or say what you used). Each: `<dt>` 11px muted (say if you use 12px for legibility) over `<dd>` 14/600 `tabular-nums`. Terms: "גיל", "גובה", "משקל", "רגל". Values as the table computes them: `ageOn(dateOfBirth, today)`, `heightCm`, `weightKg` (plain numbers, no units; the terms carry no units either, matching the mockup), the foot's Hebrew label. Any missing value → `None`.
  - The `<dl>` must be valid: each `<dt>`/`<dd>` pair wrapped in a `<div>` inside the `<dl>` is allowed by HTML; keep it that way.
- **Footer row** (`flex`, `items-center`, `justify-between`): at the start `MedicalStatusBadge`, plus `ReleasedBadge` beside it for a released player (Gal's decision: **a released card carries the "משוחרר" pill**, as the table row does, though the mockup only dims it); at the end the **same `PlayerActionsMenu`** as the table (its existing 36px ghost icon button with `EllipsisVertical`, not the mockup's horizontal dots, for consistency), with `playerActions(player, permissionLevel)` and the page's `onDialog`.
- **Released player**: the card's content is `opacity-60`, **except the actions menu**, which is never dimmed (as in the table). The pill makes it not color/opacity alone.
- **Click to open**: a click anywhere on the card that isn't on a link or button opens the player's card (`playerPath`), as a table row does: ignore clicks bubbled from the menu's portal (React bubbles portal clicks up the component tree), clicks on `a, button`, and a click that ends a text selection. **Share this logic with `SquadTable`** (extract one helper, e.g. `isOpenClick(event)` in `components/squad`), don't copy it. `cursor-pointer` on the card, and a hover state (e.g. `hover:border-input` or `hover:bg-muted/30`; say what you chose). The card itself is **not** focusable and has no `role="button"` / `onKeyDown`: the name link is the keyboard path.

### 5. States and the page

- Loading, error + retry, empty (with or without filters) are the existing messages, unchanged, in their frame, in both views.
- The count, the deleted-player notice, the dialogs and their focus return work the same in both views. After a release from a card in the "active" view the card disappears; `focusBack` falls back when the opener is gone, as in the table. Test one dialog flow from a card (release is enough).
- No `<h1>`: the title comes from the route.

### 6. Copy (Hebrew; all in `he.json` under `squad.*`)

| Where | Text |
| --- | --- |
| Toggle group label | תצוגה |
| List option | רשימה |
| Cards option | כרטיסיות |
| Stat terms | גיל / גובה / משקל / רגל |

Reuse existing keys where the wording matches exactly (e.g. `squad.columns.age` is "גיל"; but `squad.columns.height` is "גובה (ס״מ)", which doesn't match "גובה": add a new key rather than change the table's header). Say which keys you reused and which you added.

## Out of scope (don't build)

- Changing the "back to squad" links (KAN-61).
- Remembering the view anywhere but the URL (no localStorage).
- Sorting, new filters, a name search, pagination or virtualization.
- An image cache.
- Any backend change.

## Tests

Vitest + React Testing Library + MSW, through `renderWithProviders`. Put the cards-view tests in a new file (e.g. `SquadCards.test.tsx`) rather than growing `SquadPage.test.tsx` (853 lines), with the `beforeAll` preload of the lazy squad page. `handlers.ts` stays empty.

At minimum:

- **Pure functions:** the view parse / serialize (absent, `cards`, an invalid value, a repeated parameter), and the shared open-click helper if it's unit-testable.
- **URL:**
  - default = list, no `view` in the URL;
  - toggling to cards puts `view=cards` in the URL, and it survives a reload (render again at that URL → cards);
  - `view=bogus` → the list, and the URL is corrected (no `view`);
  - changing the status, a filter and "ניקוי סינון" each keep `view=cards`, and the filters still work;
  - a URL with filters + `view=cards` renders the filtered cards (the request carries the filters and **no** `view`).
- **No refetch on switch:** toggling the view sends no new `GET /squad/players` (spy on `fetch`).
- **Card content:** a full player (photo requested once, `#9` in an LTR island, both chips, age / height / weight / foot, medical pill); a player without photo (no photo request: spy on `fetch`; initials shown); no jersey number ("—", no `#`); missing stats → "—"; a released player (the "משוחרר" pill, content dimmed, the menu not dimmed).
- **Order:** cards in the server's order.
- **Opening:** clicking the card body opens the player's card; clicking the name link does; clicking the actions trigger or a menu item does not navigate; a text selection doesn't.
- **Actions:** the menu's items match the table's for a given permission level; a release from a card's menu works end-to-end (dialog, request, the card leaves the active list).
- **Busy state:** while a new filter's list loads in cards view, the grid is `aria-busy` (deferred handler, not timing).
- **Toggle a11y:** it's a radiogroup named "תצוגה" with two radios named "רשימה" / "כרטיסיות"; arrow keys switch the view.
- **Existing tests** still pass unchanged; if you had to touch any, list them and why.

## Manual verification (in Chrome — Safari can't refresh on http://localhost until KAN-56)

You can't drive a browser, so write **exact, numbered steps for Gal** in your summary. He isn't a frontend developer: give the terminal commands and what to click and expect. Cover:

- **Setup:** `docker compose up -d`; the backend with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` + `./mvnw spring-boot:run`; `npm run dev` on the branch.
- **Toggle:** switch to cards, reload, copy the URL into a new tab: still cards. Switch back: the URL has no `view`.
- **Same data:** with a filter set (e.g. a position), both views show the same players in the same order and the same count. In DevTools → Network, switching views sends no new `squad/players` request (photo requests are expected).
- **Cards:** a player with and without a photo, without a jersey number, a released player (status "הכל"): pill + dimmed, menu not dimmed.
- **Clicks:** card body opens the player; the menu works; release a player from a card.
- **Keyboard:** Tab reaches the toggle once, arrows switch; Tab through cards goes name link → menu.
- **Widths:** 1440, 1024, 768 and 375 px. No sideways page scroll; cards never overflow; long names truncate.

## Constraints

**Language and copy**

- English in code, comments and commits (CLAUDE.md rule 2).
- Every user-visible string goes in `he.json` (rule 6). List all new Hebrew strings in your summary.

**Checks**

- `npm run lint`, `npm run format:check`, `npm test` and `npm run build`, all green with zero warnings.
- Report the frontend test count before → after (it was 723 on master).
- Report the bundle size change (main bundle and the squad page's chunk) before / after.

**Styling**

- Grep every new or changed component for physical-direction classes (`ml-`, `mr-`, `pl-`, `pr-`, `left-`, `right-`, `text-left`, `text-right`, `border-l`, `border-r`, `rounded-l`, `rounded-r`, …) and paste the result. It must be empty.
- Tokens only. No hex, no raw palette colors in components.

**Dependencies**

- **No new npm dependencies.** `git diff package.json package-lock.json` must be empty. Paste it.

**Verify library behavior against the installed versions, not memory**

Say in the summary what you checked and where (`package.json` / `node_modules`):

- React Router 8.4: `useSearchParams` functional updater and `replace`, and that a replace to the same search doesn't loop;
- Base UI `RadioGroup` / `Radio` in the installed version: keyboard behavior under `DirectionProvider` RTL, `aria-label` on an icon-only `Radio.Root`;
- TanStack Query 5: `isPlaceholderData` with the existing `placeholderData` setting in `useSquadPlayers`;
- lucide-react: that `List` and `LayoutGrid` exist under those names in the installed version.

**Commits**

- Small, focused commits in Conventional Commits style with `(KAN-52)`.
- Stage files by path, never `git add -A`.

**Documentation (rule 7)**

- Update `CLAUDE.md` in the same change: the `view` URL parameter and the one-normalizer rule, the shared open-click helper.
- Replace the "KAN-52 …" forward references (in `useSquadFilters.ts`, in `ui-conventions.md`'s "Squad table" note) with what's true now.
- Add a short "Squad cards (KAN-52)" implementation note to `docs/design/ui-conventions.md`: the toggle, the grid, the card anatomy, and the two deliberate deviations from the mockup (the "משוחרר" pill on a released card; the table's vertical-dots menu and rounded-full chips instead of the mockup's).

**Don't push and don't open a PR.** Stop after committing locally.

## Spec

**Don't edit `docs/spec.md`.** In your summary, list every place this ticket makes outdated, or that should now mention it, with line numbers and what's wrong or missing. I expect at least:

- line 5 (status);
- section 02's frontend paragraph, the squad-screen sentences (the view toggle, `view` in the URL);
- section 13's Phase 3 row.

I'll prepare the spec update.

## Summary to report back

- Branch, commits (hash + message), files added / changed / deleted.
- How the URL is owned now (the hook(s), the single normalization): paste the hook and the parse / serialize functions.
- The shared open-click helper and how `SquadTable` uses it now.
- How the frame / busy state is structured in both views.
- Test counts before → after, and the new tests by name. Existing tests changed, if any, and why.
- All new Hebrew strings (key → text), and which existing keys you reused.
- The physical-class grep result, the `package.json` / lockfile diff (empty), bundle sizes.
- The manual-verification steps for Gal, complete.
- Every version-specific fact you relied on, and how you verified it.
- Spec places to update. Open ends, risks, anything you weren't sure about.
