Implement KAN-24 ("Optimistic locking on User to prevent lost concurrent updates") in the SquadPulse backend (`backend/src/main/java/com/squadpulse/`). It's the last open ticket in the KAN-10 "Auth & Roles" epic. It came out of the KAN-21 review: every write to a `User` saves the whole document (load, change a field, `userRepository.save(user)`), so two concurrent writes to the same user are last-write-wins and the losing write disappears without an error. It has to land before any endpoint that toggles `User.active`, because a concurrent write could otherwise silently undo a deactivation. Read the full ticket description in Jira (KAN-24) as well. This prompt adds the decisions we made on top of it.

## Step 0: sync and branch

Before anything else:
1. `git checkout master && git pull`
2. Verify local `master` is identical to `origin/master` (`git status` clean, `git rev-parse master` == `git rev-parse origin/master`). If they differ, stop and tell me. Don't try to fix it yourself.
3. `git checkout -b feature/KAN-24-user-optimistic-locking`

## Verify against what's actually installed (this ticket depends on it)

The project is on Spring Boot **4.1.1**, with Spring Data MongoDB and Spring Data Commons from that BOM. The whole design below rests on framework behavior that has changed between versions, so **check each point below in the source of the resolved jars** (`mvn dependency:sources`, or open the sources jar in your IDE) **and back it up with a Testcontainers test.** General knowledge isn't enough here. In your summary, give the resolved versions and, for each point, the class/method you read and the test that proves it:

1. **How `SimpleMongoRepository.save` decides insert vs update** when the entity has a `@Version` property. As far as I know it goes through `isNew`, e.g. `PersistentEntityIsNewStrategy`: a wrapper-typed version (`Long`) counts as new when `null`, and a **primitive** version (`long`) counts as new when `0`. If that's right, a legacy document that has no `version` field is "new" whichever type we pick, and `save` tries an `insert` that fails on the duplicate `_id` (or the unique email index). Confirm or correct this.
2. **What `MongoTemplate`'s versioned save does:** a conditional update on `_id` + `version` that increments the version, throws `OptimisticLockingFailureException` when nothing matched, and doesn't upsert. Also check what happens when the version is `null`.
3. **What `insert` does with a `null` `@Version` `Long`.** I expect it to initialize the version to `0`. This matters for `UserInvitationService` and `ClubBootstrapService`.
4. **Whether `@EnableMongoAuditing` (`@CreatedDate` / `@LastModifiedDate`) behaves the same once `User` is versioned.** Auditing also uses `isNew`: `createdAt` must stay untouched on updates, and `updatedAt` must still change.

If any of these turns out different from what's described here and that changes the design, **stop and tell me** before building around it.

## Read these first

- `auth/User.java`, `auth/UserRepository.java`
- `auth/PasswordResetService.java` (`resetPassword`) and `auth/UserPermissionLevelService.java`: the only two `save` calls on `User`.
- `auth/UserInvitationService.java` and `auth/ClubBootstrapService.java`: both create users with `insert`.
- `common/ClubScopedRepositoryImpl.java`: especially `save` → `stampOrValidateClubId` → `super.save`, and its Javadoc on the known look-up-then-act limitation.
- `common/ClubContext.java` (`callAs`), `common/GlobalExceptionHandler.java`, `common/ConflictException.java`, `common/MongoRepositoryConfig.java`.
- Existing tests: `PasswordResetServiceTest`, `PasswordResetFlowIntegrationTest`, `UserPermissionLevelServiceTest`, `UserRepositoryIntegrationTest`, `ClubScopedRepositoryImplIntegrationTest`. Reuse their Testcontainers setup. Don't create a new one.

Run a grep of your own to confirm there are no other writers to `User` (`save`, `saveAll`, `MongoTemplate` / `MongoOperations` updates). If you find any, list them in your summary and handle them the same way.

## Changes

### 1. Version field on `User`

- Add a `@Version private Long version;` (`org.springframework.data.annotation.Version`) with a getter. **Don't add a public setter** unless a framework need you found in the jar requires one, and if so, say why. Use `Long` (wrapper) together with the backfill in (2), unless what you find in "Verify" points to a better choice. If you pick something else, explain why.
- Javadoc: what the field is for (optimistic locking against lost updates, KAN-24), and that application code never sets it.
- **Don't expose it in the API.** `UserResponse` stays as it is. Check that no test serializes `User` directly.
- Don't touch `ClubScopedEntity`. Versioning other entities is out of scope.

### 2. Backfill for existing documents (decided: automatic, at startup)

