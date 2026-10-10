# KAN-57: Triage the npm audit high-severity findings in the frontend lockfile

**Credit budget is tight.** Read only the files this prompt names, plus whatever you need to answer a "verify" item or to trace a finding's dependency path. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `2240d47`, PR #44). If it doesn't, stop and report. Then create the branch `chore/KAN-57-npm-audit-triage`.

## Context

Jira KAN-57 (Low, label `security`, relates to KAN-42). While verifying KAN-48 from a clean checkout, `npm ci` in `frontend/` reported **8 high-severity** `npm audit` findings. KAN-48 added no dependencies, so they come from the lockfile on master. Nobody has triaged them. A permanent "8 high" teaches everyone to ignore `npm audit`, so a real runtime finding would slip by; that's the problem this ticket fixes.

The goal is **triage**, not "make the number zero at any cost": every finding is either fixed or accepted with a written reason, and **no runtime (browser-bundle) high-severity finding is left unaddressed**.

Facts I checked on master `2240d47` (re-check what you rely on):

- The "8 high" count is from KAN-48 (2026-10-06) and is probably **stale**: Playwright 1.64 (KAN-53) and other dev tooling were added since. In KAN-45 the agent reported 7 dev-only advisories from the shadcn CLI chain and **0 with `--omit=dev`**. Measure; don't rely on either number.
- `frontend/package-lock.json`: lockfileVersion 3, 641 packages, only **28 non-dev** (runtime): `react`/`react-dom` 19.3.0, `react-router` 8.4.0, `@base-ui/react` 1.8.0 (+ `@base-ui/utils`, `@floating-ui/*`, `reselect`, `use-sync-external-store`, `@babel/runtime`), `@tanstack/react-query` 5.103.1, `i18next` 23.16.8, `react-i18next` 15.7.4 (+ `html-parse-stringify`, `void-elements`), `zustand` 5.0.15, `lucide-react` 1.52.0, `class-variance-authority` 0.7.1, `clsx` 2.1.1, `cn` 0.4.0, `tw-animate-css` 1.4.0, `@fontsource-variable/heebo` 5.3.0, `@remix-run/route-pattern`, `cookie-es`, `scheduler`. Everything else is dev.
- `package.json` has **no** `overrides` block today.
- CI (`.github/workflows/ci.yml`): frontend-ci runs `npm ci`, lint, `format:check`, test, build on Node 22; the `e2e` job runs `npm ci` + `npm run e2e`. No audit step anywhere.
- **`cn` (runtime dependency, 0.4.0):** `src/lib/utils.ts` is `export { cn } from "cn";` and 9 files in `src/components/ui/` import from `"cn"`; there is no `clsx` + `tailwind-merge` helper. I checked the shadcn source at tag `shadcn@4.21.1`: the CLI itself depends on `"cn": "^0.2.4"` (imports `twMerge` from it), and ships an official `shadcn migrate cn` that replaces `clsx` / `tailwind-merge` / `cnfast` with `cn`. So this is shadcn's own pattern, **not** a stray package; **we keep it**. The lockfile also has `cn` 0.2.6 nested under `shadcn` and `@shadcn/registry` (dev). What I could **not** verify (registry blocked on my side): the publisher and provenance of `cn` on npm. That's a verify item below.

Read first: `CLAUDE.md` (rules), `frontend/package.json`, `.github/workflows/ci.yml` (frontend-ci and e2e jobs only). Other files only as a finding's dependency path points at them.

## Verify against what's installed, not memory

For each item, say in the summary how you checked (command + relevant output):

