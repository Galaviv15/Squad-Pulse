# KAN-65: Club ↔ football.org.il mapping: team, league and season ids on Club, set by the system owner

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `dbd7b6e`, the KAN-64 merge, PR #47). If it doesn't, stop and report. Then create the branch `feature/KAN-65-club-football-org-il-mapping`.

## Context

Jira KAN-65, epic KAN-14 ("Match & Scraping Integration"). Blocked by KAN-64 (the scraping spike, done: `scraper/docs/KAN-64-findings.md`); blocks KAN-66 (backend storage + ingest endpoint).

Each club needs to know which team, league and season it is on football.org.il, so the scraper (KAN-67, a separate Node service that never touches MongoDB) knows what to fetch. Today `Club` (`backend/src/main/java/com/squadpulse/auth/Club.java`) has only `id`, `name`, `createdAt`.

First real values: Sderot FC — `team_id=7617`, `league_id=62`, `season_id=28`.

From the KAN-64 findings (section "KAN-65"):

- `season_id` changes every season (28 = 2026/27).
- `league_id` changes on promotion / relegation, and possibly per season.
- `team_id` looks stable across seasons, but that's unverified.

So the system owner updates the mapping manually each summer, and the three values are always set **together**.

### What's missing today (the main point of this ticket)

The ticket says the mapping is set "by the system owner only (same path as club creation / owner bootstrap)". But `ClubBootstrapRunner` / `ClubBootstrapService` can only **create** a new club with its Club Manager. There is no way to change anything on an existing club outside the app. Sderot already exists, so without an update path the first values can't even be entered, and the yearly season change has nowhere to go. This ticket adds that path.

## Decisions (agreed with Gal; not up for change in this ticket)

1. **Shape: one embedded sub-document on `Club`**, e.g. `footballOrgIl { teamId, leagueId, seasonId }`. Name of the Java type and field is your call; keep it descriptive, and make the stored field name clear in the document (e.g. `footballOrgIl`). All three are positive integers (≥ 1). It's **all or nothing**: either the sub-document is absent / `null` (the club is not mapped, scraping is unavailable for it), or all three values are present and valid. No partial mapping can ever be stored.
2. **No API exposure in this ticket.** No new endpoint, no ADMIN endpoint, no UI. `ClubResponse` (`GET` / `PATCH /clubs/me`, and `club` inside `GET /auth/users/me`) does **not** change: its Javadoc says it never carries anything else stored on `Club`, keep it that way. Telling the user "scraping is unavailable for this club" is KAN-66 / KAN-68's job.
3. **Set by the system owner through the `bootstrap` profile**, the same gate as club creation: the owner-secret prompt, no web server, nothing reachable over the network.
   - **Creating a club** may optionally take the mapping at once: three new options, e.g. `--football-team-id`, `--football-league-id`, `--football-season-id`. All three or none; a partial set is refused before anything is written.
   - **A new mode updates the mapping of an existing club**, selected by `--club-id=<id>` plus the same three options (all three required). It replaces the whole mapping (all three values together). It does **not** prompt for a manager password, and it rejects the create-only options (`--club-name`, `--manager-*`) so the two modes can't be mixed by mistake. Likewise, create mode must reject `--club-id`. Option names are your call if you have a better consistent scheme; keep the existing create command working exactly as today when none of the new options are given.
   - Order stays as today: forbidden secret options → owner secret prompt and check → everything else. A wrong secret learns nothing (not even whether the club id exists).
   - An unknown club id, a malformed id, a missing / non-numeric / zero / negative value → refused with a clear message, exit code `1`, **nothing written**. Use the existing `ClubBootstrapException` path and the existing logging style (never log secrets).
   - On success, log one line with the club id, club name, the **previous** mapping (or "none") and the new one, so a season change leaves a trace in the run's output. Exit `0`.
   - **No "clear mapping" operation** in this ticket.
