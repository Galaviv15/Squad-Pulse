# KAN-88: Scraper tests: flaky "a timeout counts as a network error (retried once)" in backendClient.test.js

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `cdc0b12`, the KAN-86 merge, PR #65). If it doesn't, stop and report. Then create the branch `feature/KAN-88-scraper-timeout-test-flake`.

## Context

Jira KAN-88, epic KAN-14 ("Match & Scraping Integration"). Found during KAN-86: the scraper test "a timeout counts as a network error (retried once)" (`scraper/test/backendClient.test.js`, around line 132) failed **2 of 13 local runs** on Gal's machine (Node v24.21.0). The failing assertion is `assert.deepEqual(result, { delivered: true })`, which got `{ delivered: false }`. CI (`scraper-ci`, Node 24) has not failed yet, but it runs the same suite. The file was last changed in KAN-85 (`7d2ea81`). Related: KAN-85 (last change to the test), KAN-62 (the frontend's stability-under-load work: same method of proof).

**This is a test-only ticket.** No change under `scraper/src/`, `backend/` or `frontend/`, unless the investigation finds a real bug in the client's retry. If it does, **stop and report** with the evidence before changing `src/`.

## What I found reading the code (verify each point; don't take it on trust)

1. **The likely cause.** In `scraper/src/backendClient.js`, `AbortSignal.timeout(timeoutMs)` is created **on every attempt**, with the same `timeoutMs`. The test passes `timeoutMs: 20`. Its `fetchImpl` makes attempt 1 wait for the abort (good: a real timeout), but attempt 2 is a real `fetch` to the local `node:http` test server, still under a 20 ms timeout. The server parses a full `contractExample()` body before answering. Under load, attempt 2 takes over 20 ms, also times out, and the client returns `{ delivered: false }`.
2. **The likely source of the load.** `npm test` is `node --test`, which runs test files in parallel child processes. One of them, `browser.integration.test.js`, launches a real headless Chromium. Verify the installed Node's default for file concurrency (see "Verify against reality").
3. **The same pattern elsewhere: `browser.integration.test.js`, "a navigation timeout is TIMEOUT, after one retry".** It passes `navigationTimeoutMs: 300`, and that timeout applies to **every** navigation in that scrape, not only to the hung path. The test also asserts `payload.standings.status === "OK"`, which needs a real Chromium navigation to `/leagues/league/` (plus its `LeagueTable` fetch) to finish within 300 ms under load. I expect this one to flake the same way. Check also "an in-page fetch timeout (AbortController in the page) is TIMEOUT" (`fetchTimeoutMs: 300`): as I read it, only the hung `LeagueTable` request goes through `fetchInPage` in `JOB`, so it should be safe. Confirm or refute this from the code and from runs.
4. Tests that pass a `timeoutMs` through `test/helpers.js` (`fakeSource`, `fakeClock`) use a fake clock and should not be exposed. `queue.test.js`'s `setTimeout(resolve, 5)` only yields and asserts nothing about time. Confirm both.

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. Reproduce and prove the cause first, on master, before changing anything

- Write a small stress harness (a shell script plus, if useful, a tiny classifier). Keep it **outside the repo** (e.g. `~/SquadPulse-stress-logs/KAN-88/`, like KAN-62) and don't commit it. Keep every run's log.
- **Load:** use the KAN-62 "Mode A" approach: run the full scraper suite (`npm test` in `scraper/`) **2 times in parallel**, repeated, so each iteration is real parallel load including Chromium. If that doesn't reproduce within the 50 runs, add CPU burners (e.g. `N` × `node -e "for(;;){}"`, N = number of cores) and say which load you used. Use the same load before and after.
- **Before:** 50 suite runs on master under load. Count failures **per test name** (not only per run), for both `backendClient.test.js` and `browser.integration.test.js`.
- **Prove the cause with evidence, not inference.** For the backendClient test, show which attempt failed and why. For example, temporarily (not committed) print `log.lines` on failure: I expect `backend not reached, result dropped error=timeout` on the failing runs. For the navigation test, temporarily measure how long the non-hung navigations take under that load (min / median / max), and show whether any came near or over 300 ms. If the evidence points elsewhere, say so and stop before fixing.

### 2. Fix the backendClient test deterministically (no timing dependency)

- **Attempt 1 stays a real timeout.** Keep waiting for `init.signal`'s abort, so the test still proves that the client wires `AbortSignal.timeout` in and classifies it as `timeout`. Also assert that the abort reason's `name` is `"TimeoutError"`.
- **Attempt 2 is answered in-process.** `fetchImpl` returns a `Response` with status 200 and the `STORED` JSON body directly: no network, no timer.
  - Check that the global `Response` in the installed Node 24 is what the client's `response.status` / `response.json()` / `response.body?.cancel()` expect.
  - Assert that attempt 2 received a signal that was **not** aborted, so the client still passes a fresh timeout on the retry.
- **Keep the existing assertions:** `calls === 2`, `{ delivered: true }`, the `WARN … retrying error=timeout` line. Add `clock.sleeps` deep-equals `[5000]` (the retry delay, as in the other retry tests).
- Keep `timeoutMs` small (it now only has to fire once); keep the test name.

### 3. Fix the navigation-timeout integration test the same way the ticket allows: a generous timeout for what must succeed

The browser test can't use a fake, because it is the only test against a real Chromium. So:

