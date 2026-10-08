# KAN-53: Frontend E2E tests with Playwright: login, shell, squad table

**Credit budget is tight.** Read only the files this prompt names, plus whatever you need to answer a "verify" item. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `e0b7933`, the KAN-56 merge). If it doesn't, stop and report. Then create the branch `chore/KAN-53-playwright-e2e`.

Gal has approved, for this ticket, adding the new CI job and the backend test-sources seeder described below (CLAUDE.md rule 1). Anything beyond what this prompt lists still needs asking first.

## Context

Jira KAN-53 (Medium, epic KAN-43 "Frontend MVP"). Blocked by KAN-49 (squad table) and KAN-56 (HTTPS dev server), both done.

Today the backend tests run the API against real MongoDB/Redis (Testcontainers), and the frontend tests (Vitest + jsdom + MSW) run the UI against a mocked API. Nothing runs them together in a real browser: the refresh cookie, the proxy, the error shapes the UI really receives. This ticket adds a **small** Playwright suite against the real stack (backend + MongoDB + Redis + the built frontend), on Chromium and WebKit, plus a separate, non-Required CI job.

Facts you need (checked on master `e0b7933`; re-check the ones you rely on):

- Refresh cookie `refresh_token`: `HttpOnly; Secure; SameSite=Strict; Path=/auth`, no Domain (`AuthController.refreshCookie`). **The cookie is never weakened for tests** (no dropping `Secure`, no `SameSite=Lax`, no test cookie profile). Out of scope, same rule as KAN-56.
- The dev server is HTTPS only (KAN-56, `frontend/devHttps.ts`, cert in git-ignored `frontend/.cert/`). In `vite.config.ts`, `server.https` is set **only** when `command === "serve" && !isPreview && !process.env.VITEST`, so `vite preview` today serves **plain http**. A `Secure` cookie on plain `http://localhost` is dropped by Safari, and WebKit is likely to do the same, so E2E must run over HTTPS.
- The proxy (`server.proxy`, `BACKEND_PATHS` = `/auth|/squad|/clubs|/users`) targets `SQUADPULSE_BACKEND_URL` (default `http://localhost:8080`), with `xfwd: true`, `changeOrigin: false`, `server.cors: false`.
- Access token: `squadpulse.security.token.access-ttl: 15m` (`TokenProperties`, `@DurationMin(seconds = 1)`). `JwtService` sets no clock skew on the JJWT parser, so a short TTL expires exactly. The frontend refreshes only on a 401 to a request sent with a token (no proactive refresh; CLAUDE.md frontend paragraph).
- `POST /auth/logout` revokes the refresh token server-side (`authService.logout(refreshToken)`) and clears the cookie.
- Login throttle: 5 failed attempts per (email, IP) per 15 min (`squadpulse.security.login-throttle`), stored in Redis.
- The only way to create a club and its first user is the owner bootstrap (`auth.ClubBootstrapRunner` under the `bootstrap` profile, using `auth.ClubBootstrapService`). The runner **requires an interactive `System.console()`** and deliberately refuses secrets as arguments, so it can't run in CI. Don't change that runner or its rules.
- The local `docker-compose.yml` (MongoDB single-node replica set `rs0` + Redis, needs a root `.env`) is the same one Gal uses for development: database `squadpulse`, backend on port 8080. **E2E must never touch Gal's dev data.**
- KAN-56 lesson: Boot 4.1.1's `TestTypeExcludeFilter` doesn't see every test class, and test-only beans can be component-scanned into a context. Test-only endpoints are `@TestComponent` + `@Import`, and `SquadpulseApplicationTests.noTestOnlyEndpointIsRegistered` guards `/test-only/`.

Read first: `CLAUDE.md` (rules + frontend paragraph), `frontend/vite.config.ts`, `frontend/devHttps.ts`, `frontend/package.json`, `frontend/.gitignore`, `docker-compose.yml`, `.env.example`, `.github/workflows/ci.yml`, `backend/src/main/resources/application*.yml`, `auth/ClubBootstrapService.java`, `auth/OwnerBootstrapProperties.java`, the README local-setup and "Bootstrapping a new club" sections, and the login / shell / squad-table code only as far as you need selectors and i18n keys.

## Verify against what's installed, not memory

