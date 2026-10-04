# KAN-40: CI maintenance (bump deprecated actions, pin the runner image)

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `201e4f1` (the KAN-38 merge). If it doesn't, stop and report. Then create the branch `chore/KAN-40-ci-maintenance`.

## Context

This is Jira KAN-40 (no epic). CI runs on PR #23 and PR #24 (runs 37200578448, 37214366229) passed but showed deprecation warnings that have nothing to do with those tickets:
- the actions run on Node 20, which is deprecated on GitHub-hosted runners;
- `actions/setup-java@v4` is deprecated;
- a notice that the `ubuntu-latest` label is moving to Ubuntu 26.04.

Both CI checks (`backend-ci`, `frontend-ci`) are **Required** in branch protection. A broken CI blocks every merge, so this ticket is about getting ahead of the deprecations in a controlled PR.

Read first:
- `.github/workflows/ci.yml` (the only workflow)
- README, section "CI"
- `CLAUDE.md` rule 1 ("touching CI/deploy config" is a consequential change; this prompt is the go-ahead for the edits below, but still do **not** push or open a PR)

**Facts I checked (on master `201e4f1` and on the actions' own repos, 2026-10-04). Re-confirm them; don't take them on faith:**
- `ci.yml` uses `actions/checkout@v4` (both jobs), `actions/setup-java@v4` (backend), `actions/setup-node@v4` (frontend), and `runs-on: ubuntu-latest` for both jobs. Job ids and `name:` are `backend-ci` and `frontend-ci`.
- Latest major versions (from `git ls-remote --tags` and each action's `action.yml` / README):
  - `actions/checkout` → **v7** (v7.0.1). `runs.using: node24`. v6 moved persisted credentials out of `.git/config`; v7 refuses fork-PR checkout under `pull_request_target` / `workflow_run` (we use neither).
  - `actions/setup-java` → **v6** (v6.0.1). `runs.using: node24`. README states v1–v4 are deprecated. Inputs `distribution`, `java-version`, `cache`, `cache-dependency-path` still exist. New in v6: when `cache` is set, the downloaded JDK is cached too (`cache-jdk`).
  - `actions/setup-node` → **v7** (v7.0.0). `runs.using: node24`. v5 enabled automatic caching; v6 limits auto-cache to npm projects declaring `packageManager`; v7 removed the dummy `NODE_AUTH_TOKEN` fallback (only matters with `registry-url`, which we don't use). Inputs `node-version`, `cache`, `cache-dependency-path` still exist.
- `actions/runner-images` README: `ubuntu-latest` currently = Ubuntu 24.04; `ubuntu-24.04` and `ubuntu-26.04` are both GA labels. The Ubuntu 26.04 image ships Docker 29.x (24.04 ships Docker 28.x). Our integration tests use Testcontainers against the runner's Docker.

## Decisions agreed with Gal (implement exactly these)

1. **Bump the three actions to their current major tag:** `actions/checkout@v7`, `actions/setup-java@v6`, `actions/setup-node@v7`. Use the floating major tag (as today), not a SHA pin. Before editing, re-check that these are still the latest majors and read each one's release notes for breaking changes that touch the inputs we use. If a newer major exists, stop and report instead of picking it on your own.
2. **Pin the runner: `runs-on: ubuntu-24.04` for both jobs.** Reason: predictability. The OS image changes only when we change it in a PR, never silently in the middle of an unrelated ticket. Add a short YAML comment above each `runs-on` (or one comment at the top of `jobs:`) saying it is pinned on purpose and that moving to 26.04 is a deliberate future change (Docker 29 on that image).
3. **Behavior unchanged.** Same triggers, concurrency, permissions, working directories, steps and commands. Backend: Temurin 21, `cache: maven`, `cache-dependency-path: backend/pom.xml`, `spotless:check` then `verify`. Frontend: Node 22, `cache: npm`, `cache-dependency-path: frontend/package-lock.json`, then npm ci / lint / format:check / test / build. Keep the explicit `cache:` inputs (don't rely on v5+/v6+ auto-detection).
4. **Required check names must not change.** Job ids and `name:` stay exactly `backend-ci` and `frontend-ci`. Branch protection matches on these names; a rename would leave the PR waiting forever on a check that never reports.
5. **Out of scope** (list under open ends if you think they matter, don't do them): changing the Node version (22) or JDK (21), SHA-pinning actions, adding Dependabot for `github-actions`, moving to Ubuntu 26.04, any change outside `.github/workflows/ci.yml` and README.

## Verify against reality

- Validate the YAML locally (e.g. `actionlint` if available, or at least a YAML parse). Report what you ran.
- Show `git diff master -- .github/workflows/ci.yml`: only the `uses:` versions, `runs-on`, and the new comment(s) may differ.
- CI itself can only be proven on GitHub, after the push (a later prompt). In this step, don't claim CI passes.

## Docs

- **README, "CI" section:** mention the pinned runner (`ubuntu-24.04`, and why), and the action majors only if the section already names versions (it doesn't today; don't add version noise).
- **CLAUDE.md:** change only if something there becomes inaccurate; say what.
- **`docs/spec.md`: do NOT edit it.** In your summary, say whether anything in it is affected by this change. (I expect nothing; spec mentions CI only generically.)

## Commits

One focused commit, e.g. `ci: bump actions to node24 majors and pin runner to ubuntu-24.04 (KAN-40)`, with the README change in the same commit (rule 7). **Do not push and do not open a PR.**

## Summary to return

1. Files changed, one line each.
2. The full final `ci.yml`, printed.
3. Evidence for the versions you chose (tags / release pages / `action.yml` `runs.using`), and any breaking change you found that touches our inputs.
4. The YAML validation you ran and its output.
5. Deviations from this prompt, and why.
6. Spec impact (expected: none).
7. Open ends and risks (e.g. anything you expect to still warn on the next CI run).
