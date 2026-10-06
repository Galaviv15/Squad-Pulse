# KAN-48: Frontend app shell — RTL layout, navigation, club and user header

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `dd9a70b` (the KAN-47 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-48-app-shell`.

## Context

This is Jira KAN-48, in epic **KAN-43 "Frontend MVP"** (Phase 3). KAN-45 (infra), KAN-46 (API client and session) and KAN-47 (auth screens, session bootstrap, protected routes, logout) are done and merged. This ticket builds the frame every product screen lives in: a dark green sidebar on the start side (right in RTL) and a content area with a top bar. It blocks **KAN-49** (squad table) and **KAN-51** (dashboard), which will render inside this shell and replace the two stub pages you add here.

KAN-47 prepared almost everything this ticket needs. Reuse it; don't rebuild it:
- `useCurrentUser()` (`src/lib/auth/currentUser.ts`): `fullName`, `title`, `hasPhoto`, `club: { id, name, hasLogo }`. Only below `RequireAuth`, which renders its children only once `/me` has data, so the shell never has to handle "no user yet".
- `useLogout()` (`src/lib/auth/logout.ts`): `{ logout, pending }`. It already goes to `/app/login` **without** `next` and broadcasts to other tabs.
- `useAuthorizedImage(path | null)` (`src/lib/api/useAuthorizedImage.ts`): `none | loading | loaded | missing | error`; `null` fetches nothing.
- `<BrandLogo tone="dark" />` (`src/components/BrandLogo.tsx`).
- `src/app/router.tsx` and `RequireAuth.tsx` both say "the app shell (KAN-48) becomes RequireAuth's layout". Do that.

Read these first:
- `CLAUDE.md`: the whole frontend paragraph (KAN-45 + KAN-46 + KAN-47). Every rule in it applies (routes under `/app`, `renderWithProviders`, MSW `onUnhandledRequest: "error"`, backend only through `src/lib/api`, images only through `useAuthorizedImage`, tokens only, logical properties, i18n for every string, `DirectionProvider`).
- `docs/design/ui-conventions.md`: all of it, especially "Shell", the type scale, density, the sidebar tokens, the icon rule, and the implementation notes on `Button` sizes and `BrandLogo`. The approved shell mockup is in a design canvas you can't open; everything you need from it is written out in "Look" below.
- `docs/spec.md` sections 01 (RTL / Hebrew), 04 (titles, `/me`) and 13 (Phase 3). **Read only. Don't edit the spec** (see "Spec").
- Frontend: `src/app/*` (router, guards, their tests), `src/pages/*` (`PlaceholderPage`, `NotFoundPage`), `src/lib/auth/*`, `src/lib/api/useAuthorizedImage.ts` + its test (note how it stubs `URL.createObjectURL` in jsdom), `src/components/BrandLogo.tsx`, `src/components/ui/*`, `src/test/*` (esp. `render.tsx`, `setup.ts`, `msw/auth.ts`), `src/i18n/locales/he.json`, `src/index.css`.
- Backend, to confirm the contracts below: `auth/Title.java`, `auth/CurrentUserResponse.java`, `auth/ClubResponse.java`, `auth/ClubLogoController.java` (`GET /clubs/me/logo`), `auth/StaffPhotoController.java` (`GET /users/me/photo`).

## Facts I checked on master `dd9a70b` (re-confirm each one against the code; don't take them on faith)

- `GET /clubs/me/logo` and `GET /users/me/photo` exist, need `VIEW_ONLY`, and answer `404` when there's no image. `/clubs` and `/users` are already in the Vite proxy's `BACKEND_PREFIXES`.
- `club.hasLogo` and `hasPhoto` are on `/me`. Call the image endpoints **only** when the flag is true (pass `null` to `useAuthorizedImage` otherwise). A test must prove no request is sent when the flag is false (`onUnhandledRequest: "error"` makes that automatic, as long as no handler is registered for it).
- `Title` has exactly six values: `CLUB_MANAGER`, `HEAD_COACH`, `ASSISTANT_COACH`, `GOALKEEPING_COACH`, `FITNESS_COACH`, `ANALYST`. There are no Hebrew translations for them in `he.json` yet. The backend has no gender field.
- There is no "league" field anywhere in the backend (the mockup shows "ליגה לאומית" under the club name). **Leave it out.** Don't invent data.
- Current route table: `/app` → `SessionGate` → (`PublicOnlyRoute` → login / forgot / reset) and (`RequireAuth` → index = `PlaceholderPage`, `*` = `NotFoundPage`). Top-level `*` = `NotFoundPage` too.
- `PlaceholderPage` holds KAN-47's **temporary** logout button (comment points at KAN-48). `router.test.tsx` and `sessionRouting.test.tsx` reference the placeholder.
- `BrandLogo` has a fixed size (40px mark, 22px wordmark) and no size prop.

## Decisions (agreed with Gal — implement these)

### 1. Routes

- The shell is the **layout element of the protected branch**: `RequireAuth` → `AppShell` (renders the sidebar, the top bar and an `<Outlet />` inside `<main>`) → the protected pages. Keep `RequireAuth`'s job unchanged (session guard + `/me` gate); put the shell either as `RequireAuth`'s child layout route or render it from `CurrentUserGate`. Pick one, say why. Either way: the shell renders only once `/me` has data, and the public auth screens never get the shell.
- Protected routes after this ticket:
  - `/app` (index) → `DashboardPage`, a **stub** (KAN-51 replaces it).
  - `/app/squad` → `SquadPage`, a **stub** (KAN-49 replaces it).
  - `/app/*` → not-found, **inside the shell** (see 5).
- The stubs exist so that **no live nav item links to a dead page** (the ticket's rule). Each stub is the page's content area only: one short muted line (copy below). No fake data, no cards with numbers.
- **Delete `PlaceholderPage`** and its temporary logout button, and the `placeholder.*` / `app.welcome` / `app.phaseNote` strings if nothing else uses them. Whatever the existing tests checked through the placeholder (the LTR `Badge` island, `dir` not overridden, `/` → `/app`) must still be covered somewhere sensible. Say where each check moved.
- The top-level `*` (outside `/app`) keeps the full-page not-found.

### 2. Page title

- Each protected route declares its title once, through the route's `handle` (e.g. `handle: { titleKey: "nav.dashboard" }`), read in the shell with `useMatches()` (the deepest match with a title wins). Give the handle a TypeScript type, not `any`. Verify `handle` / `useMatches` against the **installed** React Router 8.4 (types included), not memory.
- The top bar shows it as the page's only `<h1>` (22/700). Pages inside the shell must not render their own `<h1>`.
- Also set `document.title` to `"<page title> · SquadPulse"` (and plain `"SquadPulse"` when there's no page title). Restore it sensibly on unmount, so the auth screens don't keep "דשבורד · SquadPulse". Test it.

### 3. Sidebar

Order from the top: club block, navigation, "בקרוב" group, then `BrandLogo` at the bottom.

- **Club block** (top): the club logo (40×40, `rounded-lg`) beside the club name.
  - `club.hasLogo` true → `useAuthorizedImage("/clubs/me/logo")`; when `loaded`, an `<img>` with `object-contain` on a `bg-sidebar-accent` square. `alt=""` (the name is right next to it; say if you disagree and why).
  - Fallback (no logo, or `loading`, `missing`, `error`): the same square, `bg-sidebar-accent text-sidebar-accent-foreground`, the club's **initials** (15/700). An image failure never shows an error: it just stays on the fallback.
  - Name: 15/600, `text-sidebar-foreground`. Club names can be up to 100 chars: clamp to 2 lines (`line-clamp-2`, `break-words`) and put the full name in `title`. No league line.
- **Navigation:** `<nav>` with an i18n `aria-label` ("ניווט ראשי"). Live items, in this order:
  - דשבורד → `/app` (`NavLink` with `end`, so it isn't active on `/app/squad`).
  - סגל → `/app/squad` (also active below it later, e.g. `/app/squad/123`: no `end`).
  - Use `NavLink` so the active one gets `aria-current="page"`.
- **"בקרוב" group** below them: a small label "בקרוב", then לו״ז, אימונים, משחקים, לוח טקטי. These are **not links and not buttons**: plain text items, not focusable, no `href`, no click handler. Make them read as disabled (muted color, reduced opacity) and make it clear to assistive technology that they belong to the "coming soon" group (e.g. a `<ul>` labelled by the "בקרוב" label via `aria-labelledby`). Don't put them inside the same list as the live links. Say what a screen reader announces.
- **Bottom:** `<BrandLogo tone="dark" />`, pushed to the bottom (`mt-auto`). Use it at its current size. If it looks wrong at the bottom of a 240px sidebar, say so in the summary rather than adding a size variant on your own.

### 4. Top bar

- In the content column, above `<main>`: `<header>` with the page title (start side) and the user block (end side).
- **User block:** avatar, then name + title stacked, then the logout button.
  - Avatar: 36px circle. `hasPhoto` true → `useAuthorizedImage("/users/me/photo")`, `<img>` `object-cover rounded-full`, `alt=""` (the name is beside it). Fallback (no photo, or `loading` / `missing` / `error`): `bg-secondary text-secondary-foreground`, the user's initials, 13/600.
  - Name: 14/600. Title: 12/400 `text-muted-foreground`, the Hebrew translation of `title` (section 6). Long names: truncate on one line (`truncate` with a max width) and put the full name in `title`.
  - Logout: the existing `Button`, `variant="outline"`, `size="sm"` (36px), a 16px lucide icon + "יציאה", `margin-inline-start: 8px`. `onClick` → `useLogout().logout()`; `disabled` while `pending`. The icon in the mockup is a "log out" door with the arrow pointing **left** (toward the end side in RTL). Use lucide's `LogOut` and make it point the right way under RTL (e.g. Tailwind 4's `rtl:` variant + `-scale-x-100`, or a mirrored icon). Verify the `rtl:` variant against the installed Tailwind 4.3.3 and that it actually applies (the document has `dir="rtl"` on `<html>`), and say which you used.

### 5. Not-found inside the shell

- An unknown `/app/...` path renders inside the shell: the top bar title "הדף לא נמצא", and in the content area the description and the "back to the home page" link (both exist in `he.json`). No full-page layout, no `<h1>` of its own. No nav item is active.
- Keep the full-page variant for the top-level `*`. Split the component however is cleanest (e.g. a `variant` prop, or two small components sharing the strings). Say which.

### 6. Copy (Hebrew strings — use these exactly; keys are yours, all in `he.json`)

| Where | Text |
| --- | --- |
| Nav `aria-label` | ניווט ראשי |
| Nav: dashboard (also its page title) | דשבורד |
| Nav: squad (also its page title) | סגל |
| Coming-soon group label | בקרוב |
| Coming-soon items | לו״ז / אימונים / משחקים / לוח טקטי |
| Logout button | יציאה |
| Dashboard stub line | סיכום הסגל יופיע כאן בקרוב. |
| Squad stub line | טבלת הסגל תופיע כאן בקרוב. |
| `CLUB_MANAGER` | מנהל מועדון |
| `HEAD_COACH` | מאמן ראשי |
| `ASSISTANT_COACH` | עוזר מאמן |
| `GOALKEEPING_COACH` | מאמן שוערים |
| `FITNESS_COACH` | מאמן כושר |
| `ANALYST` | אנליסט |

- "לו״ז" uses the Hebrew gershayim `״` (U+05F4), as in `docs/design/ui-conventions.md`, not an ASCII `"`. Check the bytes.
- **Titles in the masculine form are Gal's decision** (the backend has no gender; masculine is the usual default in Hebrew UIs). Make the title → key mapping exhaustive over the `Title` type (a `Record<Title, ...>` or a `satisfies` check), so a seventh title is a compile error, not a blank line.
- The logout button already exists in `he.json` as `placeholder.logout`. Move it to a sensible key; don't keep two copies.

### 7. Initials

One small pure function (e.g. `initials(name: string): string` in `src/lib/`), used by both the club and the user fallback:
- Take the first **letter** (Unicode letter, `\p{L}` with the `u` flag) of each of the first two words; one word → its first letter only. Words are split on whitespace; characters that aren't letters are skipped when looking for a word's first letter (so `"מ.ס. דוגמה"` gives two letters, not a dot).
- Nothing usable (empty, whitespace, only digits / punctuation / emoji) → return `""`, and the fallback then shows a 16–18px lucide icon instead (`Shield` for the club, `User` for the person; verify both names exist in the installed `lucide-react`). The fallback square / circle is `aria-hidden` either way.
- Don't upper-case Hebrew (no-op) but do upper-case Latin (`"dana rosen"` → `"DR"`).
- Unit-test it: two Hebrew words, three words, one word, Latin, punctuation inside (`"מ.ס. דוגמה"`), leading/trailing spaces, empty, digits only, an emoji-only name, a mixed Hebrew + Latin name.

### 8. Look (approved mockup — build exactly this)

**Layout:**
- Full-height page, `bg-background`. A row: sidebar first in the DOM (so it lands on the start side, right in RTL), content column second.
- **Sidebar:** `bg-sidebar text-sidebar-foreground`, width 240px (fixed, `shrink-0`), padding 20px 14px, column with a 24px gap between club block, nav and logo. On desktop / tablet it stays in view when the content scrolls (`sticky top-0 h-screen` with its own `overflow-y-auto`, or equivalent; say what you used).
- **Content column:** takes the rest (`flex-1 min-w-0`, so a wide table in KAN-49 can scroll inside it instead of pushing the page).
- **Top bar:** `bg-card`, a 1px `border-border` bottom border, padding 16px 32px, items centered, `justify-between`, gap 16px, wraps on narrow widths.
- **`<main>`:** padding 28px 32px, `max-width: 1200px`, full width below that. (The pages' own content and gaps are KAN-49 / KAN-51's.)
- **Phone width:** desktop / tablet first (KAN-44). Below Tailwind's `md` breakpoint (verify its value in the installed Tailwind 4) the sidebar stacks **above** the content at full width, not sticky. Nothing may overflow horizontally at 375px. It doesn't have to be polished.

**Club block:** row, gap 12px, padding 4px 8px. Logo square 40×40 `rounded-lg`.

**Nav items** (live): row, gap 10px, padding 10px 12px, `rounded-md` (6px), 14px text, 18px lucide icon.
- Inactive: `text-sidebar-foreground`, 500; hover: a subtle light background on the dark sidebar (a `sidebar-foreground` opacity modifier, e.g. `/10`; tokens only).
- Active: the cream pill, `bg-sidebar-accent text-sidebar-accent-foreground`, 600.
- A visible focus ring that works on the dark background (check it in the browser; the default `--ring` is dark green and may vanish on `--sidebar`). Keyboard Tab must show where focus is.
- Items in the nav column have a 4px gap.

**"בקרוב" group:** 16px above it. The label: 12px, `text-sidebar-muted-foreground`, padding 0 12px. Items: the same row shape as a nav item (gap 10px, padding 10px 12px, 18px icon), `text-sidebar-muted-foreground`, `opacity-75`, no hover, default cursor.

**Icons** (lucide, stroke, 18px in the nav; verify every name exists in the installed `lucide-react` before using it):
- דשבורד: `LayoutDashboard`. סגל: `Users`.
- לו״ז: `Clock`. אימונים: `CalendarDays` (or `Calendar`). משחקים and לוח טקטי: pick the closest stroke icons available (the mockup shows a ball for matches and a pitch outline for the tactical board; lucide may have neither). List what you picked.
- All icons in the shell are decorative (`aria-hidden`); the text carries the meaning.

**User block:** row, gap 12px; name / title column with `line-height` ~1.3.

## 9. Out of scope (don't build)

- The dashboard's content (KAN-51) and the squad table (KAN-49): only the two stubs.
- Changing the club logo or the user's photo, a profile menu, a user dropdown, settings, staff admin.
- A collapsible sidebar, a hamburger menu, mobile polish.
- Hiding nav items by permission level (every level can see the dashboard and the squad).
- Dark mode. Any backend change. A size variant for `BrandLogo` (see section 3).

## Tests (Vitest + React Testing Library + MSW, through `renderWithProviders`)

Use the existing builders in `src/test/msw/auth.ts` (`loggedIn()`, `meReturns()`, `currentUserBody()`, ...); add image answer builders there if useful (`handlers.ts` stays empty). Stub `URL.createObjectURL` / `revokeObjectURL` the way `useAuthorizedImage.test.tsx` does. Use deferred MSW handlers for "still loading" states, never timers.

At minimum:
- **Shell renders** at `/app` after the bootstrap: the nav landmark by its accessible name, the club name, the user's name and translated title, the logout button, and the `<h1>` "דשבורד". The shell is **not** rendered on `/app/login` (logged out) or while the bootstrap / `/me` is loading.
- **Fallbacks (the ticket's "Done when"):**
  - `club.hasLogo: false` → club initials shown, **no** request to `/clubs/me/logo` (no handler registered; MSW would fail the test).
  - `hasPhoto: false` → user initials shown, **no** request to `/users/me/photo`.
  - `hasLogo: true` / `hasPhoto: true` with a `200` image → an `<img>` with the object URL. While the image is in flight (deferred) → the initials.
  - Image `404` → initials. Image `500` → initials, nothing broken, no error text.
  - A name with no letters → the icon fallback.
- **Logout (the ticket's "Done when"):** click "יציאה" → exactly one `POST /auth/logout` → `/app/login` **without** `next`; the button is disabled while the logout is pending (deferred handler); the Query cache is empty afterwards. The temporary placeholder button is gone (no second "יציאה" anywhere).
- **Navigation:** on `/app` the dashboard link has `aria-current="page"` and the squad link doesn't; clicking "סגל" goes to `/app/squad`, the `<h1>` becomes "סגל", `aria-current` moves. The squad link is also current on a deeper path (`/app/squad/x` → not-found inside the shell is fine for this check, or add a test-only route; say which).
- **Coming-soon items:** each of the four texts is present, none of them is a link or a button (`queryByRole("link", { name })` / `"button"` → null), none is focusable.
- **Page title:** `document.title` on `/app` and `/app/squad`, and back to `"SquadPulse"` after logout (on the login screen).
- **Not-found inside the shell:** `/app/nope` → the shell with `<h1>` "הדף לא נמצא", the back link to `/app`, no nav item current. A path outside `/app` → the full-page not-found, no shell.
- **Titles:** every `Title` value renders its Hebrew text (iterate over all six).
- **`initials`:** the unit tests in section 7.
- **RTL:** the sidebar comes before the content in the DOM; `<html>` / the wrapper `dir` isn't overridden (keep the existing check); a grep-style test isn't needed, but run the grep yourself (below).
- Update `router.test.tsx` / `sessionRouting.test.tsx` for the removed placeholder. Keep every behavior they checked.

## Manual verification (in Chrome — Safari can't refresh on http://localhost until KAN-56)

You can't drive a browser, so write **exact, numbered steps for Gal** in your summary (he isn't a frontend developer; give the terminal commands and what to click / expect):
1. Start: `docker compose up -d`; backend with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` and `./mvnw spring-boot:run`; `npm run dev` on the branch; open `http://localhost:5173/app` and log in.
2. Compare the shell with the approved mockup (Gal has it): sidebar on the right, club block, the two live items with the cream pill on the active one, the disabled "בקרוב" group, the logo at the bottom, the top bar with title / avatar / name / title / "יציאה".
3. Click "סגל" and back to "דשבורד": the pill moves, the title changes, the browser tab title changes.
4. Fallbacks with the real backend: with no club logo and no staff photo uploaded (the default for a bootstrapped club), initials show. Then upload a club logo and a staff photo from the DevTools console (give the exact `fetch` calls with `FormData` to `PUT /clubs/me/logo` and `PUT /users/me/photo`, and how Gal gets the access token, as in KAN-47's steps), reload, and the images show. Then `DELETE` them and reload: initials again.
5. Keyboard: Tab from the top of the page; focus is visible on each live nav item and on "יציאה", and skips the "בקרוב" items.
6. `http://localhost:5173/app/nope` → not-found inside the shell.
7. Logout → login screen, no `next` in the URL, tab title back to "SquadPulse".
8. Phone width (DevTools device toolbar, e.g. 375px): the sidebar is above the content, nothing scrolls sideways.
9. A long club name: rename the club with `PATCH /clubs/me` (exact `fetch` call) to a ~90-character name, reload, check it clamps to two lines; rename it back.

## Constraints

- English everywhere in code, comments, commits (CLAUDE.md rule 2). Every user-visible string in `he.json` (rule 6); list all new Hebrew strings in your summary.
- Run `npm run lint`, `npm run format:check`, `npm test`, `npm run build` in `frontend/`. All green, zero warnings. Don't touch CI or the backend.
- Grep every new / changed component for physical-direction classes (`ml-`, `mr-`, `pl-`, `pr-`, `left-`, `right-`, `text-left`, `text-right`, `border-l`, `border-r`, `rounded-l`, `rounded-r`, ...) and paste the (empty) result.
- **No new dependencies.** If you think one is needed (e.g. a shadcn component that pulls something in), stop and ask first, with the reason and the exact version. You probably don't need any shadcn component here; if you add one, use the pinned CLI, Prettier-format it, check it under RTL, per CLAUDE.md.
- Tokens only (no hex, no raw palette colors). No new theme tokens; if you think one is needed (e.g. for the focus ring on the sidebar), say why and stop to ask.
- Verify every library behavior you rely on against the **installed** versions, not memory: React Router 8.4 (`NavLink` `end` and `aria-current`, `handle`, `useMatches`, layout routes), Tailwind 4.3.3 (`rtl:` variant, `md` breakpoint, `line-clamp-*`), lucide-react (every icon name). Say in the summary what you checked and where.
- Small, focused commits in Conventional Commits style with `(KAN-48)`, e.g. `feat(frontend): add the app shell with sidebar and top bar (KAN-48)`. Stage files by path, never `git add -A`.
- Update `CLAUDE.md` in the same change (rule 7): in the frontend paragraph, the shell (`AppShell` as the protected layout, page titles through the route `handle`, the protected routes `/app` and `/app/squad`, not-found inside the shell, `initials`, images only when the `has*` flag is true, the "בקרוב" items are never links), as dense as the existing text; replace "KAN-48's shell becomes its layout" with what's true now. Update the "Status" line's frontend part (the shell exists; still no product screens with data). Update `docs/design/ui-conventions.md`'s implementation notes with a short "App shell (KAN-48)" bullet (where it lives, how a page sets its title, how a new nav item is added). That file isn't the spec; you may edit it. Remove the "(the app shell (KAN-48) ...)" comments in `router.tsx` / `RequireAuth.tsx` that are no longer true.
- **Don't push and don't open a PR.** Stop after committing locally.

## Spec

**Don't edit `docs/spec.md`.** In your summary, list every place in the spec this ticket makes outdated or that should now mention the shell (e.g. the status line, section 02's frontend line, section 13's Phase 3 row, anything describing navigation / the sidebar or the Hebrew titles). Give line numbers and what's wrong or missing. I'll prepare the spec update.

## Summary to report back

- Branch, commits (hash + message), files added / changed / deleted.
- The route table (paste it) and where the shell sits (and why there).
- How the page title works (paste the `handle` type and the hook), and `document.title` handling.
- `initials` (paste it) and its test cases.
- The icons you picked (names), and how `LogOut` is mirrored.
- How the "בקרוב" group is marked up and what a screen reader announces for it.
- The sidebar's sticky / scroll behavior and the phone-width behavior.
- Test count before → after (frontend was 197), and the new tests by name. Where each check from the removed placeholder tests moved.
- All new / moved Hebrew strings (key → text).
- The physical-class grep result.
- The manual-verification steps for Gal (section above), complete, with the exact `fetch` calls.
- Every version-specific fact you relied on, and how you verified it.
- Spec places to update. Open ends, risks, anything you weren't sure about (including whether `BrandLogo` at the bottom looks right at its current size).