- Your local `node --version` and `npm --version`, and that `npm audit`'s output format/flags you use (`--json`, `--omit=dev`, `--audit-level`) work in that npm version. CI uses Node 22; note if your npm differs from what Node 22 ships.
- For **every** version you propose (a patched transitive, a direct-dependency bump, an `overrides` target): that it exists on the registry (`npm view <pkg>@<version> version`), that it is outside the advisory's vulnerable range (from the advisory itself, not memory), and that it satisfies (or knowingly replaces) the range declared by the parent that pulls it in (`npm view <parent>@<installed> dependencies`). An `overrides` that forces a version **outside** the parent's declared range is a semver jump for that parent; call it out as such.
- `cn`: publisher/maintainers and repository (`npm view cn maintainers repository dist`), and whether the 0.4.0 tarball has a verified signature / provenance attestation (`npm audit signatures` covers the whole tree; report its result for `cn` and overall). One line on what `cn` 0.4.0 actually ships into the bundle (its `main`/`exports` and dependencies). If anything looks wrong (unknown publisher, no link to the shadcn ecosystem, unexpected install scripts), **stop and report**; don't replace it.
- Whether `npm audit` itself treats `devOptional` / `peer` packages as dev for `--omit=dev` in your npm version (so the runtime/dev split you report is the tool's, cross-checked against the lockfile's `dev` flags).

## Step 1: Measure on master, before changing anything

In `frontend/`, after `npm ci`:

```
npm audit
npm audit --json > <outside the repo>/audit-before.json
npm audit --omit=dev
npm audit signatures
```

Keep the JSON outside the repo (or in a git-ignored folder; check `.gitignore`). **Don't commit audit output.**

Produce a table of **every** finding of any severity, high and critical first, then moderate/low grouped briefly:

| Package (installed) | Severity | Advisory (GHSA id + title) | Vulnerable range | Dependency path(s) | Runtime or dev | Proposed action |

- "Dependency path" = the full chain from a direct dependency (`npm ls <pkg> --all` or `npm explain <pkg>`). A package can be on several paths; list each.
- "Runtime or dev" comes from the lockfile flags **and** from `--omit=dev`, and both must agree. For a dev-only finding also say **which tool** pulls it in (shadcn CLI, Vite, Vitest, ESLint, Playwright, MSW, ...) and whether that tool runs in CI, only locally, or only when someone adds a shadcn component.

## Decisions (agreed with Gal, implement these)

### 1. Runtime findings: always fixed

- Any finding on a runtime path (in the browser bundle) is fixed in this ticket.
- If the only fix is a **major** upgrade of a direct dependency (e.g. `i18next` 23 → 24+, `react-i18next` 15 → 16+), **stop before changing it** and report: the advisory, whether the vulnerable code is actually reachable from our use (with evidence, e.g. the vulnerable function and a grep of our usage), the major's breaking changes from its changelog, and the size of the change. We'll decide together.

### 2. Dev-only findings: fix if cheap and safe, otherwise accept with a reason

In this order of preference:

1. A patched version **within** the parent's declared range: `npm update <pkg>` (or a targeted lockfile refresh) so only that package moves.
2. A minor/patch bump of the **direct** dev dependency that pulls it in.
3. An `overrides` entry in `package.json`, only when the forced version is inside (or trivially compatible with) the parent's range. Scope it as narrowly as npm allows (nested under the parent when possible, not a global override). Add nothing else to `overrides`.
4. Otherwise **accept**, with a one-to-two-line reason that's specific, not generic: which tool, when it runs, why the vulnerable path isn't reachable in how we use it (e.g. "ReDoS in X's CLI argument parser; only runs when a developer runs `npx shadcn add` with trusted input; not in CI or the bundle"), and what would close it (e.g. "fixed when shadcn bumps Y").

Rules:

- **No `npm audit fix --force`.** No **major** version jump of anything (direct or via `overrides`) without stopping to ask first.
- Don't change versions of anything that isn't tied to a finding. The lockfile diff should be explainable line by line by the table.
- Use `npm install` / `npm update` to change the lockfile, never hand edits. After the last change, `rm -rf node_modules && npm ci` must succeed from the committed lockfile.

### 3. Tools that tests don't exercise must still work

The frontend tests and build don't run the shadcn CLI, and only `npm run e2e` runs Playwright. So:

