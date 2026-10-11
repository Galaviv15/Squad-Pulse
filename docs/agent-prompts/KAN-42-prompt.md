# KAN-42: Add Dependabot (GitHub Actions version updates + Maven/npm security-update config)

**Credit budget is tight.** Read only the files this prompt names, plus GitHub's documentation for the "verify" items. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

**Rule 1 note:** this ticket touches CI config (`.github/`). Gal has approved the change described below. Don't go beyond it.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `8d77c25`, PR #45). If it doesn't, stop and report. Then create the branch `chore/KAN-42-dependabot`.

## Context

Jira KAN-42 (Low; relates to KAN-40, KAN-57, KAN-63). Nothing watches the versions of the GitHub Actions our CI uses. KAN-40 bumped `actions/checkout` / `setup-java` / `setup-node` only after deprecation warnings had already appeared. `.github/` has only `workflows/ci.yml`; there is no `dependabot.yml`. Meanwhile the README (Security section) and `docs/spec.md` section 10 both claim "Dependabot in CI", which is false today.

Facts I checked on master `8d77c25` (re-check what you rely on):

- `.github/workflows/ci.yml` has three jobs: `backend-ci`, `frontend-ci` (both should be required checks) and `e2e` (KAN-53, not required). It runs on `pull_request` to `master` and `push` to `master`, with `permissions: contents: read` and no secrets. The `e2e` job writes a throwaway `.env` with `openssl rand`. The actions used: `actions/checkout@v7`, `actions/setup-java@v6`, `actions/setup-node@v7`, `actions/cache@v6`, `actions/upload-artifact@v7`. All are pinned by major tag, not by SHA.
- The backend is Maven (`backend/pom.xml`, with a Maven wrapper). The frontend is npm (`frontend/package.json` + `package-lock.json`). `scraper/` has a `package.json` but **no lockfile** and is still a skeleton.
- README, `## Security` section: the line `- CORS restricted, rate limiting via Redis, input validation on every endpoint, Dependabot in CI`. Two claims on it are false. "Dependabot in CI" is false today. "CORS restricted" is also wrong: the backend has **no** CORS configuration at all, because the app is same-origin (spec section 10, KAN-45 / KAN-56).
- README `## CI` section: describes the three jobs and branch protection. It doesn't mention Dependabot.
- README and CLAUDE.md "Git & Jira" sections: "always include the ticket key" in branches, commits and PRs.

Read first: `CLAUDE.md` (rules), `.github/workflows/ci.yml`, and in `README.md` only the `## Security`, `## Git & Jira workflow` and `## CI` sections.

## Decisions (agreed with Gal, implement these)

### 1. `.github/dependabot.yml`

One file, `version: 2`, three `updates` entries:

**a. `github-actions`**, directory `/`:
- Monthly schedule.
- `open-pull-requests-limit`: a small number (2).
- All updates in **one group** (a single PR per run), covering major, minor and patch.
- Commit message: Conventional Commits `ci(deps): ...`.
- No custom labels, assignees or reviewers.

**b. `maven`**, directory `/backend`:
- `open-pull-requests-limit: 0`. That means **no** version updates; the entry exists only so that **security-update** PRs use our commit-message format.
- Commit message `build(deps): ...`.
- A schedule if the schema requires one (monthly).
- No grouping of security updates: one PR per advisory, easier to review.

**c. `npm`**, directory `/frontend`: same as `maven`.

**Don't** add `scraper/`. It has no lockfile and is still a skeleton; it gets added with the first Phase 5 ticket. Add a short YAML comment in the file saying so, so nobody thinks it was forgotten. Keep comments in the file short.

### 2. No Jira key on Dependabot PRs: accepted, documented

Dependabot PRs, branches and commits carry no ticket key. That's the one exception to the convention. Document it (see Docs). Don't add any automation to inject a key.

### 3. Nothing else in `.github/`