For every framework behavior below, check the **installed** version (`frontend/node_modules/*/package.json` and types, `backend/pom.xml` + the resolved Boot 4.1.1 plugin) and say in the summary how you checked. In particular:
- The Playwright version you add (`@playwright/test`), its config shape, `ignoreHTTPSErrors`, `webServer` (array form, `url` / `ignoreHTTPSErrors` / `reuseExistingServer` / `timeout`), trace/video options, and the browser install command with system deps.
- Vite 8 `preview`: does `preview.proxy` default to `server.proxy`? Does `preview.cors` default to `server.cors`? Does `preview.https` default to `server.https`? Does preview serve `index.html` for a deep link like `/app/squad` (SPA fallback)?
- The Boot 4.1.1 Maven plugin goal you use to run the seeder from the test classpath (e.g. `spring-boot:test-run`), and how to pass profiles / properties to it.
- Spring relaxed binding for the env vars you use (e.g. `SQUADPULSE_SECURITY_TOKEN_ACCESSTTL`, `SPRING_DATA_REDIS_DATABASE`, `SERVER_PORT`). Confirm each actually takes effect (e.g. the `expiresIn` in a login response).

## Decisions (agreed with Gal, implement these)

### 1. Frontend under test: the built bundle, served by `vite preview` over HTTPS

- The suite runs against `npm run build` + `vite preview`, not the dev server (what ships, and no dev-server dependency re-optimization reloads mid-test).
- Add HTTPS to preview **only when explicitly asked**, e.g. an env var such as `SQUADPULSE_PREVIEW_HTTPS=1` (name yours). Plain `npm run preview` stays exactly as today (no certificate needed). When the flag is set and the cert is missing, fail with a clear message (reuse `devHttpsOptions`); no http fallback.
- Preview must proxy the same `BACKEND_PATHS` to `SQUADPULSE_BACKEND_URL` with the same options and no CORS headers. If preview doesn't inherit them from `server`, set them explicitly from the same constants (one source of truth, no copy).
- Use a preview port that doesn't clash with the dev server (Vite's preview default 4173 is fine; verify).

### 2. Certificate

- Playwright runs with `ignoreHTTPSErrors: true` (both projects), so any certificate works.
- Locally: reuse the mkcert cert from `npm run dev:cert` (already on Gal's machine).
- CI: generate a throwaway self-signed cert with `openssl` into `frontend/.cert/` (same file names as `devHttps.ts`), in the job, never committed (`.cert/` and `*.pem` are already git-ignored; confirm).
- **Prove, don't assume**, that with `ignoreHTTPSErrors` and a self-signed cert, **WebKit and Chromium both store the `Secure` refresh cookie** and send it on `/auth/refresh` (the reload scenario does this; also assert it directly once, e.g. `context.cookies()` contains `refresh_token` and the reload's refresh returns 200). If WebKit doesn't, stop and report with evidence, don't work around it by weakening the cookie.

### 3. Isolation from Gal's dev environment

- E2E uses its **own database name** (e.g. `squadpulse_e2e`) in `MONGODB_URI`, its **own Redis logical database** (e.g. index 1) and its **own backend port** (e.g. 8081, fed to preview via `SQUADPULSE_BACKEND_URL`). Gal's running dev backend on 8080 and his `squadpulse` data must be untouched by a local E2E run. Reuse the existing compose MongoDB/Redis containers (same `.env` credentials); don't add a second compose file unless you find a real reason (explain it).
- Each run starts from a clean state: the seeder drops the E2E database and flushes **only** the E2E Redis logical database. This also resets the login throttle, which the wrong-password scenario would otherwise hit after a few local runs (5 failures / 15 min).
- **Safety guard:** the seeder refuses to run (non-zero exit, clear message) unless the configured database name ends with `_e2e` and the Redis database index is non-zero. Add a unit test for the guard.

### 4. Seeding

