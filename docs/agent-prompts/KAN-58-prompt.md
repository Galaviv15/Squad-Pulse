# KAN-58: Frontend tests: make console output visible and fix the root redirect route warnings

**Credit budget is tight.** Read only the files this prompt names, plus whatever you need to answer a "verify" item. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `cce27e0`, the KAN-53 merge). If it doesn't, stop and report. Then create the branch `chore/KAN-58-test-console-and-root-redirect`.

## Context

Jira KAN-58 (Low, tech-debt, epic KAN-43 "Frontend MVP"), found while working on KAN-49:

1. **Test console output is invisible.** The KAN-49 agent reported that `npm test` in `frontend/` prints no console output at all, not even a deliberate `console.warn` (also with `--silent=false`). Nothing in `vite.config.ts` (`test` block: `environment: "jsdom"`, `exclude`, `setupFiles: ["./src/test/setup.ts"]`) or `src/test/setup.ts` sets `silent` or `onConsoleLog`, so the cause is unknown. React, React Router and `act()` warnings go unnoticed. A quiet run proves nothing.
2. **The `/` → `/app` route logs two React Router warnings** (pre-existing on master): "No `HydrateFallback` element provided to render during initial hydration" and "Matched leaf route at location "/" does not have an element or Component". They come from `{ path: "/", loader: () => redirect("/app") }` in `src/app/router.tsx` (a loader, no element, no fallback). A page load at `/` in dev logs them too.

**Out of scope: any product behavior change.** `/` must still land on `/app`.

Facts I checked on master (re-check the ones you rely on):

- `package.json`: `vitest ^5.0.0` (lock: **5.0.1**), `jsdom ^29.1.1`, `react-router ^8.4.0`, `msw ^2.15.0`, `@testing-library/react ^16`, React 19. Script: `"test": "vitest run --passWithNoTests"`. Read the exact installed versions from `node_modules/*/package.json`.
- `src/test/setup.ts`: jest-dom import; `beforeAll` → `server.listen({ onUnhandledRequest: "error" })`; `beforeEach` / `afterEach` reset the auth session and bootstrap; `afterEach` also `cleanup()` and `server.resetHandlers()`; `afterAll` → `server.close()`. Vitest globals are **off**.
- **Many test files call `vi.restoreAllMocks()` in `afterEach`** (or return it from `beforeEach`), e.g. `AppShell.test.tsx`, `SquadPage.test.tsx`, `SquadCards.test.tsx`, `EditPlayerPage.test.tsx`, `PlayerCardActions.test.tsx`, `PlayerCardPhoto.test.tsx`, `routeError.test.tsx`, `useAuthorizedImage.test.tsx`.
- `src/app/routeError.test.tsx` deliberately silences `console.error` with `vi.spyOn(console, "error").mockImplementation(() => {})` and asserts the route error was logged (`toHaveBeenCalledWith(expect.any(String), chunkError)`).
- `src/test/msw/server.test.ts`: two tests ("fails a request that no handler covers", "forgets a test's handlers after it") deliberately hit an unhandled request; under `onUnhandledRequest: "error"` MSW rejects the fetch **and** (verify) prints a `console.error`.
- `AppShell.test.tsx` says an unexpected image request "fails silently" (MSW rejects it, the image hook falls back). MSW's console output in such cases is exactly what the new policy will surface.
- `src/app/router.test.tsx` "redirects / to /app" only asserts the dashboard heading and `router.state.location.pathname === "/app"`.
- `src/test/render.tsx` (`renderWithProviders`) uses `createMemoryRouter(routes, { initialEntries })`; `main.tsx` uses `createBrowserRouter(routes)` via `createAppRouter()`. `/app` already has `hydrateFallbackElement: <LoadingScreen />`.
- Frontend Vitest count at KAN-53: 776 (master may have moved; report before and after).

Read first: `CLAUDE.md` (rules + frontend paragraph), `frontend/package.json`, `frontend/vite.config.ts`, `src/test/setup.ts`, `src/test/render.tsx`, `src/test/msw/server.ts` + `server.test.ts`, `src/app/router.tsx` + `router.test.tsx`, `src/app/routeError.test.tsx`. Other test files only as far as the warnings you surface require.

