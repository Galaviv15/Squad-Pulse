# KAN-61: "Back to squad" links return to the last squad view and filters

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `c513b89` (the KAN-60 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-61-back-to-squad`.

## Context

This is Jira KAN-61, in epic **KAN-43 "Frontend MVP"**. It is a follow-up from KAN-52 (squad cards view, PR #36), which built on KAN-49 (squad table with filters in the URL).

On `/app/squad` the filters and the view (`view=cards`) live in the page URL, owned by one hook: `useSquadPageQuery` (`src/lib/squad/useSquadPageQuery.ts`), with one normalizer (`squadPageSearchParams` in `view.ts`, `parseSquadFilters` in `filters.ts`). The browser's Back button already keeps them. But every **in-app** link back to the squad goes to a bare `SQUAD_PATH` (`/app/squad`). So a coach who filtered the squad and switched to cards, opened a player and clicked "חזרה לסגל", lands on the default list with no filters.

This ticket makes the links that mean "back to where I was" return to the **last squad URL seen in this tab** (filters + view). The navigation entry points stay a fresh, unfiltered squad.

**Frontend only. No backend change. No change to how the filters or the view are stored in the URL** (the URL format, the normalizer and `useSquadPageQuery`'s replace behavior stay as they are). If you believe one of these is needed, stop and ask.

Read these first:

- `CLAUDE.md`: all of it, especially the frontend paragraph. Every rule applies. In particular: **a test file whose first test opens a lazy page preloads it in `beforeAll`** (KAN-51); a request no handler covers fails the test (KAN-60); unexpected console output fails the test (KAN-58).
- `docs/design/ui-conventions.md`: the "Squad table (KAN-49)", "Player card and form (KAN-50)", "Player actions and photo (KAN-59)", "Squad cards (KAN-52)" and "Dashboard (KAN-51)" notes.
- `src/lib/squad/useSquadPageQuery.ts`, `view.ts`, `filters.ts`, `paths.ts`, `routeState.ts`.
- `docs/spec.md` section 02 (frontend paragraph). **Read only** (see "Spec").

## Facts I checked on master `c513b89` (re-confirm each against the code; don't take them on faith)

1. **Every use of `SQUAD_PATH` outside tests** (from `grep -rn SQUAD_PATH src`):
   - `pages/squad/PlayerPageStates.tsx`: `BackToSquadLink` → used on the player card's action area, in `PlayerQueryState`'s 404 message, and in `RequireEditFull`'s "no permission" message.
   - `pages/squad/PlayerCardPage.tsx` `leaveDeleted`: `navigate(SQUAD_PATH, { replace: true, state: deletedPlayerState(...) })` after a delete from the card.
   - `components/squad/PlayerActionDialog.tsx` `ActionError`: the 404 "חזרה לסגל" link. It is rendered **only** when `place !== "table"` (on the squad page itself a 404 offers a refetch instead).
   - `components/squad/PlayerForm.tsx`: the "notFound" alert's "חזרה לסגל" link.
   - `pages/squad/NewPlayerPage.tsx`: `cancelTo={SQUAD_PATH}` (the add form's "ביטול"). **Not listed in the Jira ticket, but in scope** (decided with Gal).
   - `components/dashboard/SquadSummaryCards.tsx`: the dashboard's "לטבלת הסגל" link.
   - `paths.ts` itself (`playerPath`, etc.). Those must stay as they are.
   - The sidebar's "סגל" item (`components/shell/Sidebar.tsx`, `NAV_ITEMS`) is a `NavLink` to `/app/squad`.
   - `EditPlayerPage`'s `cancelTo` is `playerPath(playerId)` (back to the card). **Leave it.**
   If you find another use, stop and list it before deciding.
2. **The squad page URL is normalized in an effect.** `useSquadPageQuery` reads `searchParams`, computes `normalized = squadPageSearchParams(filters, view).toString()` and, when `current !== normalized`, rewrites it with `replace` in a `useEffect`. So on the first render of a non-normalized URL (`?position=XX&view=bogus`), `location.search` is **not** yet normalized. **What is remembered must be the normalized string**, never the raw `location.search`. Otherwise a stale or invalid query could be stored and later sent to the API.
3. **The delete redirect already keeps the search.** `SquadPage` reads the one-shot notice from `location.state` (`deletedPlayerNameFromState`) and clears it with `navigate({ pathname, search, hash }, { replace: true, state: null })`. So redirecting to a filtered squad URL should show the notice once and keep the filters and view. Prove it with a test; don't assume.
4. **The session is cleared in one subscription.** `bindSessionToQueryClient` (`src/lib/auth/bindSessionToQueryClient.ts`) empties the Query cache whenever the session goes `"unauthenticated"` (logout, a refused refresh, a logout in another tab). It's called from `main.tsx` and from the test helper `src/test/render.tsx`.
5. **Module state survives between tests in one file.** `src/test/setup.ts` already resets other module-level state in `beforeEach` (`resetAuthSessionForTests`, `resetSessionBootstrap`).

## Decisions (agreed with Gal — implement these)

### 1. Which links return to the last squad URL

| Link | Behavior |
| --- | --- |
| `BackToSquadLink` (card action area, player 404, "no permission") | **last squad URL** |
| Delete redirect from the card (`leaveDeleted`) | **last squad URL**, still `replace: true` with the deleted-player state |
| `ActionError` 404 link (card dialogs) | **last squad URL** |
| `PlayerForm` "notFound" link | **last squad URL** |
| `NewPlayerPage` "ביטול" (`cancelTo`) | **last squad URL** |
| Dashboard "לטבלת הסגל" | **stays bare `/app/squad`** |
| Sidebar "סגל" | **stays bare `/app/squad`** |

Reasons to document (in code comments where the bare path is kept, and in the docs):

- **Dashboard:** the tile next to the link shows the number of **active** players. A link to a remembered filtered view (e.g. released players) would show a list that contradicts that number. The bare path is the active list.
- **Sidebar:** primary navigation is a fresh start. Clicking "סגל" while already on a filtered squad resets the filters today. Keep that.

### 2. What is remembered and where

- **The value:** `/app/squad` plus the **normalized** query string (`?` + `normalized`, or no `?` when it's empty). Record it from `useSquadPageQuery` (the one owner of the page's URL). Record **only when the URL is already normalized** (`current === normalized`) — or record `normalized` directly; either way never the raw search. Say which you chose and why. A unit/integration test must show that visiting a non-normalized URL never leaves a non-normalized value remembered.
- **Storage:** memory only, per tab. A small module in `src/lib/squad` (e.g. `squadReturnPath.ts`), with a getter that falls back to `SQUAD_PATH` when nothing is remembered. **No localStorage, no sessionStorage.** A reload falls back to the bare path.
- **Reading it in components:** one helper/hook the links use (e.g. `useSquadReturnPath()` or `squadReturnPath()`), so no call site builds the path itself. The value changes only while the squad page is mounted, and none of the link-holding pages are mounted at the same time as the squad page… **except** that `PlayerActionDialog` can be mounted on the squad page (with `place="table"`, where the 404 link isn't rendered). Check that no link is rendered on the squad page itself with a stale value. If you use `useSyncExternalStore` or a plain read during render, explain why it's correct here.
- **Clearing on session end:** clear the remembered value whenever the session goes `"unauthenticated"`, through the same kind of subscription as `bindSessionToQueryClient` (extend it, or add a sibling bound in both `main.tsx` and `src/test/render.tsx`). Your call; explain it. The reason: a logout then a login as a user of another club, in the same tab, must not start from the previous user's squad URL.
- **Tests isolation:** reset it in `src/test/setup.ts`'s `beforeEach`, next to the other module resets.

### 3. Path building

- Keep `SQUAD_PATH` as the bare path (used by the router, the sidebar, the dashboard and `playerPath`).
- Don't change `paths.ts`'s other functions.

## Out of scope (don't build)

- Any change to the URL format of the filters or the view, or to `useSquadPageQuery`'s normalization/replace logic (beyond recording the normalized value).
- Persisting across reloads or tabs.
- Changing the sidebar or dashboard links (beyond a comment saying why they stay bare).
- Any backend change.

## Tests

Vitest + React Testing Library + MSW, through `renderWithProviders`. `handlers.ts` stays empty. Navigate between the squad page and player pages inside one rendered router (not by rendering each page separately), so the test proves the real flow. **Preload the lazy squad pages in `beforeAll`** in any new file (KAN-51 pattern). KAN-62 (flaky under CPU load: 1 s `findBy` timeouts on lazy chunks) is still open: don't add a new source of it.

At minimum:

- **Unit:** the store/getter (nothing remembered → `/app/squad`; remembered value returned; reset), and the session-end clearing.
- **Normalization:** visit `/app/squad?position=XX&view=bogus` (or another invalid mix), then open a player and click "חזרה לסגל": land on the normalized URL (assert `location.pathname + location.search`), never the raw one.
- **Main flow (ticket's "Done when"):** filter (e.g. a position) + cards view → open a player from a card → "חזרה לסגל" lands on the same filters and `view=cards`, and the request carries the filters and no `view`.
- **Delete redirect:** same setup → delete the player from the card → the squad shows with the same filters and view, the one-time deleted notice shows, and the card isn't left in history (as today).
- **Each "last URL" link:** the player 404 state, the "no permission" message, `ActionError`'s 404 link, `PlayerForm`'s "notFound" link, and `NewPlayerPage`'s "ביטול". It's fine to prove the shared `BackToSquadLink` once and each of the others by its `href`.
- **Bare links stay bare:** with a remembered filtered URL, the dashboard's "לטבלת הסגל" and the sidebar's "סגל" both have `href="/app/squad"`.
- **No memory:** a direct visit to a player URL (no squad visit in this "tab") → "חזרה לסגל" goes to `/app/squad`.
- **Session end:** remember a filtered URL, end the session (e.g. a logout path the existing tests use), log in again → "חזרה לסגל" goes to `/app/squad`.
- **Existing tests** still pass unchanged; if you had to touch any, list them and why.

Run the full suite twice in parallel (2×) at least 10 times and report the result, given KAN-62.

## Manual verification (in Chrome)

You can't drive a browser, so write **exact, numbered steps for Gal** in your summary. He isn't a frontend developer: give the terminal commands and what to click and expect. Cover:

- **Setup:** `docker compose up -d`; the backend with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` + `./mvnw spring-boot:run`; `npm run dev` on the branch (`https://localhost:5173`).
- Filter + cards → open a player → "חזרה לסגל": same filters and view.
- Same, through the add form's "ביטול".
- Delete a player (as admin) from a filtered + cards state: back on the same view with the notice.
- The dashboard link and the sidebar item: always the unfiltered list view.
- Reload on a player page, then "חזרה לסגל": the bare squad (accepted).
- Logout and login: the bare squad.

## Constraints

**Language and copy**

- English in code, comments and commits (CLAUDE.md rule 2).
- No new user-visible strings are expected. If you add one, it goes in `he.json` (rule 6); list it.

**Checks**

- `npm run lint`, `npm run format:check`, `npm test` and `npm run build`, all green with zero warnings.
- Report the frontend test count before → after (take the "before" from master yourself).

**Dependencies**

- **No new npm dependencies.** `git diff package.json package-lock.json` must be empty. Paste it.

**Verify library behavior against the installed versions, not memory**

Say in the summary what you checked and where (`package.json` / `node_modules`):

- React Router (installed 8.x): that `navigate(to, { replace, state })` with a `to` string that carries a query string sets both `pathname` and `search`; that `Link to="/app/squad?…"` renders that exact `href`; and `useSearchParams` / `useLocation` behavior you rely on.
- React 19: if you use `useSyncExternalStore`, its behavior in the installed version.

**Commits**

- Small, focused commits in Conventional Commits style with `(KAN-61)`.
- Stage files by path, never `git add -A`.

**Documentation (rule 7)**

- Update `CLAUDE.md` in the same change: the "last squad URL" rule (which links use it, which stay bare and why, memory only, normalized only, cleared on session end, reset in tests).
- Update `docs/design/ui-conventions.md` where it describes "חזרה לסגל" and the dashboard link.
- Remove or update any remaining "KAN-61" forward reference in code or docs (grep for it).

**Don't push and don't open a PR.** Stop after committing locally.

## Spec

**Don't edit `docs/spec.md`.** In your summary, list every place this ticket makes outdated, or that should now mention it, with line numbers and what's wrong or missing. I expect at least section 02's frontend paragraph, where it says that after a permanent deletion from the card "the app shows the squad instead" (now: the squad as last viewed in this tab). I'll prepare the spec update.

## Summary to report back

- Branch, commits (hash + message), files added / changed / deleted.
- The store and the helper/hook the links use: paste them. Where the value is recorded, and the exact proof that only a normalized value is ever stored.
- How the session-end clearing is wired (main and tests).
- Every call site changed, and the two that stay bare with their comments.
- Test counts before → after, and the new tests by name. Existing tests changed, if any, and why. The 2× parallel stress result.
- The `package.json` / lockfile diff (empty).
- The manual-verification steps for Gal, complete.
- Every version-specific fact you relied on, and how you verified it.
- Spec places to update. Open ends, risks, anything you weren't sure about.