- **Club + admin:** a seeder that lives only in test sources (`backend/src/test/java/...`), in package `com.squadpulse.auth` (so it can use the package-private `ClubBootstrapService` as-is), active only under its own profile (e.g. `e2e-seed`), with **no web server** (`web-application-type: none`, like `application-bootstrap.yml`), run as a one-shot process that exits 0/1. It calls the real `ClubBootstrapService.bootstrap(...)`, so validation, password hashing (Argon2id + pepper) and the transaction are the production ones. The owner secret and the E2E user's password come from environment variables (it's test data, not a real secret; never logged). **Do not** add any endpoint, controller or production-code hook for seeding, and don't change `ClubBootstrapRunner`.
- Check the seeder's context: confirm that running it from the test classpath doesn't pull test-only beans into anything that matters, and that the `/test-only/` guard and the full backend test suite still pass with the seeder present (it must not start under normal `@SpringBootTest` contexts: it's profile-gated, verify).
- **Players:** created through the real API in Playwright's global setup: log in as the E2E admin with `request` (an `APIRequestContext` against the preview origin, `ignoreHTTPSErrors`), then `POST /squad/players` a few players (at least 3, different positions, one with a distinctive name to assert on). This goes through the real clubId isolation, no shortcuts.
- **Users:** one ADMIN (Club Manager) only. No scenario needs another permission level. Keep the seeder easy to extend.
- **The server under test runs from the normal packaged jar** (`./mvnw -DskipTests package` then `java -jar ...`), not from the test classpath, so no test bean can reach it. Run it with the `dev` profile (forwarded headers, no HSTS; same as local dev) and the E2E overrides below.

### 5. Short access-token TTL

The E2E backend runs with a short real access TTL (e.g. 10s; your choice, justify it) via env var. Don't fake the 401 with `page.route`. Make sure the other scenarios are not timing-sensitive because of it (they may refresh more often; that's fine and expected).

### 6. Orchestration

