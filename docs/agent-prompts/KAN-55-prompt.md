# KAN-55: Make the root-logger log assertions immune to background driver noise

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `5dd42a7` (the KAN-45 merge). If it doesn't, stop and report. Then create the branch `fix/KAN-55-flaky-log-assertions`.

## Context

This is Jira KAN-55 (High). The project has no Bug issue type, so it's a Task labeled `bug` and `flaky-test`. Related: KAN-54 (Low, later) removes the noise at its source by sharing test containers. **This ticket does not do KAN-54.**

What happened: backend-ci failed on master after the KAN-45 merge (run 37290423038, job 111699273575, commit 5dd42a7). The tree was identical to PR #27's head, where backend-ci passed (run 37289954000). Re-running the failed job passed with 1024 tests. The failing test was `GlobalExceptionHandlerLoggingTest.anUnmappedClientErrorKeepsItsStatusAndIsNotLoggedAtError` (line 195, `assertThat(errors()).isEmpty()`). It captured an ERROR that has nothing to do with the code under test:

```
ERROR … [xecutorLoop-6-4] i.n.u.c.D.rejectedExecution : Failed to submit a listener notification task. Event loop shut down?
java.util.concurrent.RejectedExecutionException: event executor terminated
  ... io.lettuce.core.protocol.RedisHandshakeHandler.fail(RedisHandshakeHandler.java:123)
```

Cause: each integration test class starts its own MongoDB and Redis containers. Spring keeps those application contexts cached after the class's containers stop, and their Lettuce / Netty / Mongo clients keep logging on background threads at random moments. CI shows about 10 such ERRORs per run: 9 in the green PR run, 11 in the red master run, 10 in the re-run. The logback root logger is JVM-wide, so a test that counts every event on it fails whenever one of these lands inside its window. That's true even for a test with no Spring context at all.

### Facts I checked on master (`5dd42a7`). Re-confirm them, don't take them on faith

- **`GlobalExceptionHandlerLoggingTest`** (`backend/src/test/java/com/squadpulse/common/`) is **standalone MockMvc**: no Spring context, no Tomcat, everything runs on the test thread. It still sees the noise, because other classes' cached contexts log to the same root logger.
  - The root-logger `ListAppender` is set up at lines 75–86.
  - Zero-ERROR assertions are at lines 195 and 228. Exactly-one assertions go through `theOnlyError()` at line 275.
  - The **WARN-or-above** assertion is at lines 254–256 (`aClientThatWentAwayIsNeitherLoggedNorAnswered`), on raw `appender.list`.
  - `errors()` (line 272) and `everythingLogged()` (line 283) iterate `appender.list` **directly, with no copy**. `appender.list` is a plain `ArrayList` that background threads may be appending to, so this can throw `ConcurrentModificationException`. That's a second, rarer flake source.
- **`ErrorRenderingIntegrationTest`** (KAN-35) is a real `@SpringBootTest` with Tomcat on a random port, and it also captures the root logger.
  - `errors()` is at line 294, with `List.copyOf(appender.list)`, and `theOnlyError()` is at line 300.
  - Zero-ERROR assertions are at lines 165, 191 and 201.
  - In `aClientThatWentAwayIsLoggedAtDebugOnly`, lines 224–226 assert nothing at **WARN or above** from any logger, on raw `appender.list`. Line 227 filters raw `appender.list` to `UnhandledExceptionFilter`'s logger. That one isn't exposed to noise, but it should still read from a safe snapshot.
  - The **only** place the "not also logged by Tomcat" guarantee lives is this class: `theOnlyError()` plus its logger-name assertion (e.g. lines 135–136, 212–213). A duplicate ERROR from `org.apache.catalina` / `org.apache.coyote` / `org.apache.tomcat` would make it two.
- `CurrentUserIntegrationTest`, `ApiErrorControllerTest` and `UnhandledExceptionFilterTest` capture one specific logger and aren't affected. Confirm that, and grep the whole test tree for any other `ROOT_LOGGER_NAME` / `ListAppender` use.

Read these first:
- `CLAUDE.md`: the error-handling / logging paragraph (KAN-31, KAN-35).
- Both test classes above, in full.

## Why the root logger is captured (don't lose this)

The root-logger capture is deliberate. It's how these tests prove that:
- a filter exception is logged **once, by us**, and **not also by Tomcat** (KAN-35);
- a 4xx is never logged at ERROR, by anyone;
- a client that went away produces nothing at WARN or above, from anyone;
- nothing logged anywhere contains the query string, body, headers or cookie markers (`everythingLogged()`).

The following fixes are **rejected**. Don't use them:
- **Narrowing the capture to one logger, or to the test thread.** In `ErrorRenderingIntegrationTest` the request runs on a Tomcat thread, and a duplicate log from Tomcat would come from Tomcat's own logger. Either narrowing silently drops the guarantee.
- **Silencing the drivers in a `logback-test.xml`** (or via `logging.level.*`). It hides real diagnostics in CI output, and it would also hide those events from `everythingLogged()`.
- **Retries / rerun-on-failure** (e.g. surefire `rerunFailingTestsCount`).

## Decision (implement this)

