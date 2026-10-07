# KAN-56: Serve the local dev server over HTTPS so Safari keeps the Secure refresh cookie

**Credit budget is tight.** Read only the files this prompt names, plus whatever you need to answer a "verify" item. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `6e88d9d`, the KAN-46 merge). If it doesn't, stop and report. Then create the branch `chore/KAN-56-https-dev-server`.

## Context

Jira KAN-56 (Medium, epic KAN-43 "Frontend MVP"). It blocks KAN-53 (Playwright E2E on Chromium and WebKit).

The refresh cookie `refresh_token` is `HttpOnly; Secure; SameSite=Strict; Path=/auth` (`AuthController.refreshCookie`). The Vite dev server runs on `http://localhost:5173` and proxies the API to `http://localhost:8080` (same origin, no CORS; that's the KAN-43 decision). Chrome stores a `Secure` cookie on `http://localhost`, but Safari 26.5.2 doesn't. In Safari, login returns 200 with `Set-Cookie`, the next `POST /auth/refresh` goes out with no `Cookie` header and gets a 401, and every page reload logs you out.

**The fix is to move local dev to HTTPS. The cookie is NOT changed.** Don't drop `Secure`, don't use `SameSite=Lax`, and don't add a dev-only cookie profile. Any change to cookie attributes is out of scope. The production reverse proxy (Phase 6) is out of scope too.

Read first: `CLAUDE.md` (frontend paragraph and rules), `frontend/vite.config.*` (and the vitest config, if it's a separate file), `frontend/package.json`, `frontend/.gitignore` / root `.gitignore`, the README local-setup section, `frontend/.env.example`, and the backend `application*.properties|yml`.

## You do the setup yourself

Gal won't install anything by hand. Install mkcert yourself (`brew install mkcert`, or check `which mkcert` first), run `mkcert -install`, and generate the certificate. If a step needs a password or a GUI confirmation you can't give (`mkcert -install` writes to the macOS keychain and may prompt), stop at that step and print the exact one-line command for Gal to run. Then continue after he confirms.

## Decisions (already agreed, implement these)

1. **Mechanism: Vite's own `server.https`, no plugin.** The config reads a key and cert from a git-ignored folder inside `frontend/` (e.g. `frontend/.cert/localhost-key.pem` and `localhost.pem`; the names are yours). Generate it for `localhost 127.0.0.1 ::1`. Add an npm script that creates it (e.g. `npm run dev:cert`) so the README step is one command after `mkcert -install`. **Never commit certificates or keys.** Add the folder to `.gitignore`, and before each commit confirm with `git status --porcelain` and `git check-ignore -v` that no `.pem` is tracked or staged. Verify the exact `server.https` option shape against the **installed** Vite (8.3.0 per the KAN-46 agent; confirm from `node_modules/vite/package.json` and its types), not from memory.

2. **HTTPS is the default for `npm run dev`. There's no http fallback.** If the cert files are missing, `npm run dev` must fail at startup with a clear message naming the command to run. It must not silently serve http.
   - **Important trap:** Vitest loads the Vite config too (often with `command === "serve"`). The missing-cert check and the https setting must **not** apply under Vitest, or `npm test` breaks in CI where no cert exists. Also check `npm run build`, `npm run lint` and `vite preview`. Gate it properly (e.g. on `process.env.VITEST`, `mode`, or `command`; your choice, verified against how Vitest 5.0.1 actually invokes the config) and prove it: run `npm test` and `npm run build` **with the cert folder temporarily renamed away** and show they pass. Then restore it.
   - Keep the change dev-only. Production build output must be unaffected.

3. **Proxy forwarding and the backend's `Location` header.** The proxy target stays `http://localhost:8080`; only browser to Vite needs TLS.
   - `POST /squad/players` builds `Location` from the current request (KAN-26). Through an HTTPS dev server it must come out as `https://localhost:5173/...`, not `http://...` and not `:8080`.
   - **First, measure the current behavior.** Through the HTTPS proxy, with the current backend config, what `Location` comes back? Also report what the proxy sends as `Host` (check `changeOrigin` in the current config) and whether it sends `X-Forwarded-*` today. Verify how the installed Vite 8 proxy handles `xfwd` (Vite 8 may not use the old `http-proxy`; check `node_modules`).
   - **Then the fix:** the proxy sends `X-Forwarded-Proto` / `Host` / `Port` / `For` (e.g. `xfwd: true`). The backend honors forwarded headers **only in local dev, never by default**, so production and tests without that profile can't be fooled by spoofed forwarded headers. Expected approach: a `dev` Spring profile (create `application-dev.*` if none exists) that sets `server.forward-headers-strategy` (`native` = Tomcat `RemoteIpValve`, or `framework` = `ForwardedHeaderFilter`; pick one and justify). Document running the backend with that profile (e.g. `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`). Verify the property name, values and default against **Boot 4.1.1** source/docs for the installed version, not general knowledge. If a dev profile already exists or there's a cleaner existing hook, use it and explain.
   - **Login throttle:** it keys on `getRemoteAddr()`. Forwarded-header handling can change `getRemoteAddr()` to the `X-Forwarded-For` value. Confirm what the throttle key becomes through the proxy in dev (it should still be the loopback address) and that it's unchanged without the profile. Also confirm a client can't pick its own throttle key by sending `X-Forwarded-For` in the default (non-dev) configuration.

4. **Vite's CORS headers:** the dev server adds `Access-Control-Allow-Origin: http://localhost:5173` and `Vary: Origin` to proxied responses. Confirm they come from Vite's `server.cors` default (installed version) and not the backend, then turn it off (`server.cors: false` or the correct shape for the installed version), so dev matches "no CORS". Show that the headers are gone (curl through the proxy with an `Origin` header).

## Tests

- **Backend (forwarded headers).** With the `dev` profile, a request carrying `X-Forwarded-Proto: https` + `X-Forwarded-Host: localhost:5173` gets a `Location` starting with `https://localhost:5173/`. **Without** the profile, the same headers are ignored (spoof guard). If you choose `native`, `RemoteIpValve` lives in Tomcat, so MockMvc won't exercise it. Use a real-port test (`RANDOM_PORT`) for whichever strategy you pick, and say why the test proves it. Add a throttle-key assertion where it's cheap. Run the new test against the old config first (profile without the property) and confirm it fails.
- **Frontend.** If the cert check lives in a small helper (e.g. a function that returns the https options or throws), unit-test the missing-file message. Keep it small.
- Full builds: backend `./mvnw verify` with JAVA_HOME = Temurin 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`; Spotless crashes on JDK 25). Frontend `npm run lint`, `npm run format` check (as in CI), `npm test`, `npm run build`. Report totals (backend was 1039 at KAN-55, frontend 87 at KAN-46; master may have moved since, so report the before and after counts).

## Manual verification you can do (no browser)

Start Mongo/Redis (`docker compose up -d`), the backend with the dev profile, and `npm run dev`. Then:
- `curl -v https://localhost:5173/` **without `-k`**: the TLS handshake must succeed (the cert is trusted). Show the relevant lines.
- `curl -v -X POST https://localhost:5173/auth/refresh -H "Origin: https://localhost:5173"`: it's proxied (expect 401 JSON from the backend), and there's no `Access-Control-Allow-Origin` / `Vary: Origin` from Vite.
- `curl -v http://localhost:5173/` fails (no http fallback).
- Don't log in with Gal's credentials. Gal does the browser check: login → refresh → reload → refresh, all 200, in **Chrome and Safari**. At the end, give him short step-by-step instructions for it (terminal commands + where to look in DevTools / Web Inspector).

## Docs

- **README local setup:** mkcert install + `mkcert -install` + the cert npm script, the new `https://localhost:5173` URL, the backend dev-profile run command, and what the missing-cert error means.
- **`frontend/.env.example`:** only if something there references `http://localhost:5173` or needs a change. Say if unchanged.
- **CLAUDE.md frontend paragraph:** dev runs on HTTPS with a git-ignored per-machine mkcert cert, there's no http fallback, the cookie is never weakened for dev, the backend honors forwarded headers only under the `dev` profile, and Vite `server.cors` is off. Short. Update any line that says `http://localhost:5173`.
- **`docs/spec.md`: do NOT edit it.** List in the summary every place that needs updating, with line numbers: at least §10 and §11, and the KAN-46 note that Safari fails on http (§02 around line 68). Grep the spec for `localhost:5173`, `http://localhost`, `Safari` and `cors`.

## Commits

Small, focused commits, for example:
- `chore(frontend): serve the dev server over HTTPS with a local mkcert certificate (KAN-56)`
- `feat(backend): honor forwarded headers under the dev profile only (KAN-56)`
- `chore(frontend): turn off Vite's dev-server CORS headers (KAN-56)`
- `docs: document the HTTPS local setup (KAN-56)`

Put the CLAUDE.md change in the commit that introduces the rule (rule 7). Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. Installed versions you verified (Vite, Vitest, Boot) and the evidence for each "verify" item: the `server.https` / `server.cors` shapes, how the Vite 8 proxy handles `xfwd`, and the Boot 4.1.1 forward-headers property and default.
3. The `Location` header before and after (actual curl output), the throttle key with and without the profile, and the spoof-guard test.
4. Proof that `npm test` and `npm run build` pass with no cert present, and the missing-cert error message from `npm run dev`.
5. The curl checks above, with output.
6. Test counts before/after and full build results (frontend + backend).
7. Spec places to update (section + line + what's now wrong).
8. Deviations from this prompt, and why.
9. Open ends and risks. Include what KAN-53 (Playwright in CI) will need for the cert, and whether the backend run without the dev profile still works through the proxy (only `Location` affected?).
10. Gal's step-by-step browser check (Chrome + Safari).
11. Print the full Vite config, the dev-profile properties file, and the backend test.