- Raise `navigationTimeoutMs` in "a navigation timeout is TIMEOUT, after one retry" to a value **justified by your measurement** in step 1: well above the max non-hung navigation time you saw under load. I expect around 2–3 s. The hung path never answers, so it still times out every time; the only cost is run time. Report the test's duration before and after.
- Don't change what it asserts.
- Apply the same treatment to `fetchTimeoutMs: 300` **only if** step 1 shows that anything other than the hung request depends on it.

### 4. Sweep the rest of the scraper suite for the same pattern

List every scraper test that combines a **real** timer or timeout with a **real** request, process or browser (short `timeoutMs`, `navigationTimeoutMs`, `fetchTimeoutMs`, `AbortSignal.timeout`, `setTimeout` with an assertion on time). Give each a one-line verdict: exposed (fixed how) / not exposed (why). Fix any other exposed test the same way, with the same before/after evidence.

### 5. Prove it

- **After:** 50 suite runs on the branch under **the same load** as before. Report the failure counts per test name, before and after, in one table. Also run each changed test file alone 50 times under load.
- **No network in the fixed test:** assert in it that the local test server received nothing (`received.length === 0`), so it can't depend on the server's speed.
- **Mutation checks (backendClient test):** break the client on purpose, confirm the new test fails, then restore it. At least:
  1. reuse attempt 1's signal for attempt 2 (create the timeout once, before the loop);
  2. no retry after a timeout (return `{ delivered: false }` on attempt 0);
  3. classify a timeout as `network error`.
  Report each and whether it was caught.

## Out of scope

Any change under `scraper/src/`, `backend/` or `frontend/` (unless step 1 finds a real client bug: then stop and report); changing the client's retry policy or timeouts; CI workflow changes; the frontend's own flakes (KAN-62 is done); adding a test-concurrency flag to `npm test` (propose it in the summary if your evidence says it matters, don't do it).

## Verify against reality, not memory

Check against what's installed (`scraper/package.json`, `scraper/package-lock.json`, `node_modules`, the Node binary you actually run), not general knowledge, and say in the summary **how** you checked each:

- **Node:** `scraper-ci` runs on **Node 24** (`.github/workflows/ci.yml`, job `scraper-ci`). Run on Node 24 (`node --version` in the shell you actually use) and report the exact version.
- **`node:test`:** whether `node --test` runs files in parallel by default on that version, and with what concurrency (check `node --help` and the installed version's docs, not memory). This is the load model.
- **`AbortSignal.timeout`:** the abort reason's `name` on that Node version (`TimeoutError`), checked by running it, since the client's classification depends on it.
- **Global `Response`:** that `new Response(body, { status: 200, headers })` gives what the client reads (`status`, `json()`, `body?.cancel()`), checked by running it.
- **Playwright** (resolved version from the lockfile): that `page.goto`'s `timeout` is per call, as `src/browserSource.js` uses it.

## Read first

`CLAUDE.md` (standing rules, especially the testing and docs rules), then only:

- `scraper/package.json`, `.github/workflows/ci.yml` (job `scraper-ci`)
- `scraper/src/backendClient.js`, `scraper/src/clock.js`
- `scraper/test/backendClient.test.js`, `scraper/test/helpers.js` (`fakeClock`, `fakeSource`, `contractExample`)
- `scraper/test/browser.integration.test.js`, `scraper/src/browserSource.js`, and in `scraper/src/scrape.js` only the `request` function and the default timeouts
- `scraper/test/queue.test.js` (only for the sweep)
- `docs/spec.md`, the testing section and status line: to list what goes out of date, **not** to edit (I expect nothing)

## Docs

- **`CLAUDE.md`:** if it has a scraper testing rule or paragraph, add **one** sentence: a test must not put a short real timeout on a request that has to succeed; fake it or give it a generous timeout (KAN-88). If there's no natural place, don't add one; propose the wording in the summary.
- **`README.md`:** no change expected.
- **`docs/spec.md`: do NOT edit it.** List any place that's now out of date (section + a few quoted words + what's wrong). I expect none.

## Commits

Small, focused commits, for example:

- `test(scraper): deterministic timeout-then-retry test in backendClient (KAN-88)`
- `test(scraper): generous navigation timeout where the scrape must succeed (KAN-88)`
- `docs: CLAUDE.md rule on real timeouts in scraper tests (KAN-88)` (only if added)

Stage files by path, never `git add -A`. Never commit in the same command as a test or stress run. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each. Confirm no change under `scraper/src/`, `backend/` or `frontend/`.
2. Versions verified and **how** you checked each, with the findings for every item in "Verify against reality" (evidence, not assumptions).
3. Reproduction: the harness (what it runs, the load, the machine's core count), and the evidence for the cause of each flake: log lines, timings. Say plainly whether my hypothesis in points 1–3 was right, partly right or wrong.
4. The sweep: every test checked, one line each with its verdict.
5. The fix for each test, and why it's deterministic (or, for the browser test, why the margin is safe: measured max vs new timeout).
6. Evidence: a table of failures per test name, before (master) and after (branch), same load, 50 runs each; plus the 50 single-file runs; plus the test duration change for the browser test; plus each mutation check and whether it was caught.
7. Scraper test count before and after (it should be unchanged), and the `npm run lint` / `npm run format:check` / `npm test` results.
8. Docs changed (or the proposed CLAUDE.md sentence), one line each. Spec: any out-of-date place, or "none".
9. Deviations from this prompt, and why.
10. Open ends and risks (e.g. other flakes you saw in the logs that aren't timing-related, whether file concurrency should be capped, anything CI-specific).
11. Print in full, at branch HEAD, every changed test (the whole `test(...)` block), and the diff of `CLAUDE.md` if changed.