Local dev databases already contain users that have no `version` field. Add a small, idempotent startup step in `auth` (e.g. `UserVersionBackfill`) that runs `updateMany` on the `users` collection with the filter `{version: {$exists: false}}` and the update `{$set: {version: 0}}`. It logs the number of documents it modified at INFO, and only when that number is greater than zero.

- **Timing:** it must finish **before the web server accepts requests**. Don't use an `ApplicationRunner` or `CommandLineRunner`: in Boot those run after the embedded server has already started. Run it during context initialization instead (e.g. `InitializingBean` / `SmartInitializingSingleton` on a bean that depends on `MongoTemplate`), and **verify in the installed Boot version** that this runs before the web server starts. Say how you verified it.
- **clubId isolation (CLAUDE.md rule 4):** this is the first direct `MongoTemplate` write in `main`, and it intentionally goes around `ClubScopedRepositoryImpl`. Keep it as narrow as possible: filter on `version` only, `$set` on `version` only, touching nothing else. Explain in the Javadoc why this is an accepted exception: it's a schema backfill that reads and exposes no tenant data. If an ArchUnit rule or an existing test objects to it, stop and tell me. Don't loosen a rule on your own.
- It also runs under the `bootstrap` profile. That's harmless, but confirm it doesn't break `ClubBootstrapIntegrationTest`.
- Mention in the Javadoc that the step can be removed once every environment has been backfilled. There's no production database yet (Phase 6+).

### 3. `PasswordResetService.resetPassword`: bounded retry

The code has already been consumed by the time we save, so failing on a conflict would force the user to request a new code for nothing. The new flow:

1. Verify the code, as today.
2. Compute `passwordEncoder.encode(newPassword)` and `clock.instant()` **once, before the loop**. Argon2 isn't recomputed on retry.
3. Loop, at most `MAX_ATTEMPTS` (a named constant, e.g. 3): load the user with `findByEmail(normalizedEmail)`, **re-check** that the user exists and is `active` (if not, throw `InvalidResetCodeException`, same as today), set the hash and `sessionsInvalidatedAt`, save inside `clubContext.callAs(user.getClubId(), ...)`. On `OptimisticLockingFailureException`, go around again.
4. Once the attempts run out, let the last `OptimisticLockingFailureException` propagate. It becomes a 409 through (5). **Never return normally, and therefore never 204, unless the save succeeded.**

The re-check of `active` inside the loop is the security point of this ticket: if the user is deactivated during the window, the reset must fail, not undo the deactivation. Document this in the method's Javadoc.

### 4. `UserPermissionLevelService.changePermissionLevel`: bounded retry (decided: not 409)

The request is "set level X", an absolute value on a field that no other writer touches, so reloading and saving again loses nothing. Same shape as (3): a loop with at most `MAX_ATTEMPTS`, reloading with `findById` (club-scoped, as today, so a user that disappears or is outside the club → `NotFoundException`). **Keep the no-op check inside the loop**: if the reloaded user already has the requested level, return it without saving. After the last attempt, propagate the exception (→ 409 via (5)). Document the choice and the reason in the class Javadoc: a future multi-field "edit user" endpoint, where the admin edits based on what they saw, should probably get a 409 instead of a retry. Note that as guidance for the future. Don't build it.

Avoiding duplication between (3) and (4) with a small shared helper is fine if it stays clean and readable (e.g. package-private in `auth`). **Don't add a Spring Retry dependency** for this.

### 5. Safety net in `GlobalExceptionHandler` (decided: 409 + log)

Add an `@ExceptionHandler(OptimisticLockingFailureException.class)` that returns **409 Conflict** with the same `ApiErrorResponse` shape as `ConflictException` and a generic message (e.g. "The resource was modified concurrently, please retry"). Don't leak internal details. Log it at **WARN** without the full stack trace. Right now, without this handler, the exception would fall through to the catch-all `handleUnexpected`, which returns 500 and logs nothing. **Don't change `handleUnexpected`.** It's a known open item, not part of this ticket.

Also check where this sits in the Spring `DataAccessException` hierarchy, so the new handler doesn't accidentally catch other exceptions (e.g. `DuplicateKeyException`, which is `DataIntegrityViolationException`). Use the exact type.

### 6. `ClubScopedRepositoryImpl`

Nothing is expected to change there. `save` calls `stampOrValidateClubId` and then `super.save`, so the versioned save should go through as usual. **Verify** this with a test through the real club-scoped repository (not `MongoTemplate` directly). Also check that `stampOrValidateClubId` treats a versioned entity whose document exists as an update, not as "new". Update the look-up-then-act paragraph in the Javadoc only if it's now inaccurate. Optimistic locking doesn't fix the clubId-check race (it happens between the find and the save), and that limitation stays as is.

