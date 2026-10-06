# KAN-47: Frontend auth screens — login, activation / password reset, session bootstrap, protected routes, logout

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `6e88d9d` (the KAN-46 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-47-auth-screens`.

## Context

This is Jira KAN-47, in epic **KAN-43 "Frontend MVP"** (Phase 3). KAN-45 (frontend infra) and KAN-46 (API client and session) are done and merged. This ticket blocks **KAN-48** (app shell: sidebar, top bar with the user's photo / name and the real logout button). After this ticket, every `/app` screen sits behind login.

This is the first ticket with real screens and routing on the session. KAN-46 deliberately left all navigation, bootstrap and Query-cache wiring to this ticket.

Read these first:
- `CLAUDE.md`: the whole frontend paragraph (KAN-45 + KAN-46). It is long; every rule in it applies here (routes under `/app`, `renderWithProviders`, MSW `onUnhandledRequest: "error"`, backend only through `src/lib/api`, tokens only, logical properties, i18n for every string).
- `docs/design/ui-conventions.md`: tokens, type scale, density (controls 40px), card rules, and the implementation notes on the base components. The auth screens and a new two-color logo were **approved by Gal in this ticket** (in a design canvas you can't open); everything you need from them is written out in section 6, "Look", below.
- `docs/spec.md` sections 09 (onboarding: invited user activates with a code) and 10 (refresh, families, the client's refresh rules), and the `/me` paragraph in section 04. **Read only. Don't edit the spec** (see "Spec" below).
- Frontend: `src/lib/api/session.ts` (all of it: `AuthSession`, `createBrowserSession`, the generation counter, `authSession`, `useSessionStatus`), `src/lib/api/client.ts` (`apiFetch` / `apiJson`, `PUBLIC_ENDPOINTS`), `src/lib/api/errors.ts`, `src/lib/queryClient.ts`, `src/app/router.tsx` + `router.test.tsx`, `src/main.tsx` (note `<StrictMode>`), `src/test/render.tsx`, `src/test/setup.ts`, `src/test/msw/*` (esp. `auth.ts`), `src/pages/*`, `src/components/ui/*`, `src/i18n/locales/he.json`.
- Backend, to confirm the contracts below: `auth/AuthController.java`, `auth/PasswordResetController.java`, `auth/LoginRequest.java`, `auth/ForgotPasswordRequest.java`, `auth/ResetPasswordRequest.java`, `auth/InvalidCredentialsException.java`, `auth/InvalidResetCodeException.java`, `auth/LoginThrottledException.java`, `auth/PasswordResetService.java` (the two email bodies), `auth/CurrentUserResponse.java` + `ClubResponse.java`, `auth/User.normalizeEmail`, `common/GlobalExceptionHandler.java`, and README "Auth API".

## Facts I checked on master `6e88d9d` (re-confirm each one against the code — don't take them on faith)

**Backend contracts:**
- `POST /auth/login` `{ email, password }` → `200` `AccessTokenResponse` + refresh cookie. Any failure → `401`, message `"Invalid email or password"` (one message for every cause). 5 failures in 15 min for the same email + IP → `429`, message `"Too many failed login attempts, try again later"`, `Retry-After` header. Blank email / password → `400` (`@NotBlank`; email `@Size(max = 254)`).
- `POST /auth/forgot-password` `{ email }` → **always `202`, empty body**, whether or not the email exists / is active / is over its limit. Never `429`. `400` only for a blank or > 254-char email.
- `POST /auth/reset-password` `{ email, code, newPassword }` → `204`. **Doesn't log in.** Ends all of the user's refresh sessions. `401` `"Invalid or expired code"` for every failure cause (wrong / expired / used / burned code, unknown or deactivated user). `400` if `code` isn't exactly 6 ASCII digits or `newPassword` isn't 8–128 chars (UTF-16 units — the same as JS `string.length`); a `400` doesn't use up the code or a guess. Field names in `details`: `email`, `code`, `newPassword`.
- The same endpoint is the **activation** of an invited user (no password yet). The activation code is valid 15 minutes (`squadpulse.password-reset.code-ttl`); if it expires, the user asks for a new one through forgot-password (an invited user is `active: true`, so forgot-password does send them a code).
- **The emails contain only the code, in English, and no link to the app** (`PasswordResetService`, sent by the logging stub `LoggingEmailSender` in dev — the code shows up in the backend log). So the user must be able to find the "enter code" screen from the login screen by themselves. Don't change the backend or the emails in this ticket.
- The backend normalizes emails with `trim().toLowerCase(Locale.ROOT)`. The client may trim; it doesn't need to lowercase.
- `GET /auth/users/me` → `200` `{ id, email, fullName, title, permissionLevel, dateOfBirth, active, hasPhoto, activated, club: { id, name, hasLogo } }`. `permissionLevel` is the **effective** level (from the token). A deactivated / deleted user → generic `401`.
- `POST /auth/logout` → always `204`, clears the cookie, revokes only this session's family.

**Frontend (from KAN-46):**
- `authSession.getStatus()` is `"unknown"` until something decides. `refresh()` → `authenticated` on success; a **4xx** → `clear()` → `unauthenticated`; a **5xx, a network failure, or a malformed 200** rejects but **leaves the status as it was** (so on app load it stays `"unknown"`). This is deliberate and must not change.
- `login()` doesn't change the status on failure. `logout()` waits for a running refresh, POSTs, then `clear()`s whatever happened.
- `subscribe` fires on status changes only. `useSessionStatus()` is the React hook over it.
- `apiFetch` never sends the token to `PUBLIC_ENDPOINTS`, so a `401` from login / reset never triggers a refresh. Forgot / reset go through `apiFetch` / `apiJson` like everything else; login / refresh / logout go through `authSession`.
- `authSession` is a module-level singleton. Its state (and anything else module-level you add) **leaks between tests** unless reset. Handle that (see Tests).
- `main.tsx` renders under `<StrictMode>`, which mounts → unmounts → mounts effects in dev.

## Decisions (agreed with Gal — implement these)

### 1. Routes

All under `/app` (CLAUDE.md rule):
- Public (no session needed): `/app/login`, `/app/forgot-password`, `/app/reset-password`. The exact names are yours if you have a reason, but keep them under `/app` and short.
- Protected: everything else under `/app` (today the placeholder at `/app`). Put the guard on a **parent route**, so KAN-48's shell can become that parent's layout later without touching each screen.
- `/` still redirects to `/app`; unknown paths still show the not-found page. Decide whether `/app/<unknown>` is checked by the guard first or shows not-found directly, and say which.
- An **authenticated** user who opens a public auth route is sent to `/app` (or to a valid `next`, see 3). Exception to think through: `/app/reset-password` while logged in. Reasonable either way (redirect, or allow it); pick one and say why.

### 2. Session bootstrap on app load — no flash, and no infinite spinner

- When the status is `"unknown"`, call `authSession.refresh()` **once**. Do it through a **module-level memoized promise** (or equivalent), never by calling `refresh()` directly from a component effect, so StrictMode's double effects and multiple mounted guards can't start two refreshes. (The session's own single-flight would already merge two concurrent calls, but don't rely on timing — a second call after the first has settled would send a second refresh.)
- On success the status is `authenticated` → load the current user (section 4) → render the protected route.
- On a 4xx the status becomes `unauthenticated` → redirect to login (with `next`, section 3). A first visit with no cookie is a plain `401` from `/auth/refresh`; that's the normal path, not an error to show.
- **On a 5xx / network failure / malformed 200 the status stays `"unknown"`.** Don't leave the app on a loading spinner forever and don't show the login form (the cookie may be fine). Show a full-page "can't reach the server" state with a **retry** button that runs the bootstrap again (reset the memo first). Same screen if `/me` fails with a 5xx / network error after a successful refresh (retry = refetch `/me`).
- While the status is `"unknown"` and the bootstrap is in flight, or `/me` is loading: a neutral full-page loading state (i18n label, accessible: `role="status"` or `aria-busy`). **Never** render protected content, and never render the login form, before the status is settled — that's the "no flash" requirement. Test it.
- Public auth routes don't wait for the bootstrap to render the form? Think about it: if someone with a valid cookie opens `/app/login`, they should land in the app, not see the form flash. Simplest correct behavior: every route waits for the bootstrap to settle (status not `"unknown"`, or the server-error state). Do that unless you find a concrete reason not to, and say what you chose.

### 3. Redirect back after login (`next`) — and no open redirect

- When the guard sends an unauthenticated user to login, it carries the requested location (path + search + hash) in a **query parameter** (e.g. `/app/login?next=%2Fapp%2Fsquad%3Fposition%3DGK`), so it survives a reload of the login page. Not router state.
- **Validate `next` before using it.** Accept only a same-origin path that is `/app` or starts with `/app/`, and isn't one of the public auth routes. Reject (fall back to `/app`): absolute URLs, protocol-relative `//evil.com`, backslashes (`/\evil.com`), `javascript:`, encoded tricks (`%2F%2Fevil.com` after decoding), anything not under `/app`. Implement it as one small pure function (e.g. `safeNextPath(raw): string`), parsed with `new URL(raw, window.location.origin)` and an origin check plus the prefix check, and unit-test it with every case above.
- After a successful login (and after the auto-login in section 6), navigate to the validated `next` with `replace`, so Back doesn't return to the login form.
- **Explicit logout** goes to `/app/login` **without** `next`. A session that ended by itself (expired refresh, a 401 that ended it, logout in another tab) goes to login **with** `next` = the current location. Make sure the guard's redirect doesn't add `next` on an explicit logout (e.g. the logout action navigates to `/app/login` itself with `replace` before or right after the status change — get the ordering right and test it).

### 4. The current user

- After the status becomes `authenticated` (bootstrap or login), load `GET /auth/users/me` through `apiJson`, with **TanStack Query** (one query key, e.g. `["auth", "me"]`), and expose a typed hook, e.g. `useCurrentUser()`, returning the `/me` shape (write the TS type from `CurrentUserResponse` + `ClubResponse`, field by field, and re-check it against the Java records).
- The protected parent route renders its children only once `/me` has data. Screens below it can then assume the user is there (the hook may throw / assert if called outside — your choice, say which).
- Don't set `staleTime: Infinity` blindly: think about whether `/me` should refetch on window focus. The effective `permissionLevel` only changes on refresh, so refetching on focus mostly costs requests. Pick something reasonable and say what.
- A `401` from `/me` (deactivated user with a still-valid token) is already handled by `apiFetch`: refresh → refresh refused → `clear()` → `unauthenticated` → guard → login. Add a test for that path through the UI.

### 5. Clearing client state when the session ends

- Wire the session to the app's `QueryClient`: when the status becomes `unauthenticated`, call `queryClient.clear()` (via `authSession.subscribe`). Put this in one small, testable function (e.g. `bindSessionToQueryClient(session, queryClient)` returning the unsubscribe), called from `main.tsx`, not inside a component.
- Also clear when a **different** login happens? The cache is already cleared on the way to `unauthenticated`, so a login always starts from an empty cache. Confirm that holds for every path to the login form (including the cross-tab logout below) and say so.

### 6. Screens

**Look (approved by Gal — build exactly this):**

*Layout, shared by every auth screen and the loading / server-error states* — one `AuthLayout` component:
- Full-height page on `--background`, content centered horizontally and vertically, column with a 24px gap, padding 48px 16px (works at phone width).
- Top: the **logo** (below). Middle: the card. Bottom: a muted footer line, 12px, `--muted-foreground`: "מערכת לניהול הצוות המקצועי של המועדון".
- Card: the existing `Card`, `max-width: 420px`, full width below that, padding 32px 28px, inner column gap 20px. Card header: title 22/700 (`<h1>`), subtitle 14/400 `--muted-foreground`, gap 6px.
- Form: column, gap 16px. Each field: `Label` (13/500) above the `Input` (default size, 40px), gap 6px; a hint below in 12px `--muted-foreground`, or the field error below in 13px `text-danger` (the error replaces the hint), and the input border becomes `--danger` when invalid. Submit = the default (primary) `Button`, full width, 600 weight.
- Form-level error alert: `role="alert"`, `bg-danger-muted text-danger`, 13/500, padding 12px 14px, radius 6px (`rounded-md`), an 18px lucide `CircleAlert` icon at the start, text beside it (and an optional link under the text, in `text-danger`, 600).
- Neutral / success notice: same shape, `bg-secondary text-secondary-foreground` (`#E6EDE8` / `#23463A`), `role="status"`, lucide `CircleCheck` (success) or `Mail` (code sent) icon.
- Links: `--primary`, 13/500 unless stated.

*The logo — a new, reusable `BrandLogo` component* (KAN-48's shell will use it too; put it in `src/components/`, not `ui/`):
- A 40×40 mark, `rounded-lg` (8px), background `#1E3A2F` (the `--sidebar` token), with a 22px stroke "pulse" icon centered in it: SVG `viewBox="0 0 24 24"`, `fill="none"`, `stroke="currentColor"`, `stroke-width="2"`, round caps / joins, path `M3 12h4l3-7 4 14 3-7h4`. Icon color: `--brand-pulse-bright` (below). Decorative: `aria-hidden`.
- Beside it (gap 10px) the wordmark "SquadPulse", 22/700, `letter-spacing: -0.01em`, in an LTR island (`dir="ltr"`), in **two colors**: "Squad" in `--sidebar` (`#1E3A2F`), "Pulse" in `--brand-pulse` (`#5E8A2E`). The whole logo has an accessible name "SquadPulse" once (e.g. the wordmark text itself; don't let screen readers read "Squad" and "Pulse" as two words if you split it into spans — check what RTL shows and what the accessible name is).
- On a dark background (KAN-48's sidebar; give the component a `tone="light" | "dark"` prop now, default `light`): the mark becomes `#F2EEE3` (`--sidebar-accent`) with the icon in `--brand-pulse`, "Squad" in `--sidebar-accent` (`#F2EEE3`), "Pulse" in `--brand-pulse-bright` (`#8FB83F`).
- **Two new theme tokens** in `src/index.css`, mapped in `@theme inline` like the others: `--brand-pulse: #5E8A2E` (for use on light backgrounds) and `--brand-pulse-bright: #8FB83F` (on dark backgrounds; the same value as `--chart-3`, but a separate token on purpose — brand and chart colors must be able to change independently). Contrast (checked): `#5E8A2E` on `#F6F4EE` is 3.7:1 and `#8FB83F` on `#1E3A2F` is 5.4:1, so `--brand-pulse` is for the **large wordmark only**, never body text — write that in a comment and in the doc.
- Add a short "Logo" bullet under "Decisions" in `docs/design/ui-conventions.md` (approved in KAN-47: the colors, the two tokens, the contrast rule) and the two tokens to its token table. That file isn't the spec, you may edit it.

*Copy — use these Hebrew strings exactly* (plural / gender-neutral wording on purpose; keys are yours, all in `he.json`):

| Where | Text |
| --- | --- |
| Footer (all screens) | מערכת לניהול הצוות המקצועי של המועדון |
| Login title / subtitle | כניסה / התחברו עם האימייל והסיסמה שלכם. |
| Labels | אימייל / סיסמה |
| Forgot link (at the end of the password label's row, same line as the label) | שכחתי סיסמה |
| Submit | כניסה |
| Activation box (below the form, separated by a 1px `--border` top line, padding-top 16px; muted line then a 600-weight link) | קיבלתם הזמנה או קוד במייל? / הזנת קוד והגדרת סיסמה |
| 401 | האימייל או הסיסמה שגויים. |
| 429 | יותר מדי ניסיונות כניסה. נסו שוב בעוד כמה דקות. |
| 5xx / network (all forms) | אין חיבור לשרת כרגע. נסו שוב בעוד רגע. |
| After reset + failed auto-login (success notice on login) | הסיסמה נשמרה. אפשר להתחבר עם הסיסמה החדשה. |
| Forgot title / subtitle | שכחתי סיסמה / נשלח קוד בן 6 ספרות לאימייל שלכם. הקוד תקף ל־15 דקות. |
| Forgot submit / back link | שליחת קוד / חזרה לכניסה |
| Reset title / subtitle | הגדרת סיסמה / להפעלת חשבון חדש או לאיפוס סיסמה: הזינו את הקוד שקיבלתם במייל ובחרו סיסמה. |
| Notice after forgot (with `Mail` icon) | אם הכתובת רשומה במערכת, נשלח אליה קוד. כדאי לבדוק גם בתיקיית הספאם. |
| Reset labels | אימייל / קוד אימות / סיסמה חדשה / אימות סיסמה |
| Reset hints | code: 6 ספרות, מהמייל שקיבלתם — new password: 8 תווים לפחות |
| Reset submit | שמירה וכניסה |
| Reset links (one row, space-between, wraps) | לא קיבלתם קוד? שליחה מחדש / חזרה לכניסה |
| Reset 401 (alert + link to forgot) | הקוד שגוי או שפג תוקפו. / שליחת קוד חדש |
| Confirm mismatch | הסיסמאות אינן תואמות. |
| Loading state | טוען… |
| Server-error title / body / button | אין חיבור לשרת / לא הצלחנו להתחבר לשרת של SquadPulse. ייתכן שיש תקלה זמנית. / נסו שוב |

You'll also need field-level messages that the table doesn't list (required email / password / code, code not 6 digits, password too short / too long, and the Hebrew mapping of the backend's `400` field errors). Write them in the same plural, neutral style and list them in your summary for Gal.

*Field details:* email and code inputs `dir="ltr"`; the email placeholder `name@club.co.il` (forgot screen only; LTR); the code input `inputMode="numeric"`, `autoComplete="one-time-code"`, `maxLength={6}`, placeholder `000000`, 16px with `letter-spacing: 0.4em` and `tabular-nums`. No show-password toggle.

*Loading state:* no card — the logo, then centered (min-height ~200px) a 32px ring spinner (track `--border`, arc `--primary`; respect `prefers-reduced-motion`: no spin, just the ring) and "טוען…" in `--muted-foreground`; `role="status"`.

*Server-error state:* the card, starting with a 44px round `bg-danger-muted text-danger` circle holding a 22px lucide `WifiOff` icon, then the title / body, then a full-width primary button with a 16px lucide `RotateCw` icon before "נסו שוב".

Verify each lucide icon name exists in the installed `lucide-react` before using it.

**Common form rules:**
- Proper `<form>` with `onSubmit`, `<label htmlFor>` on every field, correct `type` and `autoComplete` (`email`, `current-password`, `new-password`, `one-time-code`). Email and code inputs get `dir="ltr"` (LTR islands), the code input `inputMode="numeric"`.
- **Disable submit while a request is pending** (and ignore a second submit). This matters: a double login sends two logins; a double reset burns the code on the second call (`401`).
- Client-side validation mirrors the backend so the common cases never reach it: email required; password required; code exactly 6 digits; new password 8–128 (`string.length`); confirm password equals new password (client only). Still handle the backend's `400` `fieldErrors` (show the message for the matching field in Hebrew — map by field name to an i18n key; never show the backend's English text to the user).
- Field errors are linked with `aria-describedby` and `aria-invalid`; the form-level error is in a `role="alert"` region.
- 5xx / `NetworkError` on any form → a form-level "couldn't reach the server, try again" message. The form stays filled.

**Login (`/app/login`):**
- Email + password. `401` → "wrong email or password" (one message, never which one). `429` → a **generic** "too many attempts, try again in a few minutes" message (Gal's decision: **don't** read `Retry-After`, don't touch `ApiError`).
- Links: "Forgot password?" → forgot screen; **"I have a code / activate my account"** → reset screen. The second link is required: the activation email has no link to the app.
- Carries `next` through (the forgot / reset links keep it too, so an invited user who activates lands on the page they first asked for).

**Forgot password (`/app/forgot-password`):**
- Email only. On `202` → navigate to the reset screen with the email pre-filled and a neutral notice: "if this email is registered, a 6-digit code was sent; it's valid for 15 minutes". Never say whether the email exists. Pass the email via router state (don't put the email in the URL).
- `400` → field error.

**Set password with a code (`/app/reset-password`) — reset and activation:**
- Email (pre-filled when coming from forgot, editable), code, new password, confirm password. A short line explains it's also how an invited user activates their account.
- On `204` → **auto-login** (Gal's decision): `authSession.login(email, newPassword)` → status `authenticated` → bootstrap path loads `/me` → navigate to the validated `next` (or `/app`) with `replace`. If that login fails for any reason (e.g. `429`, network), **don't** show an error on the reset form (the password *was* set): navigate to `/app/login` with the email pre-filled and a success notice "password set, please log in".
- `401` → "the code is wrong or has expired", with a link to request a new code (the forgot screen, email pre-filled).
- `400` → field errors.
- Note on the reset ending all refresh sessions: if the same browser had another tab logged in as this user, that tab's next refresh will fail → it goes to login. That's correct and expected; just be aware of it.

**Logout:**
- A `useLogout()` hook (or similar) that calls `authSession.logout()`, broadcasts it (section 7), and navigates to `/app/login` (no `next`). The real button belongs to KAN-48's top bar; **for now put a temporary logout button on `PlaceholderPage`**, so the end-to-end check is possible. Mark it with a comment pointing at KAN-48.
- Disable the button while logout runs.

### 7. Cross-tab logout (Gal's decision: yes)

- On an **explicit logout** in one tab, every other tab of the app ends its session immediately (instead of staying logged in on its in-memory access token for up to 15 minutes).
- Use `BroadcastChannel` with one fixed channel name (a constant next to `REFRESH_LOCK_NAME`). The tab that logs out posts a message after its own `logout()` has run; the other tabs call `authSession.clear()` — **never** `logout()` (no second POST: the family is already revoked and the cookie cleared by the first tab, and the cookie is shared).
- Decide where it lives: inside `createBrowserSession` (so `logout()` broadcasts and the session listens — the session already owns cross-tab concerns, the Web Lock) or in a small module next to it. Keep the `AuthSession` interface unchanged if you can; if you must change it, say why. Whatever you choose, the receiving tab's `clear()` goes through the same path as any session end (status `unauthenticated` → Query cache cleared → guard → login with `next`).
- If `BroadcastChannel` doesn't exist (verify what jsdom 29.1.1 and the installed Node provide in the Vitest environment; don't assume), skip it silently. Test both paths with an injected fake channel.
- Close the channel when appropriate (it's a long-lived singleton in the browser; just make sure tests don't leak open channels / handles).
- Out of scope: cross-tab **login** (another tab sitting on the login form doesn't log itself in), and broadcasting a session that ended by itself. Don't build those.

### 8. Out of scope (don't build)

- The app shell, sidebar, top bar, user photo, club logo (KAN-48).
- Any backend change, including the email texts / a link in the email.
- Proactive refresh from `expiresIn`.
- Showing the minutes from `Retry-After`.
- Hiding actions by permission level (later screens use `useCurrentUser().permissionLevel`).

## Tests (Vitest + React Testing Library + MSW, through `renderWithProviders`)

**Test isolation first:** `authSession` and your bootstrap memo / broadcast channel are module-level. Add a reset seam used in `src/test/setup.ts` (`afterEach`), or create the session per test through a provider — your call, but every test must start with status `"unknown"`, no token, no memoized bootstrap, no open channel. Say what you did. Update the existing `router.test.tsx`: `/app` now requires a session, so its tests need MSW handlers (a refresh that succeeds + `/me`); keep what they check (placeholder, LTR badge, not-found paths, `dir`).

Put shared answer builders (a `/me` body factory, a refresh that succeeds / fails) in `src/test/msw/auth.ts`; `handlers.ts` stays empty (an unexpected request must still fail the test).

At minimum:
- **Bootstrap:** refresh OK + `/me` OK → protected page rendered, and **never** the login form or protected content before that (assert the loading state first; use a deferred MSW handler, not timers). Refresh `401` → login form at `/app/login?next=...`. Refresh `502` (empty body) → server-error screen, **no** login form; retry → refresh OK → page. Refresh network error → same. `/me` `500` → server-error screen; retry works.
- **Exactly one** `POST /auth/refresh` on app load, also when the app is rendered inside `<StrictMode>` (count it in the handler; render with StrictMode in that test).
- `/me` `401` → refresh attempted → refresh `401` → login.
- **Login:** success → navigates to `next` with `replace` (assert history can't go back to the form, or assert the router's `replace`); success without `next` → `/app`. `401` → the Hebrew wrong-credentials message, form still filled. `429` → the generic message. `502` / network → the server message. Client validation (empty fields → no request sent). Double submit → exactly one `POST /auth/login`.
- **`safeNextPath`:** unit tests for every case in section 3 (valid `/app`, `/app/x?y#z`; rejected `https://evil.com`, `//evil.com`, `/\evil.com`, `javascript:alert(1)`, `%2F%2Fevil.com`, `/auth/login`, `/app/login`, empty, missing).
- **Already logged in** at `/app/login` → redirected into the app.
- **Forgot:** `202` → reset screen with the email pre-filled and the notice; the request body is exactly `{ email }`. `400` → field error. Network → server message.
- **Reset:** success → `POST /auth/reset-password` with exactly `{ email, code, newPassword }` (no confirm field sent) → auto-login → `/me` → lands on `next` / `/app`. Success but the auto-login gets `429` → login screen, email pre-filled, success notice. `401` → wrong/expired code message + link to forgot. `400` with `details` `["code: must match \"\\d{6}\""]` (copy the real message format from a backend test) → error on the code field, in Hebrew. Client validation: 5-digit code, 7-char password, mismatched confirm → no request. Double submit → one request.
- **Logout:** temporary button → `POST /auth/logout` → `/app/login` **without** `next`; the Query cache is empty afterwards (seed a query before, assert it's gone). Logout with `/auth/logout` failing (network) → still logged out locally.
- **Session ends by itself** while on a protected page (e.g. call `authSession.clear()` in the test) → login with `next` = the current location; cache cleared.
- **Cross-tab:** with an injected fake channel, a logout posts one message; receiving that message calls `clear()` (status `unauthenticated`) and sends **no** `POST /auth/logout`. Without `BroadcastChannel` → logout still works, nothing throws.
- **No storage:** nothing written to `localStorage` / `sessionStorage` through the whole login → reload-style bootstrap → logout flow.
- A11y basics: every input has an accessible name (query by label), errors are announced (`role="alert"`), the code / email inputs have `dir="ltr"`.

Make concurrency / ordering tests deterministic with deferred MSW handlers. No timing-based waits.

## Manual verification (in Chrome — Safari can't refresh on http://localhost until KAN-56)

You can't drive a browser, so write **exact, numbered steps for Gal** in your summary (he isn't a frontend developer; give the terminal commands and what to click / expect). Cover:
1. Start: `docker compose up -d`; backend with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` and `./mvnw spring-boot:run`; `npm run dev` on the branch; open `http://localhost:5173/app`.
2. First visit → login screen (one 401 from `/auth/refresh` in the Network tab is expected). Wrong password → Hebrew error. Correct login → placeholder page.
3. Reload → still logged in, no login-form flash.
4. Open `http://localhost:5173/app/something?x=1` while logged out → login → after login lands on that URL (it'll be not-found or the guard's choice — say what to expect). Try `http://localhost:5173/app/login?next=//example.com` → after login lands on `/app`, not on example.com.
5. Two tabs logged in → logout in one → the other goes to login immediately (no reload needed).
6. Activation: invite a user from the DevTools console as the admin (give the exact `fetch` call to `POST /auth/users/invite` with the access token — say how Gal gets the token, e.g. from the `/auth/refresh` response in the Network tab), take the 6-digit code from the backend log (`LoggingEmailSender`), log out, use "I have a code" → set a password → lands logged in as the new user.
7. Forgot password for an existing user → code in the log → reset → logged in.
8. Stop the backend while on the login screen and submit → server message; reload with the backend stopped → server-error screen with retry; start the backend, retry → works.
9. Visual check: Gal compares every screen and state (login, its error, forgot, reset, reset errors, loading, server error) with the approved mockups he has, in Chrome at desktop width and at a phone width (DevTools device toolbar). List the URLs / how to reach each state.

## Constraints

- English everywhere in code, comments, commits (CLAUDE.md rule 2). Every user-visible string in `he.json` (rule 6); list all the new Hebrew strings in your summary so Gal can review the copy.
- Run `npm run lint`, `npm run format:check`, `npm test`, `npm run build` in `frontend/`. All green, zero warnings. Don't touch CI or the backend.
- **No new dependencies** (no form library, no schema library). If you think one is truly needed, stop and ask first, with the reason and the exact version.
- Verify every library behavior you rely on against the **installed** versions, not memory: React Router 8.4 (`redirect`, loaders vs. element guards, `Navigate`, `useNavigate({ replace })`, `useSearchParams`, `createMemoryRouter` in tests), TanStack Query 5 (`clear()`, `enabled`, query state names), React 19 StrictMode behavior, jsdom 29.1.1 / Node 22 for `BroadcastChannel`, MSW 2.15. Say in the summary what you checked and where.
- If you add a shadcn component (e.g. `alert`), use the pinned CLI (`npx shadcn add`), Prettier-format it, check it under RTL (grep for physical classes) and adjust it to the tokens, per CLAUDE.md.
- Small, focused commits in Conventional Commits style with `(KAN-47)`, e.g. `feat(frontend): add the login screen (KAN-47)`. Stage files by path, never `git add -A`.
- Update `CLAUDE.md`'s frontend paragraph in the same change (rule 7): the public auth routes and the guard (parent route), the bootstrap (once, memoized; `unknown` after a 5xx → server-error screen, never login), `next` and `safeNextPath`, `useCurrentUser` and its query key, `bindSessionToQueryClient`, cross-tab logout (channel name, `clear()` never `logout()`), the test reset seam. Keep it as dense as the existing paragraph. Also update the "Status" line's frontend part (auth screens exist).
- **Don't push and don't open a PR.** Stop after committing locally.

## Spec

**Don't edit `docs/spec.md`.** In your summary, list every place in the spec this ticket makes outdated or that should now describe the auth screens (e.g. the status line, section 02's frontend line, section 09's activation flow from the user's side, section 10's client paragraph — cross-tab logout, bootstrap on load, server-error state — and the section 13 Phase 3 row). Give line numbers and what's wrong or missing. I'll prepare the spec update.

## Summary to report back

- Branch, commits (hash + message), files added / changed.
- The route table (paste it), where the guard lives, and the choices left to you in sections 1, 2, 4 and 7 (with the reason for each).
- How the bootstrap is made StrictMode-safe, and the test that proves exactly one refresh.
- `safeNextPath` (paste it) and its test cases.
- The cross-tab logout design, what jsdom / Node provide for `BroadcastChannel`, and whether `AuthSession` changed.
- The test reset seam.
- Test count before → after (frontend was 87), and the list of new tests by name.
- All new Hebrew strings (key → text). Mark which ones came from the table in section 6 and which you added.
- `BrandLogo`: its props, the two new tokens, the logo's accessible name as a screen reader gets it, and the `ui-conventions.md` change.
- The manual-verification steps for Gal (section above), complete.
- Every version-specific fact you relied on, and how you verified it.
- Spec places to update. Open ends, risks, anything you weren't sure about.
