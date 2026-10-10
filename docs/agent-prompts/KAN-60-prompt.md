# KAN-60: Frontend tests: fail any test that sends an unmocked API request

**Credit budget is tight.** Read only the files this prompt names, plus whatever you need to answer a "verify" item. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `3952989`, the KAN-58 merge). If it doesn't, stop and report. Then create the branch `fix/KAN-60-unhandled-request-guard`.

## Context

Jira KAN-60 (Medium, label `bug`, epic KAN-43 "Frontend MVP"), found while working on KAN-51. Read the ticket's description: it was updated today after KAN-58.

`src/test/setup.ts` runs MSW with `onUnhandledRequest: "error"`. Spec section 11 and README.md claim "a request no mock covers fails the test". When the ticket was opened that was only true if the request was sent **before the test's last assertion**: a later one was rejected and logged, but the test passed (in KAN-51 only 2–4 of ~55 affected tests failed, varying between runs). This is the safety net that should catch a leaked token, a duplicate fetch, or a request after logout, so it must be reliable.

**KAN-58 (PR #39, merged as `3952989`) changed the picture**, and this prompt is built on it:

- `src/test/consoleGuard.ts`: an unexpected `console.error` / `console.warn` during a test fails it (checked in the setup's `afterEach`, after the test file's own hooks); one logged outside a test is kept and fails the **file** in `afterAll` (`endConsoleGuardFile`). Plain wrappers, not `vi.spyOn`. Opt-out: the test mocks the method itself (in the test or its `beforeEach`) and asserts the calls.
- MSW logs an unhandled request with `console.error` (`[MSW] Error: intercepted a request without a matching request handler: • GET /path ...`, asserted in `src/test/msw/server.test.ts`) **and** rejects the fetch. So today an unmocked request is *probably* already caught by the console guard, in the test or at file level. That is unverified, and it leaves real gaps:
  1. **Masking:** a test that mocks `console.error` (the guard's opt-out; e.g. `src/app/routeError.test.tsx`) also swallows MSW's error. An unmocked request in such a test passes silently.
  2. **Attribution:** a request landing after the test's check fails the file, not the test.
  3. **Implicit:** the safety net depends on MSW printing to the console, not on an explicit mechanism.
- The known AppShell leak (`GET /squad/players/x` in "keeps the squad link current below /app/squad") was already fixed in KAN-58. The reporter issue ("Vitest only prints the log for failing tests") was KAN-58's root cause (Vitest 5.0.1 picks the `minimal` reporter under an AI agent) and is fixed in `vite.config.ts`.

Facts I checked on master (re-check what you rely on):

- `package-lock.json`: `msw` **2.15.0**, `@mswjs/interceptors` **0.41.9**, `vitest` **5.0.1**. Vitest globals are off; default `sequence.hooks` is relied on by setup.ts (the setup's `afterEach` runs after the test file's own).
- `src/test/setup.ts`: `installConsoleGuard()`; `beforeAll` → `server.listen({ onUnhandledRequest: "error" })`; `beforeEach` → `beginConsoleGuardTest()` + session/bootstrap reset; `afterEach` → `cleanup()`, session/bootstrap reset, `server.resetHandlers()`, then `endConsoleGuardTest()` (throws); `afterAll` → `server.close()`, then `endConsoleGuardFile()` (throws).
- `src/test/msw/handlers.ts` is deliberately empty and must stay empty. `src/test/msw/server.ts` exports `server = setupServer(...handlers)`.
- `src/test/msw/server.test.ts`: two tests ("fails a request that no handler covers", "forgets a test's handlers after it") send an unmocked request **on purpose**, opt out of the console guard with `expectUnhandledLogged(path)` (mocks `console.error`, asserts the exact MSW message), and expect the fetch to reject with MSW's `"error" strategy` message.
- `src/test/consoleGuard.test.ts` uses `it.fails(...)` and calls the guard's check functions directly, to test failures without a red test in the suite. Follow the same approach.
- Nine test files use `vi.spyOn(globalThis, "fetch")` to assert that *no* request is sent (e.g. `src/app/AppShell.test.tsx`, `src/pages/squad/SquadPage.test.tsx`). CLAUDE.md's frontend paragraph explains why: "an unhandled request only rejects the fetch, which doesn't fail a test whose UI treats a failure like 'nothing there'" (a KAN-51 note).
- Frontend Vitest at KAN-58: 784 passed + 1 `it.fails` (47 files). Report before and after.

Read first: `CLAUDE.md` (rules + frontend paragraph), `frontend/package.json`, `frontend/vite.config.ts` (`test` block), `src/test/setup.ts`, `src/test/consoleGuard.ts` + `consoleGuard.test.ts`, `src/test/msw/server.ts`, `handlers.ts`, `server.test.ts`, `src/app/routeError.test.tsx`. Other files only as far as a leak you find requires.

## Verify against what's installed, not memory

For each item, check the **installed** package (`node_modules/msw/package.json`, its `.d.ts`, its `lib/` source; same for `@mswjs/interceptors` and `vitest`) and say in the summary how you checked:

- MSW 2.15.0: the `server.events` API — the exact name and payload of the unhandled-request event (`request:unhandled`?: does it carry `request` and `requestId`?), **when** it fires relative to the `onUnhandledRequest` strategy (before? after? also when the strategy throws?), and whether it fires synchronously with the fetch call or later (microtask / macrotask).
- MSW 2.15.0: what a **custom** `onUnhandledRequest(request, print)` callback does if it neither calls `print.error()` nor throws — does the request then go to the real network (passthrough)? What does `print.error()` do (log + throw?). This decides whether you can stop MSW's own `console.error` without letting a request through. **A request must never reach the network.**
- MSW 2.15.0: `server.events.removeAllListeners()` / listener lifetime across `resetHandlers()` and `close()`.
- What happens to a request sent **after** `server.close()` in `afterAll` (a stray promise at the end of a file): does jsdom's / Node's fetch try the real network, and is it visible anywhere? Report what you find; don't build a mechanism for it unless it's cheap and clean.
- Vitest 5.0.1: that a throw from the setup file's `afterEach` fails the test that just ran, and a throw from `afterAll` fails the file (KAN-58 relied on this; confirm it still holds for your code path), and how `it.fails` reports.

## Step 1: Diagnose on master first (before changing anything), with evidence

Write throwaway probe tests (don't commit them) and run each with `npx vitest run <file>`. For each, report: did the test fail, did the file fail, did it pass, and the exact output.

- **A:** a component test whose effect sends an unmocked `GET` that lands **after** the test's last assertion (e.g. render something that fires a query after the asserted element appears; or a `setTimeout(() => fetch(apiUrl("/squad/summary")), 0)` after the assertion).
- **B:** an unmocked request fired during `cleanup()` / unmount, or from a timer that fires after the test's `afterEach` check (so it lands outside a test).
- **C:** an unmocked request inside a test that mocks `console.error` (the opt-out, as in `routeError.test.tsx`), without asserting it.

This tells us what KAN-58 already covers. Expected (unverified): A fails the test, B fails the file, C passes silently. If reality differs, say so; the design below still applies unless the diagnosis shows a reason it can't work, in which case stop and report.

## Decisions (agreed with Gal, implement these)

### 1. An explicit unhandled-request guard

- A module next to the console guard (e.g. `src/test/unhandledRequestGuard.ts`), wired in `src/test/setup.ts`, on the same model as `consoleGuard.ts`:
  - Listens to MSW's unhandled-request event (`server.events`), not to the console.
  - Records each unhandled request (method + full URL) into the current test's list while a test runs, otherwise into a file-level list.
  - The setup's `afterEach` checks the test's list and **throws**, failing that test, with a message that names **every** method and URL (e.g. `Unmocked request(s) in this test: GET /squad/summary`), plus a one-line hint ("add a handler with server.use(...), or fix the code if the request shouldn't happen").
  - The setup's `afterAll` checks the file-level list and throws, failing the file, with the method, URL and the name of the last test that ran (for a pointer). **Per-test attribution of a late request is not required** (agreed with Gal; don't add timing heuristics for it).
- Order inside the setup's `afterEach`: the request check must still run if something earlier in the hook throws, and so must the console check (wrap so neither hides the other; if both fail, the thrown error carries both messages). Same for `afterAll`.
- **The request must still never reach the network, and the app's fetch must still reject**, as today.
- **No double or confusing failure.** An unhandled request must produce one clear failure that names method and URL, not a console-guard failure about an MSW log line *plus* a request-guard failure. Choose the cleanest way your verification allows, e.g. a custom `onUnhandledRequest` that rejects the request without MSW's `console.error` (only if verified that it doesn't pass through), or keeping `"error"` and making the console guard not count MSW's own unhandled-request line because the request guard reports it. **Don't** add a text-pattern allow-list to the console guard unless it's that single, exact MSW line and only because the request guard demonstrably reports the same request; explain the choice and the evidence.
- The guard must not be removable by `vi.restoreAllMocks()` / `vi.resetAllMocks()` or by `server.resetHandlers()`; register its listener once per file (in `beforeAll` / at install) and confirm it survives `resetHandlers()`.
- **Masking closed:** an unmocked request fails the test even when the test mocks `console.error`.

### 2. An explicit, asserted opt-out for tests that send an unmocked request on purpose

- Exactly one documented way, e.g. `expectUnhandledRequest("GET", "/squad/players")`, which marks that one request (method + path) as expected for the current test and returns, or registers, an assertion that it actually happened once. An expectation that's never met **fails** the test (no stale opt-outs). Reset per test.
- No allow-list, no "ignore everything" switch, nothing that applies beyond the one test.
- `server.test.ts`: convert both deliberate tests to the new opt-out and keep everything they prove today (fetch rejects with the MSW strategy message; the request is reported). Remove or simplify `expectUnhandledLogged` only if MSW no longer logs (decision 1); otherwise keep the assertion that matches reality.

### 3. Leaks

- Run the full suite with the guard on and list **every** unhandled request it surfaces (file, test, method + URL, cause).
- Fix each one: an explicit handler in that test (with `server.use(...)`, reusing the existing builders in `src/test/msw/` and the test file), or, if the request shouldn't happen, the product code. **If a fix needs product code, stop on that one and report it** (file, request, proposed change) before changing product code; we'll decide, possibly in a separate ticket.
- `handlers.ts` stays empty. Each fix keeps the test's meaning; never weaken an assertion, and don't wait on a request just to make it land inside the test unless the test is actually about it.
- If more than ~20 distinct leaks surface, stop after the guard and the list, commit, and report before fixing.

### 4. Tests for the guard itself (e.g. `src/test/unhandledRequestGuard.test.ts`)

Without leaving a red test in the suite (call the check functions directly, or `it.fails`, as `consoleGuard.test.ts` does):

- An unmocked request **after the test's last assertion** fails the test, and the message names method and URL.
- An unmocked request in a test that **mocks `console.error`** still fails it.
- A request recorded outside a test fails the file-level check, naming method and URL.
- A handled request doesn't fail anything.
- The opt-out: an expected request passes; an expected request that never happens fails; an *other* unmocked request in the same test still fails.
- The guard still works after `server.resetHandlers()` and after a test that called `vi.restoreAllMocks()`.
- The fetch still rejects (the request never goes out).

## Tests and builds

- `npm run lint`, `npm run format:check`, `npm test`, `npm run build`, as in CI. Report test counts before and after.
- Run the full suite **5 times in a row**; all green, same counts. A request that only sometimes leaks is a race: find its cause, don't paper over it.
- **Break a handler on purpose** (temporary, not committed) and show the right test fails, with method and URL in the message, on each of 3 runs:
  - remove the `GET /squad/summary` handler from one dashboard test;
  - add an unmocked request to a test in `routeError.test.tsx` (which mocks `console.error`).
  Paste the failure output.
- `npm run e2e` is not required. Don't touch `e2e/`, Playwright config, or CI.

## Docs

- **CLAUDE.md** (frontend paragraph, testing part, in the commit that introduces the guard, rule 7): an unmocked request fails the test that sent it (or the file, if it lands outside a test), regardless of console mocks; the one documented opt-out and that it's asserted; `handlers.ts` stays empty. Re-check the KAN-51 sentence about `vi.spyOn(globalThis, "fetch")` ("an unhandled request only rejects the fetch, which doesn't fail a test ..."): it's no longer true as written. Keep the advice to record fetch when a test asserts that *no* request is sent (it also covers *handled* requests), and fix the reason. Keep it as dense as the existing paragraph.
- **README.md** (frontend section, ~line 124, "a request no mock covers fails the test"): make it true and consistent with CLAUDE.md, in the same short style.
- **`docs/spec.md`: do NOT edit it.** Grep it for `MSW`, `mocked`, `unhandled`, `console` and list in the summary every place that is now outdated or imprecise (section + line + what's wrong). Section 11's sentence ("a request no test mocked fails the test (KAN-45)") is expected to need a change; I'll prepare the spec update.

## Commits

Small, focused commits, for example:
- `test(frontend): fail tests on unmocked API requests (KAN-60)` (guard + its tests + setup wiring + `server.test.ts` + CLAUDE.md)
- `test(frontend): answer requests surfaced by the unmocked-request guard (KAN-60)` (one or several, grouped by cause)
- `docs: say an unmocked request fails the test (KAN-60)` (README)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Branch, commits (hash + message), files changed or added, one line each.
2. Installed versions verified (MSW, @mswjs/interceptors, Vitest) and the evidence for each "verify" item: event name/payload/timing, custom `onUnhandledRequest` passthrough behavior, listener lifetime, request after `server.close()`, hook failure semantics.
3. Diagnosis on master: probes A, B, C, what each did, and the output.
4. The guard: design, where it checks, how double failure with the console guard is avoided (and why that choice), how the request is still blocked, the opt-out mechanism, and its own tests (by name).
5. Every leak surfaced: file, test, method + URL, cause, fix (or "stopped: needs product change", with the proposed change).
6. Test counts before/after, the 5 full runs, lint/format/build results, and the output of the two deliberately broken handlers (3 runs each).
7. Spec places now outdated (or none).
8. Deviations from this prompt, and why.
9. Open ends and risks.
10. Print in full: the new `src/test/setup.ts`, the guard module and its tests, the new `server.test.ts`, and the CLAUDE.md and README diffs.