## Tests (Testcontainers Mongo; deterministic, no `Thread.sleep` or timing-dependent races)

Build the interleaving deterministically: e.g. load two copies of the same user and save them one after the other, or use a test hook (a spy on the repository, or a wrapper around `PasswordEncoder` / the first `findByEmail`) that performs the "concurrent" write at an exact point in the flow. If you also add a real-thread test, it's in addition to the deterministic ones, and it must not be flaky.

- **Repository level (through the club-scoped `UserRepository`):** two copies of the same user; the first save succeeds and the second throws `OptimisticLockingFailureException`. The stored document holds the first write, and the version went up by exactly 1. A normal save increments the version. `insert` (invite / bootstrap) creates a user with version `0`, or whatever the jar actually does (see "Verify").
- **Legacy document:** insert a raw document without `version` into `users` (via `MongoTemplate`, with no version field at all), run the backfill, and then check that `resetPassword` and `changePermissionLevel` succeed on it and that no duplicate is created. **Also add a test that proves the backfill is needed:** without it, saving a legacy document fails (or whatever you actually found in the jar). That documents the reason for the backfill in code. Also check that the backfill is idempotent (a second run changes 0 documents) and touches no other field.
- **Backfill timing:** a test (or a clear explanation in the summary, if a test isn't practical) showing that it runs before the server accepts requests.
- **Reset vs a concurrent permission change:** in the middle of `resetPassword` (after the load, before the save), a permission change is written. Result: **both** end up in the database (new hash, `sessionsInvalidatedAt` set, and the new permission level) and the response is 204. Also run it the other way round: the reset is saved during a permission change, and again both changes are kept.
- **Reset vs a concurrent deactivation:** in the middle of `resetPassword`, the user is set to `active = false` directly in the repository (there's no endpoint yet). Result: `InvalidResetCodeException` (401), `active` stays `false`, and the old `passwordHash` is unchanged. This is the most important test in the ticket.
- **Retries exhausted:** a hook that conflicts on every attempt → exactly `MAX_ATTEMPTS` attempts, then `OptimisticLockingFailureException`. Through HTTP (WebMvc or integration) it's **409** with the generic message, never 204 or 500.
- **Permission level:** a concurrent conflict → reload and save succeed. A conflict where the reloaded level is already the requested one → no-op, no save. A user deleted or missing on reload → 404.
- **`GlobalExceptionHandler`:** `OptimisticLockingFailureException` → 409 with the expected body.
- **Auditing:** after an update, `createdAt` is unchanged and `updatedAt` has changed.
- Keep all existing tests green, including ArchUnit.

## Docs (same commit as the change, per CLAUDE.md rule 7)

- `README.md`: add a short note wherever it fits (a developer section, or "Auth API"). It should say that `User` is protected by optimistic locking, that a concurrent-write conflict that wasn't resolved by retry returns 409, and that the startup backfill adds a version field to existing local users automatically.
- `CLAUDE.md`: a line in "Architecture at a glance": `User` writes use `@Version`. A writer that does load-modify-save on `User` must handle `OptimisticLockingFailureException` (reload + retry for absolute single-field changes, 409 otherwise). A future deactivation endpoint must follow this pattern.
- **Don't edit `docs/spec.md`.** If anything in it is now inaccurate (e.g. section 10 or the data model), flag exactly what and where in your summary and in the PR description, and leave the decision to me.

## Conventions

- English code, comments and commits. Conventional Commits with the ticket key. Suggested split: `feat(auth): add optimistic locking to User (KAN-24)` (field + backfill + repository tests), then `fix(auth): retry user writes on version conflicts (KAN-24)` (both services + their tests), then `feat(common): map OptimisticLockingFailureException to 409 (KAN-24)`, then docs. Tests go with the code they cover.
- Save this prompt as `docs/agent-prompts/KAN-24-prompt.md` in a `docs:` commit, like the previous tickets.
- Run `spotless:apply` and the full test suite before you finish.
- **Don't push, open a PR, or touch CI config.** When you're done, stop and give me a summary with:
  - what you built;
  - every design decision you made that this prompt didn't dictate;
  - for each point under "Verify": the resolved version, the class/method you read, and the test that proves it;
  - the full test list with results;
  - any open ends or risks you noticed.

## Out of scope

- The deactivation endpoint itself.
- Optimistic locking on other entities (revisit when Player and similar feature entities exist).
- Fixing the clubId look-up-then-act race in `ClubScopedRepositoryImpl`.
- Logging in `GlobalExceptionHandler.handleUnexpected` (a known open item from KAN-21).
- A general-purpose migration framework (Mongock and the like).