## Verify against what's installed, not memory

For every framework behavior below, check the **installed** version (`node_modules/<pkg>/package.json`, its types, its source/dist, its changelog in `node_modules` if present) and say in the summary how you checked:

- Vitest 5.0.1: default `silent` value; how `onConsoleLog` / `printConsoleTrace` / reporters handle console output in `vitest run`; whether the default reporter prints console for passing tests; the default `sequence.hooks` order (are `afterEach` hooks from a test file run before or after the ones registered in a setup file?); what `vi.restoreAllMocks()` restores in 5.x (all `vi.spyOn` spies, including ones created in a setup file?) and whether it clears recorded calls.
- jsdom 29 under Vitest: whether the jsdom environment's `console` is Node's console or jsdom's virtual console, and where the latter's output goes.
- MSW 2.x with `onUnhandledRequest: "error"`: does it print to `console.error` in addition to rejecting? Exact message prefix.
- React Router 8.4: the conditions under which each of the two warnings is emitted (find the warning strings in `node_modules/react-router` dist), and whether a `<Navigate replace />` element route (decision 3) avoids both, in both `createMemoryRouter` and `createBrowserRouter`.

## Decisions (agreed with Gal, implement these)

### 1. Find the root cause of the invisible output, then fix it there

- **Diagnose first, with evidence.** Reproduce: a throwaway test with `console.warn("KAN-58 probe")` and `console.error(...)`, run with `npm test` and with `npx vitest run <file>`. Then bisect: setup file on/off, environment `node` vs `jsdom` for the probe file, MSW listen on/off, reporter choice. Report what you ran and what you saw at each step, and the actual cause.
- Fix the cause in config / setup, not with a workaround that happens to print. If the cause is a Vitest/jsdom default, set the explicit option and comment why, with the version.
- Do not commit the probe test; the permanent proof is the guard's own tests (section 2).

### 2. Policy: unexpected `console.error` / `console.warn` fails the test