- If any change touches a package in the **shadcn CLI** tree (`shadcn`, `@shadcn/registry`, and their deps): show the CLI still runs, e.g. `npx shadcn --version` **and** `npx shadcn add button --dry-run` (or this version's equivalent of a no-write check; verify the flag exists with `npx shadcn add --help`). It must not modify any file; confirm with `git status --porcelain`.
- If any change touches the **Playwright** or **Vite** tree: run `npm run e2e` locally (needs the stack per README "End-to-end tests"), and report the result.
- If any change touches **ESLint** / **Prettier** / **TypeScript**: lint, format check and build already cover it.

### 4. `cn` stays

Don't replace or wrap `cn`. Only the verify item above, plus a line in the summary. If `cn` 0.4.0 has an advisory, treat it as a runtime finding (decision 1).

### 5. No CI change in this ticket

Don't add an audit step to CI or change `.github/`. In the summary, recommend (for KAN-42) whether a gate such as `npm audit --omit=dev --audit-level=high` in frontend-ci is a good idea **given what you found**: would it be green today after your changes, how often runtime advisories would block unrelated PRs, and whether it should be Required. One short paragraph; the decision is made in KAN-42.

## After the changes

- `rm -rf node_modules && npm ci` (no warnings beyond what master already had; list any), then `npm run lint`, `npm run format:check`, `npm test`, `npm run build`, as in CI. Test counts before and after (expected: identical).
- `npm audit`, `npm audit --omit=dev`, `npm audit signatures` again. Report **before → after** per severity, for the full tree and for `--omit=dev`.
- The same table as in step 1, with the final status of each finding: **fixed** (how, from → to version) or **accepted** (the reason). This table goes, as-is, into the PR description and into the Jira closing comment, so write it to be read by someone who didn't see the run.
- Bundle sanity: the runtime package list (non-dev entries in the lockfile) before vs after. If any runtime package changed version, say which and why; compare `dist/` total JS size before vs after (from the build output). Expected: unchanged unless decision 1 applied.

## Docs

- **`docs/spec.md`: do NOT edit it.** Grep sections 10 and 11 and the README for `audit`, `Dependabot`, `vulnerab`, `dependenc` and confirm explicitly in the summary: either "nothing outdated by this ticket" or each place (file + section + line + what's wrong). Note: spec section 10 (line ~284) and README line 77 claim "Dependabot in CI"; that's known and owned by KAN-42, so just say whether this ticket changes anything about it (it shouldn't).
- **CLAUDE.md:** only if you added an `overrides` entry, add one short sentence in the same dense style saying why it exists and when it can be removed (e.g. "remove once <parent> depends on <pkg> ≥ x.y"), so nobody deletes it blindly or keeps it forever. Same commit as the `overrides` change (rule 7). If there's no `overrides`, don't touch CLAUDE.md.

## Commits

Small, focused commits, for example:

- `chore(frontend): update <pkg> to <version> for <GHSA-id> (KAN-57)`
- `chore(frontend): override <pkg> under <parent> for <GHSA-id> (KAN-57)` (+ CLAUDE.md)

If nothing needs a change (everything accepted), there's no code commit; say so, and we'll still record the triage in Jira and decide whether a PR is needed. Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Branch, commits (hash + message), files changed, one line each (or "no changes").
2. Node/npm versions and the evidence for each "verify" item, including the `cn` provenance result.
3. **Before** (master `2240d47`): counts per severity, full tree and `--omit=dev`; `npm audit signatures` result.
4. The findings table with final status (fixed: how and from → to; accepted: the specific reason). Ready to paste into the PR and Jira.
5. Every version you proposed, with the evidence that it exists, is outside the vulnerable range, and fits the parent's range (or the explicit call-out that it doesn't).
6. Tool checks from decision 3 (shadcn CLI, e2e) with output, or why they weren't needed.
7. **After**: counts per severity, full tree and `--omit=dev`; signatures; runtime package list and bundle size before vs after.
8. lint / format / test (counts before/after) / build results; `npm ci` warnings.
9. Recommendation for KAN-42 (decision 5).
10. Spec / README: "nothing outdated" or the places.
11. Any point where you stopped for approval (decision 1 or a major jump), with the evidence.
12. Deviations from this prompt, and why.
13. Open ends and risks (e.g. accepted findings that need a later re-check, an `overrides` that will go stale).
14. Print in full: the `package.json` diff, and the `package-lock.json` diff **summarized** as package → old version → new version (not the raw diff).