4. **The write is a targeted update.** `Club` has no `@Version` on purpose (see its Javadoc and spec section 10): every write must touch only its own field(s), never `save()` the whole document. Add a `ClubRepository` method in the style of `updateNameByClubId` (`@Query("{ '_id': ?0 }")` + `@Update` `$set`) that sets the mapping alone. Whether you `$set` the whole sub-document or the three dotted paths is your call, but prove the stored shape with a test that reads the raw document (see Tests). Because an `@Update` method's return value can't tell "not found" from "no change", check the club exists first and re-read it after the write for the log line (clubs can't be deleted, so there is no race to handle beyond that; say so in a comment).
5. **Validation before writing.** Spec section 10: entity constraints are **not** re-checked on save, so validate the three values in the service before anything touches the database (the create path already validates the `Club` with the `Validator` — make the new field's constraints part of that, e.g. `@Valid` on the field and `@NotNull` / `@Positive` on the components, and use the same validation for the update path).
6. **Existing club documents keep working.** A club document stored before this ticket (no mapping field) must load with a `null` mapping. No backfill, no migration.
7. **Resolved names are out of scope.** KAN-64 suggested showing the team name the ids resolve to, so a wrong id is visible. The backend never talks to football.org.il (the scraper does, and the site is behind Cloudflare), so the ids can't be checked here. That moves to KAN-66 / KAN-67 (store the team name from the standings; a configured `team_id` missing from the standings is a visible error). Don't build anything for it here.

## Verify against reality, not memory

Check these against what's actually installed (`backend/pom.xml`, the resolved dependency versions — e.g. `./mvnw dependency:list` or the jars in `~/.m2`), not general knowledge, and say in the summary how you checked each:

- That Spring Data MongoDB (the version this project resolves; I believe 5.1.x under Boot 4.1.1 — confirm) maps the embedded type you choose correctly, **in both directions**: if you use a Java `record`, that it's instantiated through its canonical constructor and that a document **without** the field reads as `null`; if you use a class, the same.
- How an `@Update` method converts the parameter you pass: if you `$set` a whole object through `?1`, confirm it's written as an embedded document with the right field names (not a string, not with a `_class` you didn't expect — if `_class` is written, say so and whether that matters). Prove it with the raw-document test, not by reading docs.
- That `ApplicationArguments` option parsing behaves as you rely on for the new options (e.g. `--club-id` given twice, given empty).

## Read first

`CLAUDE.md` (standing rules, especially 5 and 7), then only:

- `backend/src/main/java/com/squadpulse/auth/Club.java`, `ClubRepository.java`, `ClubBootstrapService.java`, `ClubBootstrapRunner.java`, `ClubBootstrapException.java`, `ClubResponse.java`, `ClubSettingsService.java`
- `backend/src/main/resources/application-bootstrap.yml`
- `backend/src/test/java/com/squadpulse/auth/ClubBootstrapIntegrationTest.java`, `E2eSeeder.java` (it calls `ClubBootstrapService` with a `NewClub`; keep it compiling and behaving exactly as today: the E2E club has no mapping), the existing runner tests, `ClubSettingsIntegrationTest.java`
- `README.md` section "Bootstrapping a new club"
- `docs/spec.md` sections 03, 04 (only the club-settings paragraph), 07, 09 and 10 (only the "Concurrent writes" bullet) — to list what goes out of date, **not** to edit
- `scraper/docs/KAN-64-findings.md`, section "KAN-65" only

## Tests

Follow the existing test style (unit tests for the runner's argument handling with the `SecretPrompt` / exit-code seam; Testcontainers integration tests for the service and repository). At least:

- **Create with mapping:** all three given → stored as the sub-document with the right field names and integer types (raw document read via `MongoTemplate`).
- **Create without mapping:** none given → no mapping field stored (or explicitly `null` — say which, and make sure reading it back gives `null`). The existing create tests still pass unchanged.
- **Create with a partial mapping** (one or two of three) → refused, **nothing written** (no club, no user).
- **Update mapping on an existing club:** sets all three; a second update replaces all three; `name`, `createdAt` and every other field of the document are untouched (compare the raw document before and after, minus the mapping).
- **Update refusals**, each with exit code `1` and nothing written: wrong secret (and the club id isn't even looked up), unknown club id, malformed club id, a missing value, a non-numeric value, `0`, a negative value, a value given twice, mixing in a create-only option. And create mode with `--club-id` → refused.
- **No manager-password prompt in update mode** (the test prompt seam proves it's never asked).
- **A legacy club document** inserted raw, without the field → loads with a `null` mapping, and `GET /clubs/me` still answers as before.
- **`PATCH /clubs/me` (rename) does not touch the mapping**, and the mapping update does not touch the name.
- **`ClubResponse` / `GET /clubs/me` / `GET /auth/users/me` do not expose the mapping** (assert the JSON has no such key).
- **The log line** on a successful update contains the club id, the previous and the new mapping, and nothing secret.

For two of the refusal tests and the "rename doesn't touch the mapping" test: break the code on purpose (e.g. remove the partial-mapping check, switch the targeted update to `save()`), confirm the test fails, restore. Report which mutations you tried and that each was caught.

Run `./mvnw spotless:apply` then `./mvnw verify` from `backend/` with `JAVA_HOME` set to Temurin 21 (Spotless crashes on JDK 25). Report the test count before and after.

## Docs

- **`README.md`**, "Bootstrapping a new club" (CLAUDE.md rule 7, same commit as the code): the optional mapping options on create, the new update mode with an example (use Sderot's real values: `--football-team-id=7617 --football-league-id=62 --football-season-id=28`), that the season and league must be updated each summer, what's refused, and that there's no "clear" operation yet. If the section's title no longer fits, rename it (e.g. "Bootstrapping and mapping a club") and fix any link to it.
- **`CLAUDE.md`**: update only if a statement there becomes wrong (e.g. something saying the only targeted club write is the name). Say in the summary what you changed or that nothing needed changing.
- **Javadoc**: `Club` (the new field, that it's set only by the owner bootstrap and only by a targeted update, that `null` = not mapped), the new repository method, `ClubBootstrapRunner` (the two modes), `ClubResponse` (still exposes nothing else — keep it true).
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR. In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words so I can find it) and what's wrong. I expect at least: section 09 (the owner creates clubs — now also maps them, and updates the mapping yearly); section 10's "Concurrent writes" bullet (it says the name is the club's only editable setting / only targeted update); possibly section 03 (what `Club` holds) and section 07 (how the scraper knows what to fetch). Confirm or correct each, and add any I missed.

## Commits

Small, focused commits, for example:

- `feat(auth): store the football.org.il mapping on Club (KAN-65)`
- `feat(auth): set or update a club's football.org.il mapping from the owner bootstrap (KAN-65)`
- `docs: document the club mapping options in the README (KAN-65)` (or in the same commit as the code, per rule 7)

Stage files by path, never `git add -A`. **Do not push and do not open a PR.**

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. Versions verified (Spring Boot, Spring Data MongoDB, and anything else you relied on) and **how** you checked each.
3. The final design: the Java type and stored field names, the exact stored document shape (paste a raw document from a test), the repository method, and the CLI: every option, both modes, an example command for each.
4. What you verified about embedded mapping, `@Update` conversion and `_class` (evidence, not assumptions).
5. Tests: the new tests (name + one line each), the mutation checks and their results, the test count before and after, and the `verify` result (local).
6. Docs changed (README, CLAUDE.md, Javadoc), one line each.
7. Spec: every out-of-date place (section + quoted words + what's wrong).
8. Deviations from this prompt, and why.
9. Open ends and risks for KAN-66 / KAN-67 / KAN-68 (e.g. how KAN-66 should read the mapping, the "not mapped" case, a season change while fixtures for the old season are stored).
10. Print in full, at branch HEAD: `Club.java`, `ClubRepository.java`, `ClubBootstrapService.java`, `ClubBootstrapRunner.java`, and the new embedded type.
