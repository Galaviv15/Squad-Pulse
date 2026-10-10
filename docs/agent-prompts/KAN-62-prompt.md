# KAN-62: Frontend tests: flaky under CPU load (RouteErrorPage effect log, 1 s findBy timeouts)

**Credit budget is tight.** Read only the files this prompt names, plus whatever you need to answer a "verify" item or to fix a file the stress runs point at. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `1a2cf26`, the KAN-61 merge, PR #41). If it doesn't, stop and report. Then create the branch `fix/KAN-62-flaky-tests-under-load`.

## Context

Jira KAN-62 (Medium, labels `bug`, `tech-debt`, epic KAN-43 "Frontend MVP"), found by the KAN-60 agent during stress runs on master `3952989` (before any KAN-60 change). The frontend Vitest suite is green when run alone but fails under CPU contention. CI runners can be slow too, so this can hit CI. A test that fails at random teaches everyone to ignore failures, which is exactly what KAN-58 (console guard) and KAN-60 (unmocked-request guard) were built to prevent.

The evidence in the ticket (KAN-60 agent, on `3952989`):

| Mode | Runs | Failed runs |
| --- | --- | --- |
| `routeError.test.tsx` alone | 100 | 0 |
| full suite, 2 in parallel | 20 | 4 (1× routeError, 3× DashboardPage) |
| full suite, shuffled, 4 in parallel | 20 | 20 (~7 tests per run: 1 s `findBy` timeouts, plus 6× "Test timed out in 5000ms") |

Shuffle alone is green, so order isn't the cause; load is. **Master has moved since** (KAN-60 merged as `c513b89`, KAN-61 as `1a2cf26`), so re-measure; don't rely on this table.

This is a test-only change. **No product code change** is expected; if you find a fix that needs one, stop and report it before changing product code.

Facts I checked on master `1a2cf26` (re-check what you rely on):