Don't change `ci.yml`. Don't add an `npm audit` step (that's KAN-63). Don't pin actions to SHAs.

## Verify against reality, not memory

`dependabot.yml` options change over time. Check each item below against **GitHub's current documentation** ("Dependabot options reference", and "Configuring Dependabot security updates"), not memory. In the summary, for each item, give the doc page/section and what it says (paraphrased, one line):

1. The exact keys and allowed values you use: `version`, `updates`, `package-ecosystem` values (`github-actions`, `maven`, `npm`), `directory`, `schedule.interval`, `open-pull-requests-limit`, `groups` (and whatever key selects "all dependencies" / which update types), `commit-message` (`prefix`, `include: "scope"`).
2. **The key assumption of decision 1b/1c:** that `open-pull-requests-limit: 0` disables version updates for that ecosystem **while security-update PRs still use that entry's settings** (commit message in particular). If the docs say otherwise, or don't say it clearly, **stop and report** before committing the file. Don't invent a workaround.
3. What commit message / PR title `prefix: "ci"` (or `"build"`) with `include: "scope"` actually produces (`ci(deps): bump ...`?). The docs describe how the colon and the scope are added. Make sure the result is valid Conventional Commits. If it isn't exactly `type(deps): ...`, say what it will be.
4. That `directory: "/"` for `github-actions` covers `.github/workflows/*.yml`.
5. That `groups` on the `github-actions` entry applies to **version** updates only, and what that means for security updates on that ecosystem (one line).
6. Whether Dependabot PRs trigger `pull_request` workflows with a read-only `GITHUB_TOKEN` and no repository secrets, and whether anything in our `ci.yml` (incl. `e2e` and `actions/cache`) would break under that. Expected: nothing breaks, since we use no secrets and request only `contents: read`.
7. Validate the file's syntax locally. Use a JSON-schema check against the SchemaStore schema for `dependabot.yml` (e.g. `npx --yes check-jsonschema`-equivalent or `npx --yes ajv-cli`; use whatever actually works on this machine), and say which tool and schema URL you used. A YAML-parse check alone isn't enough. If no schema validation works, say so; the real check happens on GitHub after merge.

## Docs (rule 7: README and CLAUDE.md in the same commit as the config)

- **README `## Security`:** replace the line `- CORS restricted, rate limiting via Redis, input validation on every endpoint, Dependabot in CI` with accurate statements:
  - No cross-origin access: same-origin app, no CORS configuration. One short clause; point to spec section 10, don't re-explain.
  - Rate limiting via Redis and input validation on every endpoint stay as they are.
  - Dependabot as actually set up: security alerts and security updates for the backend (Maven) and frontend (npm), enabled in the repository settings, with commit messages set by `.github/dependabot.yml`; monthly grouped version updates for the GitHub Actions in CI; no routine version updates for Maven/npm; `scraper/` not covered yet.

  Keep it as short as the surrounding bullets.
- **README `## CI`:** one short paragraph about Dependabot. Dependabot PRs target `master`, so they run the same jobs and need the same required checks. `runs-on` labels aren't tracked by Dependabot, so the runner-image move (KAN-41) stays manual.
- **README `## Git & Jira workflow`** and **CLAUDE.md `## Git & Jira conventions`:** one bullet in each. Dependabot's PRs, branches and commits are the one exception to the ticket-key rule. Their commits are `ci(deps)` / `build(deps)`.
- **`docs/spec.md`: do NOT edit it.** Gal is preparing the updated file himself (rule 7). In the summary, list every place in the spec that this change makes inaccurate or that should mention it (section + quoted phrase + what's wrong). Search at least `Dependabot`, `CORS`, `vulnerab`, `ticket key` / `ticket number`, and the CI paragraph in section 11. I know about section 10 "Additional protections" ("automated dependency vulnerability scanning (Dependabot) in CI") and section 12 (branches always carry the ticket number); confirm those and report anything else.

## Checks before committing

- `git diff --stat` shows only `.github/dependabot.yml`, `README.md` and `CLAUDE.md`.
- The schema validation from verify item 7.
- No test run is needed (no code changes). Say so explicitly rather than running the suites.

## Commits

Small, focused, for example:

- `ci: add Dependabot config for actions updates and security-update commit format (KAN-42)`, with `.github/dependabot.yml` + README + CLAUDE.md (rule 7: docs in the same commit).
- If the README `CORS` correction is unrelated enough to keep separate: `docs: correct the CORS and Dependabot claims in the README (KAN-42)`. Your call; say which you chose.

Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Branch, commits (hash + message), files changed, one line each.
2. The full final `.github/dependabot.yml`, printed.
3. Evidence for each verify item 1–7 (doc page + one-line paraphrase, or command + relevant output). Explicitly: **is the `open-pull-requests-limit: 0` assumption confirmed by the docs, yes or no?**
4. The exact commit message / PR title format Dependabot will produce for each ecosystem.
5. README and CLAUDE.md diffs, printed in full (they're short).
6. Spec: every outdated place (section + quoted phrase + what's wrong), for Gal's spec update.
7. What can only be confirmed on GitHub after merge (e.g. Insights → Dependency graph → Dependabot shows the three ecosystems; the first actions-update run), so Gal can check it.
8. Deviations from this prompt, and why.
9. Open ends and risks. Include at least: actions pinned by major tag rather than SHA (one line on the trade-off; not to be changed here), and anything about the docs that didn't match your expectations.
