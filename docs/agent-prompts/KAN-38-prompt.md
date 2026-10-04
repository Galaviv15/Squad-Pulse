# KAN-38: Club settings (club name)

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `3b260a1` (the KAN-37 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-38-club-settings`.

## Context

This is Jira KAN-38, under Epic KAN-33 ("Club & Staff Administration"). Spec section 04 already says the Club Manager (`ADMIN`) "manages … club settings", but no endpoint reads or writes the club itself. Only the logo has endpoints (KAN-30). Today `Club` has three fields: `id`, `name`, `createdAt`. The logo lives in the image store, not on the document. So in this ticket, "club settings" means **viewing and renaming the club**. No other settings are added.

**The Jira ticket leaves scope, endpoints and concurrency "to decide". They are decided. Follow this prompt.**

Read these first:
- `CLAUDE.md`: the access-control and error-handling paragraph, the images paragraph (why `/clubs/me/...` is not under `/auth`), the `ActiveCallerCheck` rule, and rule 7
- `docs/spec.md`: section 03 (multi-tenancy, `Club` as the tenant root), section 04 (Club Manager row, logo paragraph, `/me` paragraph, endpoint table), section 09 (onboarding / bootstrap), section 10 ("Concurrent writes", "Error responses", "Additional protections" about validation)
- README: "Club API" and "Auth API" (`/me`)
- in `auth`: `Club`, `ClubRepository`, `ClubLogoController`, `ClubLogoService` (`hasLogo`), `ClubBootstrapService` (how it validates `Club` with the `Validator`), `ClubBootstrapRunner`, `CurrentUserService`, `CurrentUserResponse` (`ClubSummary`), `ActiveCallerCheck`, `UserManagementController` (how it uses the caller check), `UserVersionBackfill` (why it exists; KAN-24), `package-info.java`
- in `squad`: `CreatePlayerRequest` / `UpdatePlayerRequest` / `Player.setFullName` (the trim pattern to copy)
- tests: `ClubLogo*` tests, `CurrentUserIntegrationTest`, `ClubBootstrap*` tests, `EndpointAuthorizationRules` / `EndpointAuthorizationTest`, `PublicEndpointsConsistencyTest`, `UserLoadHook`

**Facts I checked on master (`3b260a1`). Re-confirm them; don't take them on faith:**
- `Club` is `@NotClubScoped`. `ClubRepository` is a plain `MongoRepository` (not club-scoped), and its only constraint is `@NotBlank` on `name`. There is no max length and no trimming. `ClubBootstrapService` validates the `Club` entity with the injected `Validator` before inserting it.
- `Club` has **no** `@Version`. Adding one now would make existing club documents (no `version` field) look new to Spring Data, so `save` would try an insert and hit a duplicate key. That is why KAN-24 needed `UserVersionBackfill`. **Do not add `@Version` to `Club` in this ticket.**
- `/me` (`CurrentUserService`) reads the club on every request by `caller.clubId()` and returns `club {id, name, hasLogo}`. A missing club document is an `IllegalStateException` (500), because a token's club must exist.
- `ActiveCallerCheck.requireActive` is the one definition of "the caller can still act". CLAUDE.md requires it on user-management writes.
- The last full build (KAN-37) had 945 tests.

## Decisions agreed with Gal (implement exactly these)

1. **Endpoints**, in a new `auth.ClubController` mapped at `/clubs/me`, with the service code in a new `auth.ClubSettingsService` (or a similarly clear name). "me" is the caller's club, taken only from the token (`ClubContext` / `AuthenticatedUser.clubId()`). There is never a club id in the path or body, and none is ever read from the request.
   - `GET /clubs/me`: `@PreAuthorize("hasAuthority('VIEW_ONLY')")` → `200` `ClubResponse`.
   - `PATCH /clubs/me`: `@PreAuthorize("hasAuthority('ADMIN')")`, JSON body `{"name": "..."}` → `200` `ClubResponse` with the stored values.
   - **Not under `/auth`.** Same reason as the logo: the refresh cookie is `Path=/auth`. Say this in the controller Javadoc, next to the logo controller's note.
   - `ClubResponse(id, name, hasLogo)`: the same fields and JSON as `/me`'s `club`. Make `/me` use the same record for its `club` field (rename or replace `CurrentUserResponse.ClubSummary`) so the two can't drift. **`/me`'s JSON must not change.** Prove that with the existing `/me` tests passing unchanged on the JSON. No `createdAt`, no `version`, nothing else.

2. **PATCH semantics:** a partial update of the club's settings. Today `name` is the only setting, so the body **must** contain it. Missing, `null`, blank or too long → `400` through the normal validation path (`details: ["name: ..."]`). Unknown JSON fields are handled like other endpoints in the codebase. Check what the configured Jackson does with an unknown property (e.g. `{"name":"x","clubId":"other"}`). Report it, and add a test that such a body can never change anything but `name`. The Javadoc says that future settings will be optional fields of this same PATCH, where an absent field means "unchanged".

3. **Validation of `name`:** trimmed first, then 1–100 characters. Follow the player `fullName` pattern: trim in the request record's compact constructor **and** in `Club.setName`.
   - Add one constant (e.g. `Club.NAME_MAX_LENGTH = 100`). Use it for `@Size(max = ...)` on both the request DTO **and** `Club.name`. That way the owner bootstrap (`ClubBootstrapService`, which validates the entity) enforces the same rule. A name the bootstrap accepts must never be one the API rejects.
   - Existing clubs whose names are longer than 100 characters or have surrounding spaces aren't migrated. Reads and `/me` still return them as stored. Mention this under open ends.

4. **Concurrency: targeted single-field update, no version.** Renaming is an absolute single-field change: two admins renaming at once → the later write wins, and both get `200` with what they wrote. That's correct for one field, so no `version` in the request and no `409`.
   - The write must change **only** `name`: a Mongo `$set` on `{_id: <caller's clubId>}`, never a load-modify-`save` of the whole document. A whole-document save would silently overwrite any field a future change adds concurrently. Pick the cleanest way that keeps the query in `ClubRepository` and verify it against the installed Spring Data MongoDB (5.1.1). A repository method with `@Query("{ '_id': ?0 }")` + `@Update("{ '$set': { 'name': ?1 } }")` is my first suggestion. If you use `MongoTemplate`/`MongoOperations` instead, explain why and confirm it doesn't break an ArchUnit rule. Either way, the `_id` is always the token's clubId.
   - If the update matches no document → `IllegalStateException` (500), the same as `/me` treats a missing club. The token's club must exist.
   - Return the response from a re-read of the club after the update (plus `hasLogo`). It is not built from the request.
   - Javadoc on the service and a sentence for the spec: **when the club gets several settings edited as one form, it must move to `@Version` (with a backfill for existing club documents, as `UserVersionBackfill` did in KAN-24) and client-sent versions, like `Player`.**

5. **Caller re-check on the write.** `PATCH` calls `ActiveCallerCheck.requireActive(caller)` first, so a deactivated ADMIN's still-valid access token (≤ 15 min) can't rename the club. That gives the same generic `401` as user-management writes. `GET` doesn't re-check (like the other reads). Update the CLAUDE.md rule so it covers "user-management **and club-settings** writes". Don't add the check to the logo endpoints in this ticket; list it under open ends instead.

6. **Nothing else changes:** no new settings, no club creation or deletion, no changes to the logo endpoints, the bootstrap flow (apart from the shared validation in item 3), or the JWT.

## Verify against what's installed

Don't rely on general knowledge. Check against `pom.xml` / `./mvnw dependency:tree` (Boot 4.1.1, Spring Data MongoDB 5.1.1, Spring Security 7.1.1, Hibernate Validator 9.1.3, Jackson 3.1.5):
- that the chosen update mechanism really issues a `$set` on `name` only. **Prove it in an integration test, not by reading the code:** insert a club document that has an extra field written directly through Mongo (e.g. `futureSetting: "keep"`) and `createdAt`. PATCH the name, then assert that the extra field and `createdAt` are byte-for-byte unchanged and that no `version` / `_class` field was added or changed.
- how the installed Jackson treats unknown JSON properties on this request (item 2), with a test.
- that `@Size` on `Club.name` is picked up by `ClubBootstrapService`'s validation (a bootstrap test with a 101-character name).
- that `EndpointAuthorizationTest` picks up both new handlers and that `PublicEndpointsConsistencyTest` is unaffected.

## Tests

1. **Controller slice** (`@WebMvcTest`, real security chain via `AuthWebMvcTestConfig`, `TestAccessTokens`): `GET` → 200 for all four levels, 401 with no token. `PATCH` → 200 for `ADMIN`; `VIEW_ONLY`, `EDIT_PARTIAL`, `EDIT_FULL` → 403; no token → 401. Exact 400 bodies for a missing, blank, whitespace-only and 101-character name. A 100-character name and a name with surrounding spaces are accepted, and the spaces are trimmed. Also the exact 401 body for a deactivated caller.
2. **Service unit tests:** the caller check runs before the update. The update targets the token's clubId. A zero-match update throws `IllegalStateException`. The response comes from the re-read.
3. **Integration (real Mongo, through HTTP). Each of these is required:**
   - **Rename round-trip:** an ADMIN renames the club (with a Hebrew name, e.g. `"הפועל בדיקה"`, and surrounding spaces). The response, then `GET /clubs/me`, then `/me`'s `club.name` all show the trimmed new name. `hasLogo` is correct both with and without a logo.
   - **Targeted update:** the extra-field / `createdAt` test from "Verify against what's installed".
   - **Club isolation:** clubs A and B. A's ADMIN renames → B's name is unchanged, and B's `GET /clubs/me` shows B. A body that tries to name another club (`{"name":"x","id":"<B>","clubId":"<B>"}`) changes only A's name, or is rejected, depending on item 2. It never touches B.
   - **Caller re-check:** admin A1 holds an access token, and A1 is deactivated by admin A2. A1's still-valid token on `PATCH` → 401, and the name is unchanged. The same token still gets 200 on `GET /clubs/me` (documents the accepted read window).
   - **Last write wins:** two sequential renames → the second is stored. If it's cheap to do with a hook (no sleeps), show that a rename landing between another request's update and re-read is what the second request returns. Otherwise skip it and say so.
   - **Bootstrap:** a 101-character club name is refused with nothing written. A name with surrounding spaces is stored trimmed.
4. **Regression:** `/me` tests pass with the JSON unchanged. `EndpointAuthorizationTest`, `PublicEndpointsConsistencyTest` and ArchUnit pass. Everything else passes unchanged.

Run `./mvnw verify` with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25). Everything green, Spotless clean. Report the total test count (945 after KAN-37).

## Docs

- **README** ("Club API"): both endpoints. Cover permissions, the body, the validation rules (trim, 1–100), the response, last write wins / no version, the caller re-check on `PATCH`, and why the path isn't under `/auth`.
- **CLAUDE.md:** the status line (KAN-38 endpoints), and extend the `ActiveCallerCheck` rule to club-settings writes. Change anything else only if it becomes inaccurate, and say what you changed.
- **`docs/spec.md`: do NOT edit it.** In your summary, list exactly what needs updating, with the exact facts. Expected at least:
  - section 04: a club-settings paragraph (`GET` / `PATCH /clubs/me`, what "me" means, name rules, response shape) and two endpoint-table rows.
  - section 09: the bootstrap now applies the same name rule.
  - section 10: the caller re-check now covers the club-settings write too. In "Concurrent writes", `Club` uses a targeted single-field update without `@Version`, and a future multi-field club form must move to `@Version` + backfill.
  - the status line and the roadmap (section 13).

## Commits

Small, focused commits, for example:
- `feat(auth): validate and trim the club name (KAN-38)`
- `feat(auth): add GET and PATCH /clubs/me for club settings (KAN-38)`
- `test(auth): ... (KAN-38)`

Docs go in the same commit as the change they describe (rule 7). **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. Sample JSON: `GET` and `PATCH` responses, `/me` before and after (identical shape). The exact 400 / 401 / 403 bodies.
3. Evidence for each item in "Verify against what's installed".
4. The test list with counts, and the full build result (command, outcome, total count).
5. Deviations from this prompt, and why.
6. The spec update list.
7. Open ends and risks.
8. Print the full final `ClubController`, `ClubSettingsService`, `ClubRepository`, `Club`, the request and response records, and the diff of `CurrentUserService` / `CurrentUserResponse`, so they can be reviewed directly.