- `package-lock.json`: `vitest` **5.0.1**, `@testing-library/dom` **10.4.2**, `@testing-library/react` **16.3.3**, `react` **19.3.0**, `react-router` **8.4.0**, `vite` **8.3.0**.
- `vite.config.ts` `test` block: jsdom, `setupFiles: ["./src/test/setup.ts"]`, reporters (KAN-58). **No** `testTimeout`, `hookTimeout`, `retry` or pool settings; no Testing Library `configure(...)` anywhere in `src/`. Everything is on defaults (`findBy`/`waitFor` 1000 ms, test 5000 ms).
- `src/test/setup.ts` (KAN-58 + KAN-60): the console guard and the unmocked-request guard. Its `afterEach` runs `cleanup()` first, then waits one macrotask (`realSetTimeout(…, 0)`), then the guards' checks. It runs **after** the test file's own `afterEach` (Vitest's default `sequence.hooks: "stack"`).
- The route table is `src/app/router.tsx`. The dashboard (index route of `/app`) is **not** lazy. Four squad pages are lazy (function form of `lazy`): `squad` → `SquadPage`, `squad/new` → `NewPlayerPage`, `squad/:playerId` → `PlayerCardPage`, `squad/:playerId/edit` → `EditPlayerPage`. A pathless route with `errorElement: <RouteErrorPage />` wraps all of them.

### Cause 1: `RouteErrorPage`'s effect log

- The component is at **`src/pages/RouteErrorPage.tsx`** (the ticket says `src/app/`; that's wrong). It logs `console.error("A page failed to load or render:", error)` from a `useEffect`.
- `src/app/routeError.test.tsx` has **4 tests**. Its `beforeEach` mocks `console.error` (`consoleError = vi.spyOn(...).mockImplementation(() => {})`), its `afterEach` calls `vi.restoreAllMocks()`.
- Only the first test ("shows the error inside the shell on a page load, and logs the error") asserts the log. The other three reach the alert and never wait for the log: "shows the error inside the shell when navigated to" (the one in the ticket), "reloads the page from its button", and "leaves the other pages working". Under load the passive effect hasn't run yet when the test ends; it runs during the setup's `cleanup()`, after the file's `afterEach` restored the mock, so the console guard fails the test with "Unexpected console output". **All four tests are exposed**, not just the one the ticket names.

### Cause 2: 1 s `findBy` timeouts on lazy pages

- The first navigation to a lazy page in a test file pays for loading (and Vite-transforming) that page's chunk; under load that exceeds `findBy`'s 1 s.
- KAN-51 fixed this in 8 files by preloading the lazy page in `beforeAll` (e.g. `src/pages/squad/SquadPage.test.tsx`: `beforeAll(() => import("@/pages/squad/SquadPage"));`, and a `describe`-level one in `src/app/AppShell.test.tsx`), but only where the **first** test of the file opens a lazy page. Today 11 test files have a `beforeAll` preload.
- `src/pages/DashboardPage.test.tsx` has **no** preload. "link to the squad table" (`await screen.findByRole("heading", { level: 1, name: he.nav.squad })` after a click) is the first test in that file to enter `SquadPage`. That matches the ticket's 3/20.
- Other candidates I saw without a preload (unverified): `src/app/sessionRouting.test.tsx` (a test renders `/app/squad?position=GK`), and possibly others. **Find the affected files by measurement, not by guessing.**

Read first: `CLAUDE.md` (rules + frontend paragraph), `frontend/package.json`, `frontend/vite.config.ts` (`test` block), `src/test/setup.ts`, `src/pages/RouteErrorPage.tsx`, `src/app/routeError.test.tsx`, `src/pages/DashboardPage.test.tsx`, `src/app/router.tsx` (route table only), and one existing preload (`src/pages/squad/SquadPage.test.tsx` top of file). Other files only as the stress runs point at them.

## Verify against what's installed, not memory

For each item, check the **installed** package (`node_modules/<pkg>/package.json`, its `.d.ts`, its source) and say in the summary how you checked:

- `@testing-library/dom` 10.4.2: the default `asyncUtilTimeout` for `findBy*` / `waitFor`, and that `@testing-library/react` 16.3.3 doesn't change it.
- Vitest 5.0.1: the default `testTimeout` and `hookTimeout` (a `beforeAll` preload counts against `hookTimeout`); how `--sequence.shuffle` and `--sequence.seed` are spelled in this version; and that the setup file's `afterEach` still runs after the test file's own (default `sequence.hooks`).
- React 19.3 + `@testing-library/react` 16.3.3: that `cleanup()` (unmount inside `act`) flushes a pending passive effect, i.e. that cause 1's mechanism is what the code actually does. One sentence of evidence (source or a probe) is enough.
- That a dynamic `import()` in a `beforeAll` lands in the same module cache the route's `lazy` uses within a test file under Vitest 5's default isolation (KAN-51 relied on it; confirm it still holds, e.g. by timing the navigation with and without the preload).

## Step 1: Re-measure on master, before changing anything

Define the modes exactly and use the same ones before and after:

- **Mode A**: full suite (`npx vitest run`), **2 processes in parallel**, **20 rounds** (40 runs).
- **Mode B**: full suite, shuffled (a different seed per run, recorded), **4 processes in parallel**, **20 rounds**.
- Plus `routeError.test.tsx` alone, 50 runs, as a sanity baseline.

Save each run's full output to a log file **outside the repo** (or in a git-ignored folder; check `.gitignore`), and record the machine (CPU cores, OS) once. **Don't commit the stress script or the logs** (agreed with Gal); put the exact commands in the summary instead, so the runs can be repeated.

From the logs, produce a table of **every** failing test across all runs: file, test name, count, and kind of failure (`findBy`/`waitFor` timeout and what it waited for; "Test timed out in 5000ms"; console guard; unmocked-request guard; other). This list, not the ticket, decides what you fix.

## Decisions (agreed with Gal, implement these)

### 1. Cause 1: the log is asserted in every test that renders the error page

- In `src/app/routeError.test.tsx`, make waiting for the log part of a **shared helper** that all four tests use, so a new test in the file can't forget it. For example, a helper that waits for the alert and then `await waitFor(() => expect(consoleError).toHaveBeenCalledWith(expect.any(String), chunkError))`, used by `expectErrorInShell()` and by the two tests that today call `screen.findByRole("alert")` directly. Your call on the exact shape; keep the file's style.
- Keep every existing assertion. The first test's synchronous `expect(consoleError).toHaveBeenCalledWith(...)` may become the shared `waitFor`.
- This is the console guard's documented opt-out (the test mocks the method and asserts the calls). **No allow-list**, no change to `consoleGuard.ts`, no change to `RouteErrorPage.tsx`.
- If another test file renders `RouteErrorPage` (grep for `routeError` / `RouteErrorPage` in `src/**/*.test.tsx`), apply the same rule there.

### 2. Cause 2: targeted preloads, KAN-51 pattern

- For each file the measurement shows failing on a lazy page, preload **that** page (or pages) in `beforeAll`, at the level that covers the tests that navigate to it (file or `describe`), exactly like the existing preloads. A short comment the first time in a file saying why ("the first navigation to a lazy page loads its chunk; under load that exceeds findBy's 1 s") is enough; if the existing preloads have such a comment, match it.
- A small shared helper in `src/test/` (e.g. `preloadSquadPages()`) is fine **only** if it removes real duplication; otherwise keep the plain one-liner. Your call; say which and why.
- **No global preload in `setup.ts`** (agreed: it slows all ~48 files for a problem in a few, and hides which test needs which page).
- **No timeout increase** (`asyncUtilTimeout`, `findBy`'s `timeout` option, `testTimeout`, `hookTimeout`, `retry`) as the fix. If, after the preloads, a specific `findBy` still times out and the cause is genuinely not a lazy chunk, stop on it and report: what it waits for, how long it actually takes (measure), and why. We'll decide together. A global change is only acceptable with that evidence and an explicit reason.
- If a failure in the table has a third cause (not cause 1, not a lazy chunk, not a plain 5 s overload), report it with evidence; fix it only if the fix is clearly test-only and small, otherwise stop on it.

### 3. Don't weaken anything

- No assertion removed or loosened, no `findBy` turned into a `queryBy`, no test skipped, no `it.fails`/`retry` to hide a flake.
- Don't touch the guards (`consoleGuard.ts`, `unhandledRequestGuard.ts`) or `setup.ts`, unless a measured failure proves a bug in them; then stop and report first.

## Tests and builds

- `npm run lint`, `npm run format:check`, `npm test`, `npm run build`, as in CI. Test counts before and after (expected: the same, since no tests are added or removed; say so if that changes).
- After the fix, re-run **Mode A** (must be 40/40 green), **Mode B** (report before/after; it doesn't have to be fully green if the remaining failures are pure "Test timed out in 5000ms" from machine overload, but **no** `findBy`/`waitFor` timeout on a lazy page and **no** console-guard failure from `RouteErrorPage` may remain), and `routeError.test.tsx` alone 50×.
- For each remaining Mode B failure, give its kind and why you classify it as overload (e.g. it's a test that's slow even unloaded; measure its unloaded duration).
- Show that the cause-1 fix actually guards against the race, not just that the runs happened to pass: e.g. temporarily delay the effect (a probe that holds the log back, not committed) and show the old test fails and the new one passes. Paste the output.
- `npm run e2e` is not required. Don't touch `e2e/`, Playwright config or CI.

## Docs

- **CLAUDE.md** (frontend paragraph, testing part): if it says nothing about preloading lazy pages, add one short sentence in the same dense style: a test that navigates to a lazy page preloads it in `beforeAll`, because the first load of a chunk can exceed `findBy`'s 1 s under load; no timeout increases instead. If it already says something like that (e.g. from KAN-51), make sure it's accurate (it covers any test that navigates to a lazy page, not only a file's first test). Same commit as the preloads (rule 7).
- **`docs/spec.md`: do NOT edit it.** I don't expect anything there to become outdated (section 11 describes what tests guarantee, not their timing). Grep section 11 for `lazy`, `timeout`, `flak`, `console` and confirm explicitly in the summary: either "nothing outdated" or each place (section + line + what's wrong).

## Commits

Small, focused commits, for example:

- `test(frontend): wait for the route error log in every routeError test (KAN-62)`
- `test(frontend): preload lazy pages before navigating to them (KAN-62)` (+ CLAUDE.md)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Branch, commits (hash + message), files changed, one line each.
2. Installed versions verified and the evidence for each "verify" item.
3. Machine and the exact stress commands (Modes A, B, routeError alone), so they can be repeated.
4. **Before** (master `1a2cf26`): the failure table (file, test, count, kind) for each mode.
5. Cause 1: what changed, which tests, and the output of the delayed-effect probe (old vs new).
6. Cause 2: which files got a preload, which pages, at what level, helper or not and why; any failure that wasn't a lazy chunk and what you did.
7. **After**: the same table for each mode, Mode A 40/40, routeError alone 50/50, and each remaining Mode B failure classified with its evidence.
8. Test counts before/after; lint/format/build results.
9. Spec: "nothing outdated" or the places.
10. Deviations from this prompt, and why.
11. Open ends and risks (e.g. CI runner speed vs your machine; a new lazy page added later without a preload).
12. Print in full: the new `src/app/routeError.test.tsx`, any new helper in `src/test/`, and the diff of every other changed file (preloads, CLAUDE.md).