1. **One shared test helper** in `backend/src/test/java/com/squadpulse/common/`, e.g. `CapturedLogs` (the name is yours), used by both classes. It owns the `ListAppender` on the root logger, with start/attach and detach (via JUnit 5 extension or plain calls from `@BeforeEach` / `@AfterEach`, your choice; keep it simple). It exposes:
   - `errors()`: ERROR events, **excluding** events from known background-driver loggers.
   - `warningsOrAbove()` (or similar): WARN+ events, with the same exclusion.
   - `theOnlyError()`: asserts exactly one event in `errors()` and returns it. On failure the message must list the offending events (logger, thread, message), so a future CI failure is diagnosable from the log alone.
   - `everythingLogged()`: **unfiltered**, every event at every level from every logger, so the "never logs secrets" checks still see everything.
   - Whatever the debug-only test needs (e.g. `events()` = unfiltered snapshot), so no test touches `appender.list` directly.

   The exclusion rule:
   - It's a small, explicit **deny-list of logger-name prefixes**, matched on a package boundary (`io.lettuce` matches `io.lettuce.core.X`, not `io.lettucefoo`).
   - Exclude by **logger name only**: never by message text, never by thread.
   - Derive the list from what CI actually logs. Read the job logs of run 37290423038 (attempts 1 and 2) and run 37289954000 (`gh run view <id> --log`, `--log-failed`, `--attempt`). Collect every distinct ERROR **and WARN** event whose logger is a driver (not `com.squadpulse`, not Spring, not Tomcat). Expected candidates: `io.netty`, `io.lettuce`, `org.mongodb.driver`.
   - Don't add a prefix you didn't see in those logs. If a driver WARN comes from an unexpected logger, report it before adding it.
   - Never deny-list `org.apache.catalina`, `org.apache.coyote`, `org.apache.tomcat`, `org.springframework` or `com.squadpulse`, whatever the logs show. If CI logs show background noise from one of those, stop and report.
   - Give the deny-list a short Javadoc: why it exists (KAN-55: background clients of cached Spring contexts whose containers have stopped), and that KAN-54 should make it unnecessary.

2. **Thread-safe snapshot.** Every read goes through one snapshot method. Logback's `AppenderBase.doAppend` is `synchronized` on the appender instance, so copying under `synchronized (appender) { new ArrayList<>(appender.list) }` gives a consistent snapshot. **Verify that against the installed logback version** (read the `AppenderBase` source from the jar or its tag; report the version and the line). If it isn't synchronized that way, choose another safe approach and explain it. `List.copyOf` alone is not enough: it isn't atomic against a concurrent `add`.

3. **Switch both test classes to the helper.** Their assertions keep their meaning: the same expected logger names, messages, levels and counts. Don't weaken or delete any assertion. If one can't be kept as-is, stop and report. After the change, neither class may reference `appender.list`, `ListAppender` or the root logger directly.

4. **A test of the helper itself** (e.g. `CapturedLogsTest`), with no Spring context:
   - an ERROR from each deny-listed prefix, logged **from another thread** (joined before asserting), doesn't appear in `errors()` and doesn't break `theOnlyError()`; the same holds for WARN and `warningsOrAbove()`;
   - an ERROR from `org.apache.catalina.core.StandardWrapperValve` and one from `com.squadpulse.whatever`, **also logged from another thread**, **do** appear in `errors()` (this proves we don't filter by thread);
   - a near-miss name (e.g. `io.lettucefoo.Bar`) is not excluded;
   - a deny-listed event still appears in `everythingLogged()`;
   - `theOnlyError()`'s failure message names the offending events.

5. **Prove the original failure is fixed, deterministically.** Add a test (in the helper test or the logging test, your call) that reproduces the CI situation: while a zero-ERROR and an exactly-one assertion run, another thread logs the exact Netty ERROR from the CI log, under the **same logger name** as in CI. It must pass now and would have failed with the old `errors()`. Show the old failure once: temporarily point it at the old unfiltered behavior, run it, paste the failure, then revert. Say that you did.

## Verify against what's installed

- **Logger names:** confirm each deny-listed logger name against the installed jars (e.g. `io.netty.util.concurrent.DefaultPromise` and its `rejectedExecution` logger, `io.lettuce.core...`, `org.mongodb.driver...`). Report the versions: `./mvnw dependency:list | grep -E "netty|lettuce|mongodb-driver|logback"`.
- **Logback's `AppenderBase` synchronization:** see Decision 2.

## Tests and build

Run `./mvnw verify` with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25). Everything must be green and Spotless clean. Report the total test count (1024 before this ticket) and the new tests.

Run the two affected classes **20 times** in a loop (e.g. `./mvnw -q test -Dtest='GlobalExceptionHandlerLoggingTest,ErrorRenderingIntegrationTest'`) and report pass/fail counts. Locally this will most likely pass both before and after, so it's a sanity check, not the proof. The proof is Decision 5.

## Docs

- **CLAUDE.md:** add one sentence to the error-handling / logging paragraph. Tests that assert on logs across the app (root logger) must use the shared helper, which ignores background driver loggers by name and reads a synchronized snapshot. They must never count raw root-logger events. Change nothing else unless it becomes inaccurate.
- **README:** probably nothing to change. Say if you changed it.
- **`docs/spec.md`: do NOT edit it.** I expect no spec change. If you think one is needed, list it in the summary.

## Commits

Small, focused commits, for example:
- `test(common): add a shared log capture that ignores background driver noise (KAN-55)`
- `test(common): use the shared log capture in the error logging tests (KAN-55)`

The CLAUDE.md sentence goes in the commit that introduces the rule (rule 7). Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. The background driver events found in the three CI runs (logger, level, message, count per run), and the final deny-list with which prefix covers which event. Also say whether any driver WARNs appeared.
3. The Decision 5 proof: the old failure output, and the new passing test.
4. Evidence for "Verify against what's installed" (versions, logger names, the `AppenderBase` line).
5. Test list with counts; full build result (command, outcome, total); the 20-run loop result.
6. Deviations from this prompt, and why.
7. Open ends and risks. For example, a real application ERROR logged by a deny-listed logger would now be ignored by these tests: say whether that's realistic. Also say whether any other test in the suite looks flaky for the same reason.
8. Print the full helper, its test, and the diffs of both test classes and CLAUDE.md.
