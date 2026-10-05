# KAN-46: Frontend API client and session — in-memory token, single-flight refresh, error parsing, image blobs

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `79753bc` (the KAN-55 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-46-api-client`.

## Context

This is Jira KAN-46, in epic **KAN-43 "Frontend MVP"** (Phase 3). KAN-35 (filter-level errors in the `ApiErrorResponse` shape) and KAN-45 (frontend infra) are done and merged. This ticket blocks **KAN-47** (auth screens: login, activation / reset, session bootstrap, protected routes, logout). Every later screen (KAN-48 shell, KAN-49 squad table, KAN-50 player card, KAN-51 dashboard) calls the backend **only** through what you build here.

**No screens and no routes in this ticket.** You build the request layer, the session, the error type and the image hook, all with tests. The login form, the redirect to the login page, session bootstrap on app load and the logout button all belong to KAN-47.

Epic decisions that constrain this ticket (from KAN-43, already agreed with Gal):
- **Same-origin, no CORS.** The Vite dev proxy forwards `/auth`, `/squad`, `/clubs`, `/users`. The backend has no `/api` prefix and no CORS. Don't add either, and **don't change the backend at all** in this ticket.
- **Access token in memory only** (Zustand), never in `localStorage`, `sessionStorage`, IndexedDB or a cookie that JS can see.
- **Refresh through the HttpOnly cookie.** One shared refresh call handles concurrent 401s.
- **Desktop-readiness:** token storage and refresh transport sit behind **one small interface**. Only the browser implementation is built.
- **The `Secure` cookie on `http://localhost` must be verified in practice, in Chrome and in Safari.** Don't assume.

Read these first:
- `CLAUDE.md`: the frontend paragraph (KAN-45) and the access-control / errors paragraph (401 vs 403, `ApiErrorResponse`, `details` format, `ActiveCallerCheck`).
- `docs/spec.md` section 10 (refresh tokens, families, rotation, reuse detection), plus the `/me` paragraph in section 04. **Read only. Don't edit the spec** (see "Spec" below).
- `docs/design/ui-conventions.md`, only in case you need anything user-visible (you shouldn't).
- Frontend: `src/lib/config.ts` (`apiUrl`), `src/lib/queryClient.ts` (`shouldRetryQuery`), `src/test/render.tsx` (`renderWithProviders`), `src/test/setup.ts`, `src/test/msw/*`, `src/main.tsx` (note `<StrictMode>`), `eslint.config.js`, `package.json`.
- Backend, to confirm the contracts below: `auth/AuthController.java`, `auth/AccessTokenResponse.java`, `auth/RefreshTokenService.java` (class Javadoc + `ROTATE_SCRIPT`), `auth/AuthService.refresh`, `auth/SecurityConfig.java` (`PUBLIC_ENDPOINTS`, `writeError`), `common/ApiErrorResponse.java`, `common/GlobalExceptionHandler.java` (`detail(...)`, the `details` entries without a field name).

## Facts I checked on master `79753bc` (re-confirm each one, against the code and the installed versions — don't take them on faith)

**Backend contracts:**
- `POST /auth/login` and `POST /auth/refresh` return `200` with body `AccessTokenResponse { accessToken, tokenType, expiresIn }` (`tokenType` = `"Bearer"`, `expiresIn` in seconds) and a `Set-Cookie` with the **new** refresh token. The refresh token is never in the body.
- `POST /auth/logout` returns `204`, always, and clears the cookie. It only needs the cookie, not an access token.
- The refresh cookie: `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/auth`, no `Domain`, 30-day Max-Age.
- `SecurityConfig.PUBLIC_ENDPOINTS` = `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/forgot-password`, `/auth/reset-password` (all POST). Everything else needs a token.
- A missing, invalid or expired token gets `401` with the `ApiErrorResponse` body (`SecurityConfig.writeError`). A permission failure gets **`403`**, which is not a session problem and must **never** trigger a refresh.
- `ActiveCallerCheck` 401s (from `GET /auth/users/me`, the user-management writes, `PATCH /clubs/me`) have the same generic body as a missing token. The client can't tell them apart, and must not try to.
- **Refresh-token reuse detection** (`RefreshTokenService`): each refresh rotates the family's token. Presenting a token that was already rotated away is treated as theft, and **the whole family is revoked**. So two refreshes sent at the same time with the same cookie means **the second one logs the user out for good**, along with the first. This drives the single-flight and cross-tab rules below.
- `ApiErrorResponse { timestamp, status, error, message, details: string[] }`. Entries in `details` are usually `"<name>: <message>"`, where name can be a path such as `players[2].position`. **Some entries have no name** (global / class-level errors, see `GlobalExceptionHandler` around line 188), and broken JSON gives an empty `details`.
- **Non-JSON error bodies still happen.** Tomcat's HTML page for connector-level rejections (KAN-35 accepted limit). The Vite proxy's own error when the backend is down. A 502/504 from a future reverse proxy. Also `204` / empty bodies on success (logout, photo PUT/DELETE).

**Frontend:**
- `apiUrl(path)` in `src/lib/config.ts` is the only place API URLs are built. Use it for every request.
- `shouldRetryQuery` (`src/lib/queryClient.ts`) duck-types a **numeric `status`** on the error. Your error type **must** keep that contract: every HTTP error carries a numeric `status`. A network failure (no response) must carry **no** numeric status, so it keeps being retried. Update the comment in `queryClient.ts` that says "KAN-46 defines the error type" to point at your type, and keep its tests green.
- `main.tsx` renders under `<StrictMode>`, which runs effects twice in dev. Anything that can start a refresh must be safe under that (see single-flight below).
- MSW 2.x, `onUnhandledRequest: "error"`. Don't loosen it. `src/test/msw/handlers.ts` is empty and says KAN-46 adds the shared handlers. Add only what's genuinely shared.
- **jsdom has no `navigator.locks`** (verify against the installed jsdom). The cross-tab lock needs a fallback and a test seam (see below).

## Decisions (agreed with Gal — implement these)

### 1. Session interface (desktop-readiness)

One small TypeScript interface, e.g. `AuthSession` (the name is yours), that is the **only** thing the request layer, hooks and future screens know about. Roughly:
- read the current access token (or none);
- a status that UI can subscribe to (e.g. `"unknown" | "authenticated" | "unauthenticated"`) — KAN-47 will route on it;
- `login(email, password)` → stores the token; failures surface as the normal API error;
- `refresh()` → the single-flight, cross-tab-locked refresh (below); resolves to the new token or rejects;
- `logout()` → calls `POST /auth/logout`, and clears the session **whatever** that call returns, including network failures;
- `clear()` / end-of-session → wipes the token and sets the status to `unauthenticated`.

The **browser implementation** keeps the token in a Zustand store (no `persist` middleware, nothing written to any storage) and refreshes through the cookie. Screens and hooks never touch the store or `document.cookie` directly; they go through the interface (and a React hook over it, e.g. `useSession()`). Keep it small. No desktop code, no second implementation, no plugin system.

`fetch` calls to `/auth/*` use the default `credentials: "same-origin"` (state it explicitly). **Don't** use `"include"`: we're same-origin, and `"include"` would hide a future cross-origin mistake.

### 2. Request layer

One function (e.g. `apiFetch` / `apiRequest`, plus thin JSON helpers if you like) that every call goes through:
- builds the URL with `apiUrl`;
- attaches `Authorization: Bearer <token>` when the session has a token;
- parses JSON success bodies, and accepts `204` / empty bodies;
- turns every non-2xx into the API error type (section 4), and every network failure into a network error with no numeric status;
- supports `AbortSignal` for normal requests (TanStack Query passes one).

**Refresh-and-retry rule:**
- A request triggers a refresh **only if it was sent with an access token and got `401`**. That one rule covers every public endpoint: login with a wrong password is a 401 with no token sent, so it surfaces as an error and never refreshes. Don't maintain a separate list of excluded paths. Add a test that a 401 from `/auth/login` doesn't refresh.
- `403`, `404`, `409`, `5xx` never trigger a refresh.
- After a successful refresh, the original request is sent **once more**, with the new token. **Build the request again from its original inputs** (method, URL, headers, body). Never re-send a consumed `Request` object. Bodies we send are JSON strings or `FormData`, which can be re-sent. If you accept any other body type, document why it's safe.
- If the retry gets `401` again, **end the session** (clear → `unauthenticated`) and reject with that 401. No second refresh, no loop.
- If the refresh itself fails (any error, including network), **end the session** and reject the original request(s) with a 401-style API error, so callers and `shouldRetryQuery` treat it as a 4xx.
  - Exception: if the refresh failed because of a **network error** (backend unreachable), think about whether ending the session is right. Losing the session on a dev-server blip is bad UX, but keeping a dead session is worse. **Report the choice you made and why**; don't silently pick one.
- Retrying writes is safe here because a 401 is decided before the handler changes anything (the security filter, or `ActiveCallerCheck` as the first step). Confirm that against `CLAUDE.md` / the controllers, and say so in a code comment.

### 3. Single-flight refresh, in-tab **and** across tabs

- **In-tab:** one module-level in-flight promise. Every caller that needs a refresh while one is running awaits the same promise. It must live at **module level** (or in the session object's closure), never in React state or a component, so StrictMode's double effects, or two hooks mounting together, can't start two refreshes.
- **Across tabs:** serialize refreshes with the **Web Locks API** (`navigator.locks.request("<a fixed lock name>", ...)`) around the `POST /auth/refresh` call. Reason: two tabs refreshing at the same moment send the same cookie, and the backend's reuse detection then revokes the family (see Facts). Inside the lock the refresh just runs: by the time a waiting tab gets the lock, the browser already holds the rotated cookie, so its refresh is a normal rotation, not a reuse. Don't add BroadcastChannel token sharing or anything else on top. Keep it to the lock.
  - Verify the API shape and support against what's actually available: TypeScript's DOM lib in the installed `typescript`, and Chrome / Safari support. Don't rely on memory.
  - If `navigator.locks` is missing (jsdom, very old browsers), fall back to in-tab single-flight only. Keep the fallback tiny, and test both paths (inject a fake `locks` in one test).
- **Never abort an in-flight refresh** (no `AbortSignal` on it, and a caller's abort must not cancel the shared refresh). An aborted refresh can mean the server rotated the token but the browser never stored the new cookie, and the next refresh then counts as reuse.
  - Known residual risk (**accept, document in a code comment, don't try to fix**): if the page is closed or reloaded while a refresh is in flight, the same thing can still happen, and that session ends. The real fix would be a server-side grace window (a separate future decision, not this ticket).

### 4. API error type

A class (e.g. `ApiError extends Error`) with:
- `status: number` (always numeric — the `shouldRetryQuery` contract);
- `error`, `message`, `details` from the body when it parsed as `ApiErrorResponse`;
- `fieldErrors`: a map from field name to message(s), from the `details` entries that have a name. Split on the **first** `": "`. A name may contain dots and brackets (`players[2].position`). Keep it as-is, don't normalize;
- `generalErrors` (or similar): the `details` entries with no name.

A non-JSON or malformed body (HTML, empty, JSON that isn't the expected shape) still gives an `ApiError` with the response's status and a fixed fallback message. Never put the raw body in the message.

A **separate** network error type (or a plain `Error` subclass) for "no response", with **no** numeric `status`.

User-facing texts aren't part of this ticket. The error type carries data; screens translate it later through i18n. Don't add Hebrew strings unless something truly needs one, and if it does, put it in `he.json` (CLAUDE.md rule 6).

### 5. Image hook

A hook (e.g. `useAuthorizedImage(path | null)`) that:
- fetches the image endpoint through the request layer (so it gets the token **and** the refresh-and-retry behavior);
- turns the blob into an object URL;
- treats `404` as "no image" (a distinct state, not an error);
- **revokes** the object URL on unmount and whenever the path changes;
- **ignores stale responses**: if the path changes (or the component unmounts) while a fetch is in flight, abort it, and make sure a late response never sets state or leaks an object URL;
- does nothing for `null` (callers pass `null` when `hasPhoto` / `hasLogo` is false; the hook never fetches on its own to "check").

Use a plain `useEffect` + `AbortController`, not TanStack Query: object URLs must be revoked by whoever created them, and the query cache would keep them alive past unmount. If you see a real reason to do otherwise, stop and ask first.

### 6. Out of scope (don't build)

- Any screen, route, redirect or `navigate()` call. Ending the session only clears it and sets the status. KAN-47 routes on the status.
- Clearing the TanStack Query cache on session end (KAN-47 wires that to the status change). Expose what KAN-47 needs, e.g. a way to subscribe to status changes, but don't wire it into `main.tsx`.
- Session bootstrap on app load (KAN-47).
- Proactive refresh from `expiresIn`. Refresh is reactive only (on 401). You may keep `expiresIn` in the store if it's free, but don't schedule anything.
- Any backend change. Any desktop implementation.

## Tests (Vitest + MSW, through the existing setup)

At minimum:
- The token is attached when present, and not attached when absent. Every URL goes through `apiUrl` (one test with a stubbed `VITE_API_BASE_URL`, the way `config.test.ts` does it).
- **Single-flight:** 3+ concurrent requests all get 401 → exactly **one** `POST /auth/refresh` (count it in the MSW handler) → each original request is retried once with the new token and succeeds.
- Retry happens once only: refresh OK, retry gets 401 → session cleared, rejected with 401, refresh called exactly once.
- Refresh fails (401 from `/auth/refresh`) → session cleared (status `unauthenticated`, no token), all waiting requests rejected.
- **The "can no longer act" case** (the ticket asks for it explicitly): `GET /auth/users/me` and `PATCH /clubs/me` return 401 with the generic body → refresh attempted → refresh fails → session ended.
- 401 from `/auth/login` (no token sent) → no refresh, error surfaces. 403 with a token → no refresh.
- A write (`POST` with a JSON body, and one with `FormData`) is retried with the same body after a refresh. Assert the body the handler received on the second attempt.
- **Cross-tab lock:** with a fake `navigator.locks`, the refresh runs inside `request(<lock name>, ...)`. Without `navigator.locks`, the in-tab single-flight still holds.
- An aborted caller doesn't cancel a shared in-flight refresh that other callers are waiting on.
- Error parsing: a full `ApiErrorResponse` with named + unnamed `details` (including a `players[2].position: ...` entry) → `fieldErrors` / `generalErrors` right. HTML 500 body, empty 502 body, malformed JSON → `ApiError` with status and fallback message. Network failure → no numeric status. `shouldRetryQuery` gives the expected answer for each (a direct test against the real error types).
- **Nothing is written to `localStorage` / `sessionStorage`** after login and refresh (spy on them or check them empty).
- `logout()` clears the session even when `/auth/logout` fails or the network is down.
- Image hook: object URL created and shown; **revoked on unmount**; **revoked on path change** (old URL revoked, new one created); `404` → "no image" state; a stale response after a path change is ignored and its URL is never leaked (stub `URL.createObjectURL` / `revokeObjectURL` and count calls; jsdom may not implement them, so check). Render through `renderWithProviders` (or a minimal wrapper if the hook doesn't need the router — say which).

Use fake timers only if you need them. Make concurrency tests deterministic (control when the MSW refresh handler resolves, e.g. with a deferred promise), not timing-based.

## Manual verification: the Secure cookie on http://localhost (Chrome and Safari)

You can't run Safari, and there is no login screen yet. So **write exact steps for Gal** in your summary, and do the Chrome part yourself if you can drive a browser. If you can't, say so and leave it to Gal too. The steps:
1. Backend + Mongo + Redis running locally (README local-setup), `npm run dev` in `frontend/`, open `http://localhost:5173/app`.
2. In DevTools console: `await fetch("/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "...", password: "..." }) }).then(r => r.status)` → expect `200`. Say which local user / bootstrap to use, from the README.
3. Check the cookie is stored: DevTools → Application/Storage → Cookies → `http://localhost:5173`, a `refresh_token` cookie (name from `AuthController.REFRESH_COOKIE`) with Path `/auth`, Secure, HttpOnly, SameSite Strict.
4. Reload the page, then: `await fetch("/auth/refresh", { method: "POST" }).then(r => r.status)` → expect `200` (and a rotated cookie value).
5. Repeat in Safari (DevTools: Develop menu → Show Web Inspector).

Report per browser: stored yes/no, refresh after reload 200 yes/no. **If Safari rejects the cookie, report it and stop there. Don't weaken the cookie** (no dropping `Secure`, no `SameSite=Lax`) and don't add an HTTPS dev setup on your own. That's a decision for Gal.

## Constraints

- English everywhere in code, comments, commits (CLAUDE.md rule 2).
- Run `npm run lint`, `npm run format:check`, `npm test`, `npm run build` in `frontend/`. All green, with zero warnings. Don't touch CI.
- No new dependencies unless truly needed. If you think one is, stop and ask first, with the reason and the exact version you checked in `package.json` / the lockfile.
- Verify every library behavior you rely on against the **installed** versions (MSW 2.x request/response API, Zustand 5 store API, TypeScript's DOM lib for `LockManager`, jsdom's `URL.createObjectURL`). Not against memory.
- Small, focused commits in Conventional Commits style with `(KAN-46)`, e.g. `feat(frontend): add the API error type (KAN-46)`. Stage files by path, never `git add -A`.
- Update `CLAUDE.md`'s frontend paragraph in the same change (rule 7): the request layer is the only way to call the backend, the session interface, the refresh rule (token sent + 401 → one refresh → one retry), the cross-tab lock and why (reuse detection), never abort a refresh, the error type's `status` contract, and the image hook. Keep it as dense as the existing paragraph.
- **Don't push and don't open a PR.** Stop after committing locally.

## Spec

**Don't edit `docs/spec.md`.** In your summary, list every place in the spec that this ticket makes outdated or that should now describe the frontend session (e.g. section 10's refresh description, now that there's a client with a cross-tab lock; the frontend lines in sections 01/13). Give the line numbers and what's wrong or missing. I'll prepare the spec update.

## Summary to report back

- Branch, commits (hash + message), and the files added / changed.
- The session interface (paste it), and where the browser implementation lives.
- How the refresh-and-retry rule is implemented, and the network-error-during-refresh choice (section 2) with the reason.
- Web Locks: what you verified (TS lib, browser support) and the fallback.
- Test count before → after, and the list of new tests by name.
- The manual cookie check: per browser, what you ran and the result, or the exact steps you're leaving to Gal.
- Every version-specific fact you relied on, and how you verified it.
- Spec places to update (above). Open ends, risks, anything you weren't sure about.