- One entry point, e.g. `npm run e2e` in `frontend/` (plus `e2e:ui` / headed if cheap), that works the same locally and in CI. Your choice whether the backend start, seeding and preview start live in Playwright's `webServer` array, `globalSetup`, or a small script; prefer the simplest thing that (a) waits for real readiness (backend health or a 401 from `/auth/refresh`, not a sleep), (b) shuts everything down afterwards, and (c) fails loudly with the backend log when the backend doesn't start.
- Locally the prerequisite is `docker compose up -d` (already part of Gal's setup) and the mkcert cert. Document it.
- E2E files live in `frontend/e2e/` (or similar). Keep them out of Vitest (`vitest` must not pick up `*.spec.ts` from there; verify), and include them in ESLint / Prettier / `tsc` the same way as the rest (or explain why not). Playwright output folders (`test-results/`, `playwright-report/`) git-ignored.

## Scenarios (small; detailed cases stay in Vitest + MSW)

Use role / label / text locators. Assert on Hebrew strings by importing them from `frontend/src/i18n/locales/he.json` (or the i18n instance), not by hard-coding Hebrew in the specs. Each test logs in fresh (new context), so tests are independent and can run in any order.

1. **Login → shell → squad table:** log in through the form, land in the shell (sidebar / top bar present), go to the squad screen, the seeded players are shown (assert the distinctive name and the count).
2. **Reload keeps the session:** after login, `page.reload()` on a protected route → still in the app (not the login screen); assert the `/auth/refresh` during bootstrap returned 200.
3. **Silent refresh on expiry:** log in, wait past the TTL (wait on time only here, with a small margin, and comment why), trigger a request through the UI (e.g. navigate to the squad screen). Assert from the network: the first `/squad/...` request got 401, exactly one `/auth/refresh` returned 200, the retried request returned 200, and the UI shows the data without going to login.
4. **Logout:** log in, record the `refresh_token` cookie value (`context.cookies()`), log out through the UI → login screen. Then: (a) reload → still the login screen; (b) **server-side revocation:** `POST /auth/refresh` with the **old** cookie value sent explicitly (a fresh request context with a `Cookie` header) → 401. (b) is the real check; the browser deleting its cookie alone proves nothing.
5. **Wrong password:** shows the error message from `he.json`, no navigation, and the response status is what the UI expects (report it).

Run the whole suite on `chromium` and `webkit` projects. Note in the README that WebKit here is not Safari on macOS; the Safari check stays manual.

**Stability:** run the full suite at least 5 times in a row locally on both browsers (e.g. `--repeat-each=5`) and report pass counts. Any flake = fix the cause (a missing wait, a race), never `retries` to hide it. In CI set `retries: 0` for now so flakes are visible (or justify a different value).

## CI

A third job, `e2e`, in `.github/workflows/ci.yml`, on `ubuntu-24.04`, same action versions as the existing jobs (`actions/checkout@v7`, `actions/setup-java@v6` Temurin 21, `actions/setup-node@v7` Node 22). **Do not touch the two existing jobs** and **don't change branch protection** (it stays not Required; Gal decides later).

- Generate a CI-only root `.env` with random values (`openssl rand`), never echoed to the log (no `set -x` near it). The secrets in it are throwaway.
- `docker compose up -d --wait` (verify `--wait` works with these healthchecks on the runner's Compose version), then the self-signed cert, build the jar, install Playwright browsers with system deps (cache them if it's simple and correct, keyed on the Playwright version), `npm ci`, `npm run e2e`.
- On failure, upload traces, videos and the Playwright HTML report (`actions/upload-artifact`, verify the current major) plus the backend log. Short retention (e.g. 7 days).
- A sensible `timeout-minutes`.
- Least privilege: the workflow keeps `permissions: contents: read`.

## Tests and builds

- Backend: the seeder guard unit test. Full `./mvnw verify` with JAVA_HOME = Temurin 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`; Spotless crashes on JDK 25). Report the count (1,059 at KAN-56, master may have moved; report before and after).
- Frontend: `npm run lint`, `npm run format:check`, `npm test`, `npm run build` all as in CI, and show that `npm test` / `npm run build` / plain `npm run preview` still need **no** certificate (run them with `.cert/` temporarily renamed away, then restore). Report counts (776 at KAN-56).
- `npm run e2e` locally: both browsers, the 5x repeat.
- Show that a local E2E run leaves Gal's dev data alone: count documents in the `squadpulse` database's main collections before and after a run (same numbers), and the dev backend on 8080 (if running) unaffected.

## Docs

- **README:** a short "End-to-end tests" section: prerequisites (`docker compose up -d`, `npm run dev:cert` once, Playwright browsers install command), `npm run e2e`, what it starts, the isolated database / Redis index / port, the short access TTL, where traces go, how to open a trace, and that WebKit isn't macOS Safari.
- **CLAUDE.md:** a short addition to the frontend paragraph (where E2E lives, how it runs, that it uses its own DB / Redis index / port and the built bundle over HTTPS, the seeder is test-sources-only with the `_e2e` guard, no test endpoints, the cookie is never weakened), and the rule that **each new screen ticket adds its own E2E scenario when it adds a real user flow** (the reason this ticket exists before the later screen tickets). Put it in the commit that introduces it (rule 7).
- **`docs/spec.md`: do NOT edit it.** List in the summary every place that needs updating, with line numbers: at least §11 (testing/CI: the new job and E2E), §13 Phase 3 row, and the KAN-43 note that Playwright is out of scope if the spec repeats it. Grep for `Playwright`, `E2E`, `end-to-end`, `CI`, `frontend-ci`.

## Commits

Small, focused commits, for example:
- `test(backend): add a test-only E2E seeder with an _e2e database guard (KAN-53)`
- `chore(frontend): serve vite preview over HTTPS on request (KAN-53)`
- `test(frontend): add Playwright E2E for login, session and squad table (KAN-53)`
- `ci: add a non-required e2e job (KAN-53)`
- `docs: document running the E2E tests (KAN-53)`

Stage files by path, never `git add -A`. Before each commit confirm with `git status --porcelain` that no `.env`, `.pem`, `test-results/` or `playwright-report/` is staged. **Do not push and do not open a PR.** (The CI job can only be proven on the PR; say so in the summary rather than claiming it works.)

## Summary to return

1. Files changed or added, one line each.
2. Installed versions verified (Playwright, Vite, Vitest, Boot plugin goal) and the evidence for each "verify" item above (preview proxy/cors/https defaults, SPA fallback, `test-run`, env-var binding with the actual `expiresIn` you saw).
3. How the stack starts and stops (command by command), and what readiness checks are used.
4. Seeder: how it runs, how the guard works, proof it can't start in normal test contexts, and proof the server under test runs from the jar.
5. Cookie evidence on **both** browsers with the self-signed cert (stored, sent, 200).
6. Per scenario: what it asserts, and the network evidence for scenarios 3 and 4(b).
7. The 5x repeat results per browser, and the total suite time.
8. Proof that Gal's dev data is untouched, and that test / build / plain preview need no certificate.
9. Test counts before/after and full build results.
10. Spec places to update (section + line + what's now wrong).
11. Deviations from this prompt, and why.
12. Open ends and risks: CI-only unknowns (to be confirmed on the PR), WebKit-specific issues, run time in CI, anything that could make the job flaky.
13. Print in full: the Playwright config, the global setup / orchestration script, the seeder and its guard test, the new `vite.config.ts`, and the new CI job.
