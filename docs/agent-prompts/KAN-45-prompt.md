# KAN-45: Frontend infrastructure — ESLint 9, router, shadcn/ui, theme, Vite proxy, Query provider, MSW

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `1a9774c` (the KAN-35 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-45-frontend-infra`.

## Context

This is Jira KAN-45, the first implementation ticket of epic **KAN-43 "Frontend MVP"** (Phase 3). It is blocked by KAN-44 (the design direction, now approved) and it blocks KAN-46 (API client + session). Every later frontend ticket (KAN-46 auth/API client, KAN-47 auth screens, KAN-48 app shell, KAN-49 squad table, KAN-50 player card/form, KAN-51 dashboard, KAN-52 squad cards view) builds on what you lay down here. **No product screens in this ticket**: only a placeholder page, a not-found page, and the plumbing.

Epic decisions that constrain this ticket (from KAN-43, already agreed with Gal):
- **Same-origin, no CORS.** In dev, the Vite dev server proxies the backend. The backend has no `/api` prefix and no CORS config, and we're not adding either.
- **All SPA routes live under `/app/...`.** The backend's own prefixes are `/auth`, `/squad`, `/clubs`, `/users`. If SPA routes shared those prefixes, a browser reload would go to the backend. Keeping the SPA under `/app` avoids that.
- **The refresh cookie** (`AuthController.refreshCookie`) is `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/auth`, with **no `Domain` attribute**. The proxy must not rewrite paths, so `Path=/auth` keeps matching. (Whether browsers store a `Secure` cookie over `http://localhost` is verified in KAN-46, not here.)
- **Tests use MSW**, no Playwright yet.
- **Desktop-readiness:** the API base URL is one env-driven setting, empty by default (same-origin relative paths).

Read these first:
- `CLAUDE.md` (especially rule 2 English vs Hebrew, rule 3 football terms stay English, rule 6 i18n, rule 7 docs in the same commit).
- `docs/spec.md` section 01 (RTL is a first-class requirement: logical properties, every shadcn component checked under RTL) and the frontend tech-stack lines (around lines 52 and 68). **Read only. Don't edit spec.**
- README: the "CI" section (frontend-ci) and the local-setup step for the frontend (around line 114).
- `.github/workflows/ci.yml`, job `frontend-ci` (Node 22: `npm ci`, `lint`, `format:check`, `test`, `build`). **Don't change CI** unless something forces it. If it does, stop and ask first.
- Everything under `frontend/`: `package.json`, `package-lock.json`, `.eslintrc.cjs`, `.prettierrc`, `vite.config.ts`, `tailwind.config.js`, `postcss.config.js`, `tsconfig*.json`, `index.html`, `src/**`.
- `backend/src/main/resources/application.yml` (`server.port: 8080`) and `AuthController.refreshCookie` (to confirm the cookie attributes above).

## Facts I checked on master `1a9774c` (re-confirm each one against what's installed — don't take them on faith)

- `frontend/package.json`: React 19, Vite `^8.0.0` (lock: **8.3.0**), Vitest `^5.0.0` (lock: **5.0.1**), Tailwind `^3.4.13`, TanStack Query 5, Zustand 5, i18next 23, ESLint `^8.57.1`, `eslint-plugin-react-hooks` `^4.6.2`, `eslint-plugin-react-refresh` `^0.4.12`, `@typescript-eslint/*` `^8.8.0`, jsdom, Testing Library, Prettier 3. No router, no shadcn, no MSW.
- The `lint` script is `eslint . --ext ts,tsx --report-unused-disable-directives --max-warnings 0`. **`--ext` is not supported with flat config in ESLint 9**: it must go, and file matching moves into `eslint.config.js`. Keep `--report-unused-disable-directives` (or its flat-config equivalent, `linterOptions.reportUnusedDisableDirectives`) and `--max-warnings 0`.
- **`eslint-plugin-react-hooks` 4.6.x has no flat-config export.** It needs a version that does. Check which, and what its flat preset is called in the installed version.
- `.eslintrc.cjs` today: `eslint:recommended`, `plugin:@typescript-eslint/recommended`, `plugin:react-hooks/recommended`, plugin `react-refresh` with `react-refresh/only-export-components: ["warn", { allowConstantExport: true }]`, ignores `dist`. Port exactly these, no new rule sets.
- `format` / `format:check` run Prettier on `src/**/*.{ts,tsx,css}`. **That includes any files shadcn generates under `src/`.** They must be Prettier-formatted, or CI fails.
- `tsconfig.json` has no `baseUrl` / `paths` and `vite.config.ts` has no `resolve.alias`. shadcn needs an import alias (`@/` → `src/`) in both, and in `components.json`.
- `vite.config.ts` imports `defineConfig` from `vitest/config` and carries the `test` block (jsdom, `src/test/setup.ts`). Keep a single config unless the installed versions force a split.
- `index.html` already has `lang="he" dir="rtl"`.
- `src/App.tsx` is a placeholder with hard-coded Tailwind palette colors (`bg-neutral-50`, `text-emerald-800`). `src/App.test.tsx` tests that placeholder. Both get replaced.
- `src/index.css` contains only the three `@tailwind` directives. There is no existing styling to migrate.
- Backend: `server.port: 8080`.
- My sandbox can't reach the npm registry, so **I did not check any current package version.** Every version claim below that says "verify" is yours to check, against the registry, the package's own docs/changelog, and what ends up in `package-lock.json`.

## Decisions agreed with Gal (implement exactly these)

### 1. ESLint 9+ with flat config

- Replace `.eslintrc.cjs` with `eslint.config.js` (ESM, matching `"type": "module"`). Delete `.eslintrc.cjs`.
- Use the current `eslint` major (9 or newer, whatever is current and supported by every plugin below) and `typescript-eslint` (the unified package, flat config). Remove the separate `@typescript-eslint/parser` / `@typescript-eslint/eslint-plugin` packages if the unified package replaces them.
- Upgrade `eslint-plugin-react-hooks` and `eslint-plugin-react-refresh` to versions with flat-config support. **Check each plugin's `peerDependencies` against the ESLint version you pick.** Don't use `--legacy-peer-deps` or `overrides` to force anything. If a plugin doesn't support the ESLint major you want, pick the newest ESLint it does support, and say so.
- Port the rules 1:1 (see Facts). If the new `react-hooks` preset adds rules that weren't in 4.6 (newer versions added React Compiler-related rules), **report which ones** and whether the existing code passes them. Keep the preset the plugin recommends unless a rule fires on shadcn-generated code (see decision 3).
- `npm ci` must no longer print the ESLint 8 EOL / deprecation warning. Report the full `npm ci` warning output before and after.

### 2. Router: React Router (current major), library mode

- `react-router` (verify the current major and its React 19 support; in v7 the package is `react-router`, and `react-router-dom` is only a re-export). Use the **data router** (`createBrowserRouter` + `RouterProvider`). **Not** framework mode, no file-based routing, no Vite plugin from React Router.
- Routes:
  - `/` → redirect to `/app`.
  - `/app` → a placeholder page (see "Placeholder page" below).
  - anything else (including `/app/whatever`) → a not-found page with a link back to `/app`.
- Put the route table in one module (e.g. `src/app/router.tsx`) so KAN-48 can add the shell layout as a parent route under `/app`.
- All visible text goes through i18n (rule 6), in `src/i18n/locales/he.json`.

### 3. shadcn/ui and Tailwind: **Tailwind 4**, with a stop condition

Decision: **Tailwind CSS 4**, since the current shadcn CLI targets it and there's no existing styling to migrate. Before you install anything, verify:
- What the **current** shadcn CLI and docs actually require for Vite + Tailwind 4 (the `@tailwindcss/vite` plugin vs PostCSS, `@import "tailwindcss"`, `@theme inline`, the animation package it now uses (`tw-animate-css` or whatever is current), `components.json` fields).
- **`@tailwindcss/vite`'s `peerDependencies` include Vite 8** (we have 8.3.0). **If they don't, stop and report** with what you found (the options are: Tailwind 4 via `@tailwindcss/postcss`, or staying on Tailwind 3 with a pinned shadcn CLI version). Don't pick one yourself, and don't force the install.
- Remove Tailwind 3 artifacts that Tailwind 4 doesn't use (`tailwind.config.js`, `autoprefixer`, `postcss.config.js`) **only if** the setup you end up with really doesn't need them. Say what you removed and why.

shadcn setup:
- Run the CLI's `init` (via `npx shadcn@<exact version>`; report the exact version). Base color: whatever is closest, since our tokens replace it anyway. CSS variables: yes. Alias `@/` → `src/`, wired in `tsconfig.json` (and `tsconfig.app.json` if the CLI expects it; keep our existing tsconfig layout unless forced), `vite.config.ts` (`resolve.alias`) and `components.json`.
- **RTL:** check whether the current shadcn CLI / `components.json` has an RTL option that generates logical classes (`ms-*`/`me-*`/`ps-*`/`pe-*`/`start-*`/`end-*`/`text-start`). I believe recent versions added one, but I'm not sure. **Verify it in the CLI's docs/changelog/source.** If it exists, enable it and say where it's documented. Either way, after generating, **grep every generated component for physical-direction classes** (`ml-`, `mr-`, `pl-`, `pr-`, `left-`, `right-`, `text-left`, `text-right`, `rounded-l`, `rounded-r`, `border-l`, `border-r`, `space-x-` without `rtl:`/`space-x-reverse` handling, `translate-x` without RTL handling) and convert any you find to logical equivalents. Report the grep command and its final (empty) output.
- **Components to add now (minimal set):** `button`, `input`, `label`, `card`, `badge`. Nothing else. Each screen ticket adds what it needs and checks it under RTL.
- **Lint vs generated code:** generated files like `button.tsx` export a non-component (`buttonVariants`) next to the component, so `react-refresh/only-export-components` warns, and `--max-warnings 0` fails. Fix this with a **scoped override for `src/components/ui/**` only** in `eslint.config.js` (turn that one rule off there). Don't disable it globally, and don't add per-file `eslint-disable` comments. If any other rule fires on generated code, report it before overriding it.
- Run Prettier over the generated files. `format:check` must pass with our `.prettierrc`.
- `lucide-react` comes with shadcn (icons, per KAN-44). Verify it gets installed and report the version.

### 4. Theme tokens, font, and the conventions file

**Tokens:** write every token from the table in "Design conventions" below into the theme, in the format Tailwind 4 / current shadcn expects (CSS variables on `:root`, mapped through `@theme inline`). **The hex values are converted, not changed.** If you convert to OKLCH (current shadcn default), do it with a real conversion (e.g. the `culori` package as a dev-time script, not a runtime dependency) and **round-trip check**: convert each OKLCH value back to sRGB hex and confirm it matches the original hex exactly (or within ±1 per 8-bit channel, and then say which). Report a table: token, original hex, value written, round-trip hex. Keeping the hex values as-is in the CSS is also acceptable if Tailwind 4 / shadcn handle them correctly (opacity modifiers like `bg-primary/50` must work); say which you chose and show that `/50` works on one token.

- Custom tokens beyond shadcn's set: `success`, `success-muted`, `danger`, `danger-muted`, `sidebar`, `sidebar-foreground`, `sidebar-muted-foreground`, `sidebar-accent`, `sidebar-accent-foreground`, `chart-1`..`chart-4`. Each must be usable as a Tailwind color utility (`bg-success-muted`, `text-danger`, `bg-sidebar`, `fill-chart-2`, ...). If shadcn's own sidebar token names differ from these (current shadcn has `--sidebar-*` tokens of its own), **use the KAN-44 names and values**, keep shadcn's extra sidebar tokens only if a generated component references them, and report the mapping.
- `--radius: 0.5rem`, with `md` = 6px and `sm` = 4px derived from it (the shadcn pattern `calc(var(--radius) - 2px)` / `- 4px`; verify what current shadcn generates).
- **Light mode only.** Don't add a `.dark` block. If the shadcn init generates one, remove it. Every color must go through a CSS variable, so dark mode later is just a second set of values.
- Remove the old hard-coded palette classes (`bg-neutral-50`, `text-emerald-800`, ...). No raw Tailwind palette colors (`emerald-*`, `neutral-*`, `gray-*`, ...) in `src/` outside `components/ui`. Report a grep proving it.

**Font: Heebo, self-hosted.**
- Install from `@fontsource` (static `@fontsource/heebo`, weights 400/500/600/700; or `@fontsource-variable/heebo` if that's cleaner. Say which and why). Import only what's needed. No Google Fonts request anywhere (`grep -r "fonts.googleapis\|fonts.gstatic" frontend/` must be empty, excluding `node_modules`).
- **Verify the Hebrew subset is included**: check the generated `@font-face` rules' `unicode-range` covers U+0590–05FF, and that the Hebrew woff2 files end up in `dist/` after `npm run build`.
- **Verify `tnum`:** inspect the actual installed woff2 file(s) (the Latin subset, which contains the digits) with a font tool (e.g. `fontTools`: `pyftsubset` isn't needed; `python -c "from fontTools.ttLib import TTFont; ..."` listing GSUB `FeatureList` tags). Report the command and output. **If `tnum` is absent**, don't fake it: report it. Also check whether Heebo's default digits are already equal-width (compare advance widths of `0`–`9` in `hmtx`) and report that, because if they are, `tabular-nums` is moot but harmless.
- Set Heebo as the default `font-sans` in the theme (with a system fallback stack), and add a `tabular-nums` utility usage note to the conventions file (Tailwind's built-in `tabular-nums` class).

**Conventions file:** create `docs/design/ui-conventions.md` containing the "Design conventions" section below **verbatim** (it's the approved KAN-44 design, so later prompts can point at the repo instead of Jira), plus a short "Implementation notes" section at the end that you write: where the tokens live (file path), how to use the custom tokens as Tailwind classes, the font setup, the RTL rule for shadcn components, and the `tnum` finding. Don't change the approved text itself. If you find something in it that's technically impossible as written, report it instead.

### 5. Vite dev proxy

- In `vite.config.ts`, proxy exactly these prefixes to the backend: `/auth`, `/squad`, `/clubs`, `/users`. **No `rewrite`**, no `cookiePathRewrite`, no `cookieDomainRewrite` (the cookie has no `Domain` and its `Path=/auth` must reach the browser unchanged).
- Match whole path segments only: `/auth` must proxy `/auth` and `/auth/...` but not, say, `/authors`. Check how Vite's `server.proxy` keys match (plain prefix vs `^`-regex) in the installed Vite 8 and use what gives segment matching. Report how you verified it.
- `/app`, `/app/...`, `/`, and the Vite internals (`/@vite`, `/src`, `/node_modules`, ...) must **not** be proxied. A browser reload on `/app/anything` must get `index.html` from Vite (SPA fallback), not the backend.
- `changeOrigin`: decide based on what the backend needs (it has no host-based logic I know of). Say what you set and why.
- **Target from env:** read the backend URL in `vite.config.ts` via `loadEnv` from a variable named `SQUADPULSE_BACKEND_URL` (no `VITE_` prefix, so it's **not** exposed to client code), default `http://localhost:8080`. Document it in a new `frontend/.env.example` (no secrets). Confirm `.env.local` is gitignored (`*.local` already is; verify it covers it).
- `vite preview` does not need the proxy for this ticket. Say whether you configured `preview.proxy` too, and why.

### 6. API base URL (one setting, nothing else builds URLs)

- One module, e.g. `src/lib/config.ts`, exporting the API base URL read from `import.meta.env.VITE_API_BASE_URL`, **empty string by default** (so requests are same-origin relative paths like `/auth/login`). Strip a trailing `/` if set. Add a typed declaration for it (`src/vite-env.d.ts` `ImportMetaEnv`).
- Add a tiny helper, e.g. `apiUrl(path: string): string`, that joins the base and a path that must start with `/`, with a unit test (empty base, base with and without trailing slash, path without a leading slash → throws). This is the **only** place that builds API URLs. KAN-46 builds the client on top of it. **No fetch client in this ticket.**
- Document `VITE_API_BASE_URL` in `frontend/.env.example`: empty = same origin (dev proxy / production reverse proxy).

### 7. TanStack Query provider

- One `QueryClient` factory (e.g. `src/lib/queryClient.ts`, `createQueryClient()`), with these defaults:
  - **queries:** `retry` = a function: **no retry if the error carries an HTTP status in 400–499**, otherwise retry up to **2** times. KAN-46 will define the error type, so for now detect the status duck-typed (an object with a numeric `status` property) and document that KAN-46 must keep that contract or update this function. Keep TanStack's default retry delay.
  - **mutations:** `retry: 0`.
  - Leave other defaults (`staleTime`, `refetchOnWindowFocus`) at TanStack's defaults unless you have a concrete reason. If you change one, say why.
- Unit-test the retry function: 401 → no retry, 404 → no retry, 500 → retry while `failureCount < 2`, a plain `Error` (network failure, no status) → retry while `failureCount < 2`.
- `main.tsx` wraps the app as: `StrictMode` → `QueryClientProvider` → `RouterProvider`. i18n stays initialized as today.
- No React Query Devtools in this ticket.

### 8. Test setup ready for MSW

- Install `msw` (current major; verify Node 22 and jsdom compatibility in its docs, and that it works with Vitest 5 + jsdom as installed. jsdom has historically needed polyfills or a custom environment for MSW's `fetch`/`Response`/streams; check whether that's still true for the versions installed and do only what's actually needed).
- `src/test/msw/server.ts`: `setupServer()` with an **empty** default handler list, plus `src/test/msw/handlers.ts` exporting an empty array that KAN-46 fills.
- `src/test/setup.ts`: keep the jest-dom import. Add `beforeAll(() => server.listen({ onUnhandledRequest: "error" }))`, `afterEach(() => server.resetHandlers())`, `afterAll(() => server.close())`.
- A test helper, e.g. `src/test/render.tsx`, that renders a UI tree inside a **fresh** `QueryClient` per test (with `retry: false` for queries; this is the only place retry is turned off) and a memory router (`createMemoryRouter` with initial entries). Later tickets use this helper.
- One smoke test proving MSW intercepts: a test that registers a handler with `server.use(http.get("/clubs/me", ...))` (relative URL, as the app will call it), calls `fetch(apiUrl("/clubs/me"))`, and asserts the mocked JSON. **If relative URLs don't resolve under jsdom's `fetch`**, find out why (jsdom's `location.href` / Vitest's `environmentOptions.jsdom.url`), fix it properly in config, and report what you did. Also one test proving `onUnhandledRequest: "error"` actually fails a test that calls an unmocked endpoint (e.g. via `expect(...).rejects`, not by leaving a red test in the suite).
- Do **not** add a browser MSW worker (`public/mockServiceWorker.js`). Dev runs against the real backend.

### Placeholder page and not-found page

- **`/app` placeholder:** a centered `Card` with the app name, a one-line welcome, and a phase note (reuse the existing i18n keys `app.name`, `app.welcome`, `app.phaseNote`), using theme tokens only (`bg-background`, `text-foreground`, `text-primary`, `text-muted-foreground`, `Card`), a `Button` (primary variant, can be inert or link to `/app`) and one `Badge` showing a position code (e.g. `CB`) in an LTR island (`dir="ltr"`), so the base components and RTL can be eyeballed in one place. No fake product data beyond that one badge.
- **Not-found page:** title + short text + a link/button back to `/app`, all via i18n.
- Tests (with the render helper): `/` redirects to `/app`. `/app` renders the placeholder (heading = `he.app.name`). `/nope` and `/app/nope` render the not-found page. The not-found link targets `/app`. The document direction is RTL (`index.html` is not loaded by Vitest, so test that the app doesn't override it, or skip this one and say why).

## Verify against what's installed (report evidence for each)

1. ESLint, `typescript-eslint`, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh` versions, and each plugin's `peerDependencies` vs the ESLint version.
2. React Router version, React 19 support, and that you used library/data mode.
3. shadcn CLI exact version, what its docs say for Vite + Tailwind 4, the RTL option (exists or not, with a source).
4. `tailwindcss` and `@tailwindcss/vite` versions and its Vite peer range (the stop condition in decision 3).
5. Token conversion table with round-trip check.
6. Heebo package + version, `unicode-range` Hebrew coverage, Hebrew woff2 in `dist/`, GSUB feature list (`tnum` yes/no), digit advance widths.
7. Vite proxy key matching semantics in Vite 8.3.x (segment matching), with how you verified (docs/source, or a test).
8. MSW version, and whether jsdom needed anything extra.
9. `npm ci` warnings before and after.

## Manual check (run it, report what you saw)

With the backend **not** running and then running (`docker compose up -d` + the backend on 8080; skip the "running" half if you can't start it, and say so):
- `npm run dev`, open `http://localhost:5173/` → lands on `/app`. Cream background (`#F6F4EE`), Heebo rendering Hebrew, text right-aligned, the `CB` badge reads left-to-right.
- Reload `http://localhost:5173/app/whatever` → the not-found page from Vite (no request to 8080; check the dev-server/proxy output and the browser network tab).
- `curl -i http://localhost:5173/auth/users/me` → proxied to the backend (with the backend running: its JSON 401; with it stopped: a proxy error, not `index.html`). `curl -i http://localhost:5173/authors` → **not** proxied.
- In the browser devtools, computed `font-family` on body text is Heebo, and the network tab shows the font loaded from the dev server, not Google.

## Docs (rule 7, same commits as the changes)

- **README:** update the frontend local-setup step (env vars `SQUADPULSE_BACKEND_URL` / `VITE_API_BASE_URL`, `frontend/.env.example`, the dev proxy and the `/app` route prefix, and that the backend must run on 8080 by default) and anything in the CI section that becomes inaccurate. Mention `docs/design/ui-conventions.md`.
- **CLAUDE.md:** add a short frontend paragraph: SPA routes under `/app`; the dev proxy prefixes (and that a new backend prefix must be added to the proxy); `apiUrl` is the only place API URLs are built; theme tokens only (no raw palette colors), logical properties only, every new shadcn component checked under RTL; ESLint flat config with the `components/ui` override; MSW `onUnhandledRequest: "error"`; tests render through the shared helper; design rules in `docs/design/ui-conventions.md`. Don't change the "Status" line except to note frontend infra is in place, if it becomes inaccurate.
- **`docs/spec.md`: do NOT edit it.** In your summary, list exactly what and where it needs updating (e.g. the frontend tech-stack line: Tailwind 4, React Router, shadcn version, ESLint 9; section 01's RTL paragraph if the shadcn RTL option changes the approach; anything about routes/proxy). Exact line numbers and the facts.

## Commits

Small, focused commits, for example:
- `build(frontend): migrate to ESLint 9 flat config (KAN-45)`
- `build(frontend): add Tailwind 4 and shadcn/ui base components (KAN-45)`
- `feat(frontend): add theme tokens and self-hosted Heebo (KAN-45)`
- `docs(design): add UI conventions from KAN-44 (KAN-45)`
- `feat(frontend): add router with /app placeholder and not-found page (KAN-45)`
- `feat(frontend): add dev proxy, API base URL config and Query provider (KAN-45)`
- `test(frontend): set up MSW and a shared render helper (KAN-45)`

Docs go in the same commit as the change they describe (rule 7). Stage files by path, never `git add -A`. `package-lock.json` is committed with the `package.json` change that caused it. **Do not push and do not open a PR.**

## Final checks

From `frontend/`, on Node 22 (what CI uses; report `node -v`): `rm -rf node_modules && npm ci && npm run lint && npm run format:check && npm run test && npm run build`. All green, zero lint warnings. Report each command's outcome, the test count, and the `dist/` size summary from `vite build`. Then `git status --porcelain` must be empty.

## Summary to return

1. Files changed / added / deleted, one line each.
2. Final `package.json` (full), and the version actually resolved in the lockfile for every package you added or upgraded.
3. Evidence for each "Verify against what's installed" item.
4. Manual-check results.
5. Test list with counts; final checks output.
6. Deviations from this prompt, and why.
7. The spec update list.
8. Open ends and risks (e.g. new react-hooks rules, anything in the shadcn RTL check you weren't sure about, `tnum`).
9. Print in full: `eslint.config.js`, `vite.config.ts`, `components.json`, the main CSS file (with the tokens), `src/main.tsx`, the router module, `src/lib/config.ts`, `src/lib/queryClient.ts`, `src/test/setup.ts`, the render helper, and `frontend/.env.example`.

---

## Design conventions (approved in KAN-44, 2026-10-05 — copy this section verbatim into `docs/design/ui-conventions.md`)

### Decisions

* **Look:** muted deep "pitch" green on a cream / off-white base. Professional, not game-like.
* **Light mode only** in the MVP. Every color still goes through CSS variables, so dark mode later is one more set of values.
* **Font: Heebo, self-hosted** via `@fontsource` (no Google Fonts request). Weights 400/500/600/700. Numbers use `font-variant-numeric: tabular-nums` (jersey numbers, ages, stats); KAN-45 must verify the installed Heebo build supports `tnum`, not assume it.
* **Mobile:** desktop/tablet first. On a phone nothing breaks (sidebar stacks, wide tables scroll horizontally), but it isn't polished.

### Theme tokens (shadcn/ui names, hex values)

Format-neutral on purpose: KAN-45 converts them to whatever its Tailwind 3 vs 4 decision requires (HSL triplets for Tailwind 3, OKLCH / `@theme inline` for Tailwind 4).

| Token | Value |
| --- | --- |
| `--background` | `#F6F4EE` |
| `--foreground` | `#1C2420` |
| `--card` / `--popover` | `#FFFEFB` |
| `--card-foreground` / `--popover-foreground` | `#1C2420` |
| `--primary` | `#2F5D4A` |
| `--primary-foreground` | `#FAF8F2` |
| `--secondary` / `--accent` | `#E6EDE8` |
| `--secondary-foreground` / `--accent-foreground` | `#23463A` |
| `--muted` | `#EEEBE3` |
| `--muted-foreground` | `#5E655E` |
| `--border` | `#E2DDD1` |
| `--input` | `#D6D0C2` |
| `--ring` | `#2F5D4A` |
| `--destructive` | `#B4462F` (foreground `#FFFFFF`) |
| `--success` / `--success-muted` (custom, "fit") | `#2F6B45` on `#E3EFE5` |
| `--danger` / `--danger-muted` (custom, "injured", error text) | `#9A3F27` on `#F6E3DC` |
| `--sidebar` / `--sidebar-foreground` | `#1E3A2F` / `#E8E4D8` |
| `--sidebar-muted-foreground` | `#A9B5AD` |
| `--sidebar-accent` / `--sidebar-accent-foreground` (active item) | `#F2EEE3` / `#1E3A2F` |
| `--chart-1` GK / `--chart-2` DEF / `--chart-3` MID / `--chart-4` ATT | `#C2841A` / `#0E7F5C` / `#8FB83F` / `#3A6DB8` |
| `--radius` | `0.5rem` (8px; md 6px, sm 4px) |

All text pairs were checked at ≥ 4.5:1. The chart palette passed a colorblind-separation check; `--chart-1` and `--chart-3` are under 3:1 against the card, so a chart must always carry a legend with the line name and count (never color alone).

### UI conventions

* **Shell:** dark green sidebar on the start side (right in RTL): club logo + name at the top, nav items, then a "בקרוב" group shown disabled (not links): לו״ז, אימונים, משחקים, לוח טקטי. Active item = cream pill. Top bar in the content area: page title (start), user avatar + name + title and a logout button (end).
* **Type scale:** page title 22/700, section 18/600, card title 16/600, body/table 14/400, labels 13/500, badges 12/500, KPI numbers 40/700.
* **Density:** controls 40px high, filter controls 36px, table rows 52px. 4px spacing base; page padding 32px, card padding 20–24px, gap between cards 20px.
* **Cards:** `--card` background, 1px `--border`, `--radius`, no shadows. A "coming soon" placeholder is a dashed `--input` border, transparent, a muted "בקרוב" pill, no fake data, not clickable.
* **Badges:** position codes as LTR chips in English (primary filled `--secondary`, secondary outlined); medical status as pills (fit = success, injured = danger); released = muted pill, and a released player's row or card is dimmed.
* **Dashboard (KAN-51):** KPI tiles (active players, average age) + a donut of `lines` with a legend (color, line name, count; no percentages) and the total in the center; then placeholder cards for next training, next match, league table, and a wide "לו״ז שבועי" placeholder with 7 empty day columns (ראשון–שבת).
* **Squad table (KAN-49):** status as a segmented control (פעילים / משוחררים / הכל), filter bar (position, age range, medical status, foot, clear), count of shown players, primary button "הוספת שחקן". Columns: #, name with avatar, position (primary + secondary), age, height, weight, foot, medical status, row actions menu.
* **Icons:** stroke icons (lucide, which ships with shadcn/ui), 16–18px.
* **RTL:** logical properties only; LTR islands (`dir="ltr"`) for position codes, formations and `#9`-style numbers.