- A global guard in the test setup: any `console.error` or `console.warn` during a test that the test didn't explicitly expect **fails that test**, with a message that includes the logged text (and, if cheap, where it came from). `console.log` / `console.info` / `console.debug` don't fail anything, they just stay visible.
- **The guard must not be built on `vi.spyOn`** (or anything `vi.restoreAllMocks()` / `vi.resetAllMocks()` undoes), because many test files call `vi.restoreAllMocks()` in their own `afterEach`, which may run before the setup file's `afterEach` (verify the hook order). Wrap the console methods yourself, record into the guard's own state, check and reset in an `afterEach` that is guaranteed to run, and keep the original method so output still reaches the terminal.
- **Explicit opt-out = the test mocks the method itself.** A test that does `vi.spyOn(console, "error").mockImplementation(...)` (as `routeError.test.tsx` does) replaces the guard's wrapper for its duration, so the guard sees nothing and the test asserts what it expects. Confirm this works with your design and that the guard is back in place for the next test after `restoreAllMocks`. If you add a small helper instead (e.g. `expectConsole("error", /pattern/)`), keep it minimal, one way to do it, documented.
- The two deliberate MSW "unhandled request" tests in `server.test.ts` opt out explicitly and **assert** the MSW error was logged, so they keep proving what they prove.
- Warnings logged outside a test (module load, `beforeAll`, after the test ends from a stray promise) must not vanish silently either: fail the file or the run, or at least print them clearly. Say what you chose and why.
- **Tests for the guard itself** (e.g. `src/test/consoleGuard.test.ts`): an unexpected `console.warn` and `console.error` fail (test this without leaving a red test in the suite, e.g. by calling the guard's check function directly, or with `it.fails` if the installed Vitest supports it; verify); a mocked method opts out; the guard still works in the test after one that called `vi.restoreAllMocks()`; `console.log` doesn't fail; the original output still reaches the console.

### 3. Root redirect: `<Navigate replace />` instead of the loader

- Replace `{ path: "/", loader: () => redirect("/app") }` with an element route that renders `<Navigate to="/app" replace />` (from `react-router`), provided your verification in RR 8.4 shows it logs neither warning. If it doesn't, stop and report what does, before choosing something else.
- Keep using the shared constant for `/app` if one fits (`APP_HOME` in `src/lib/auth/paths.ts`); don't add a new one.
- Remove the `redirect` import if it becomes unused.
- `router.test.tsx` "redirects / to /app": keep it, and add `expect(router.state.historyAction).toBe("REPLACE")` (Back must not return to `/`). With the guard in place, the test also proves no warning is logged; say so.
- **Dev check:** open `/` in the dev server (`npm run dev`, HTTPS since KAN-56) with the browser console open: lands on `/app`, no React Router warnings. Report what you saw. If you can't run a browser, say so explicitly instead of claiming it.

### 4. Existing warnings the guard surfaces

- Run the full suite with the guard on and list **every** distinct warning/error it surfaces (file, test, message, cause).
- Fix in this ticket everything that is fixable on the **test side**: a missing MSW handler, a missing `await` / `findBy`, an `act()` warning from an unawaited update, a leaked timer, etc. Each fix keeps the test's meaning; never weaken an assertion to get rid of a warning.
- **If a warning can only be fixed by changing product code, stop on that one and report it** (file, message, proposed change). Don't change product code beyond decision 3, and don't add it to an allow-list to make the run pass. We'll open a separate ticket.
- **No broad allow-list**, no pattern that ignores a whole category (e.g. all `act(` warnings). The only opt-outs are per-test, explicit, and asserted (section 2).
- If the number of surfaced warnings is large (say, more than ~25 distinct causes), stop after the diagnosis and the guard, commit, and report the full list before fixing, so we can decide on scope.

## Tests and builds

- `npm run lint`, `npm run format:check`, `npm test`, `npm run build`, all as in CI. Report test counts before and after.
- Show the final `npm test` output tail, and show that a deliberate `console.warn` in a test now fails it with a readable message (paste the output of a temporary run; don't commit the failing test).
- Run the full suite **3 times** to check the guard doesn't introduce flakes (a warning that only sometimes appears is a race; find its cause).
- `npm run e2e` is not required for this ticket. Don't touch `e2e/`, Playwright config, or CI.

## Docs

- **CLAUDE.md:** a short addition to the frontend testing part of the frontend paragraph: test console output is visible; an unexpected `console.error` / `console.warn` fails the test; how a test that expects one opts out and asserts it (the one documented way); that the guard is not `vi.spyOn`-based so `vi.restoreAllMocks()` doesn't remove it. Put it in the commit that introduces the guard (rule 7).
- **`docs/spec.md`: do NOT edit it.** Grep it for `Vitest`, `console`, `redirect`, `/app`, `loader` and list in the summary every place that is now outdated (section + line + what's wrong), or state that nothing is.

## Commits

Small, focused commits, for example:
- `fix(frontend): make test console output visible (KAN-58)` (the root-cause fix)
- `test(frontend): fail tests on unexpected console errors and warnings (KAN-58)` (guard + its tests + CLAUDE.md)
- `test(frontend): fix warnings surfaced by the console guard (KAN-58)` (one or several, grouped by cause)
- `fix(frontend): redirect / to /app without a loader (KAN-58)`

Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. Installed versions verified (Vitest, jsdom, MSW, React Router) and the evidence for each "verify" item above (hook order, `restoreAllMocks` scope, MSW console output, the two RR warning conditions).
3. **Root cause** of the invisible output: the bisection steps, what each showed, the cause, and the fix.
4. The guard: design (how it wraps console, where it checks, why `restoreAllMocks` can't remove it), the opt-out mechanism, what happens to output outside a test, and its own tests.
5. Every warning surfaced by the guard: file, test, message, cause, and fix (or "stopped: needs product change", with the proposed change).
6. Redirect: what changed, the RR 8.4 evidence, the test change, and what you saw on a dev page load at `/` (or that you couldn't check it).
7. Test counts before/after, the 3 full runs, lint/format/build results, and the output proving a deliberate `console.warn` fails a test.
8. Spec places that are now outdated (or none).
9. Deviations from this prompt, and why.
10. Open ends and risks.
11. Print in full: the new `src/test/setup.ts`, the guard module and its tests, the `vite.config.ts` `test` block, the new `/` route, and the changed `router.test.tsx` test.
