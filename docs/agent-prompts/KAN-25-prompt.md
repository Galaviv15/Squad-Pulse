Implement KAN-25 ("Player entity and repository") in the SquadPulse backend (`backend/src/main/java/com/squadpulse/`). It's the first ticket in the KAN-11 "Squad Management" epic. Right now the `squad` module holds only `package-info.java`. This ticket adds the `Player` document and its repository. It does **not** add any HTTP endpoint: KAN-26 (list / get / create / update) and KAN-27 (release / re-activate / permanent delete) build on it. Read the full ticket description in Jira (KAN-25) as well. This prompt adds the decisions we made on top of it.

## Step 0: sync and branch

Before anything else:
1. `git checkout master && git pull`
2. Verify local `master` is identical to `origin/master` (`git status` clean, `git rev-parse master` == `git rev-parse origin/master`). If they differ, stop and tell me. Don't try to fix it yourself.
3. `git checkout -b feature/KAN-25-player-entity`

## Design decisions (already agreed, don't reopen them silently)

- **A `Player` is a club-scoped roster record, not a global person.** Say the same real person moves from club A to club B. A's record is released (`active = false`) and stays in A as A's history. B creates its own, fully independent record. The system doesn't know the two are the same person, and neither club can see or edit the other's record. **No global lookup, no `@GloballyScoped` finder, no cross-club field.** A possible future link (an optional `userId` to the global `User`, if players ever get logins) is **out of scope**. Don't add the field. Just mention it in the class Javadoc as the intended extension point.
- **Leaving the club is a soft delete** (`active = false`), so that future training and lineup references to a player stay valid. Permanent deletion (ADMIN-only, for records created by mistake) will exist too, but its endpoint is KAN-27. Here, the standard club-scoped `delete` / `deleteById` of the repository is enough.
- **Optimistic locking from day one** (`@Version`), the lesson from KAN-24. Because the collection is new, no backfill is needed.
- **`EDIT_PARTIAL` is out of scope** for the whole epic for now.

## Verify against what's actually installed

The project is on Spring Boot **4.1.1**, with Spring Data MongoDB from that BOM. The local and CI database is `mongo:7` (see `docker-compose.yml` and the Testcontainers image the existing tests use). **Check each point below in the source of the resolved jars** (`mvn dependency:sources`, or open the sources jar in your IDE) and back it up with a Testcontainers test. General knowledge isn't enough here. In your summary, give the resolved Spring Data MongoDB version and, for each point, the class/method you read and the test that proves it:

1. **Partial unique compound index via annotations.** Does `@CompoundIndex` (or `@CompoundIndexes`) in this version support `partialFilter`, and does `auto-index-creation: true` (already on in `application.yml`) create it with `unique: true` **and** the partial filter expression? If annotations can't express it, stop and tell me before you create the index programmatically.
2. **Which partial-filter operators this MongoDB version accepts**, and how a `null` jersey number is stored. Does Spring Data omit `null` fields on write, or write them as explicit `null`? This decides the filter. For example, `{jerseyNumber: {$exists: true}}` **matches an explicit `null`**, so two players with a `null` number could collide. Pick an expression that only matches a real number (e.g. `$type` or `$gte: 1`, whichever this version supports in a partial filter), combined with `active: true`, and prove it with a test that inserts a raw document with an explicit `jerseyNumber: null`.
3. **Whether entity-level Bean Validation runs on save.** As far as I can see, nothing registers `ValidatingEntityCallback` / `ValidatingMongoEventListener`, so the constraints on `User` are only enforced on request DTOs and in unit tests, not at persistence. Confirm or correct this. **Don't enable persistence-level validation in this ticket.** It would change `User`'s behavior too. Just report what you found.
4. **`@Version Long` on a new document through the club-scoped repository.** `save` on a new `Player` (`version == null`) inserts it with version `0`, and a later save increments the version. It should behave exactly as for `User` in KAN-24. Confirm it.

If any of these turns out different from what's described here and that changes the design, **stop and tell me** before building around it.

## Read these first

- `auth/User.java`: the pattern to follow (a `ClubScopedEntity`, `@Document`, `@Indexed`, auditing fields, `@Version`, setter normalization like `normalizeEmail`, Javadoc style).
- `auth/UserRepository.java`, `common/ClubScopedEntity.java`, `common/ClubScopedRepositoryImpl.java` (especially `save` → `stampOrValidateClubId`, and the delete methods), `common/ClubScopedRepositoryFactory.java`, `common/MongoRepositoryConfig.java` (auditing is on).
- `common/AdultAge.java` + `AdultAgeValidator.java`: written in advance for Player (see its Javadoc). Reuse it, don't duplicate it.
- `squad/package-info.java`.
- Existing tests: `UserValidationTest` (a fixed `ClockProvider` for `@AdultAge`), `UserRepositoryIntegrationTest`, `ClubScopedRepositoryImplIntegrationTest`, `UserConcurrentWriteIntegrationTest`, and the ArchUnit tests (`ClubScopedRepositoryMethodNamingTest`, `EndpointAuthorizationTest`). **Reuse the existing Testcontainers setup. Don't create a new one.**

## Changes

### 1. Enums in `squad`

- `Position`: `GK, CB, RB, LB, DM, CM, AM, RW, LW, ST`, in exactly this order (spec section 05). Add a one-line Javadoc per constant with the English name ("Goalkeeper", "Center Back", ...). Per CLAUDE.md rule 3, these codes are shown as-is in the UI and never translated.
- `PreferredFoot`: `RIGHT, LEFT, BOTH`.
- `MedicalStatus`: `FIT, INJURED`. The Javadoc should note that extended injury fields (injury type, expected return) come later.

### 2. `Player` (`squad/Player.java`)

A `@Document("players")` that `extends ClubScopedEntity`. **Don't add a `clubId` field of its own.** Fields and constraints:

| Field | Type | Constraint |
|---|---|---|
| `id` | `String` | `@Id` |
| `fullName` | `String` | `@NotBlank`, `@Size(max = 100)`; the setter trims (same idea as `User.normalizeEmail`), so the stored value never has leading or trailing whitespace. One field, not first/last (same reason as `User`). |
| `primaryPosition` | `Position` | `@NotNull` |
| `secondaryPosition` | `Position` | optional; **must differ from `primaryPosition`** (see below) |
| `jerseyNumber` | `Integer` | optional; `@Min(1) @Max(99)` |
| `dateOfBirth` | `LocalDate` | `@NotNull @AdultAge`. Age is **derived, never stored**. Don't add an `age` field. A derived getter is optional; if you add one, it takes a `Clock`, not `LocalDate.now()`. |
| `heightCm` | `Integer` | optional; `@Min(140) @Max(220)` |
| `weightKg` | `Integer` | optional; `@Min(40) @Max(150)` |
| `preferredFoot` | `PreferredFoot` | optional |
| `medicalStatus` | `MedicalStatus` | `@NotNull`, defaults to `FIT` |
| `active` | `boolean` | defaults to `true` |
| `createdAt` / `updatedAt` | `Instant` | `@CreatedDate` / `@LastModifiedDate`, getters only (like `User`) |
| `version` | `Long` | `@Version`, getter only, no public setter (like `User`) |

- **Secondary ≠ primary:** implement it as a small class-level constraint (e.g. `@DistinctPositions` in `squad` plus its validator). Both `null` → valid; secondary `null` → valid. KAN-26's request DTOs should be able to reuse the same rule, so design it to work on anything that exposes the two positions (e.g. a small interface), or explain why you chose otherwise.
- **Jersey uniqueness:** a compound **unique, partial** index on `(clubId, jerseyNumber)` that only covers documents where `jerseyNumber` is a real number **and** `active` is `true` (see Verify 1–2). The result: two active players in the same club can't share a number; players without a number, released players, and players in other clubs never collide. **The index is the guarantee, not a service-level check.** It's what protects against two concurrent creates. Give the index an explicit, stable name. The field-level Javadoc should explain the partial filter and why.
- Class Javadoc: the club-scoped identity model (see Design decisions), soft delete vs permanent delete, optimistic locking, and a reference to spec section 05.

### 3. `PlayerRepository` (`squad/PlayerRepository.java`)

`extends MongoRepository<Player, String>`, so it gets `ClubScopedRepositoryImpl` automatically. **Don't add custom finders in this ticket.** KAN-26 adds whatever the list endpoint needs, and any finder must include `ClubId` in its name (ArchUnit). Javadoc in the style of `UserRepository`.

### 4. Nothing else changes

No controller, no service, no DTO, no change to `SecurityConfig`, `GlobalExceptionHandler` or any `common` class. Note: `DuplicateKeyException` is currently **not** mapped by `GlobalExceptionHandler` (it would become a 500). Mapping jersey conflicts to 409 is KAN-26's job. Just mention it in your summary if you see anything relevant.

## Tests (Testcontainers Mongo for anything persistence-related; deterministic, no `Thread.sleep`)

- **Validation unit tests** (`PlayerValidationTest`, fixed `ClockProvider` like `UserValidationTest`): a fully populated player is valid; a minimal one (only the required fields) is valid. Boundaries: `fullName` blank / 100 / 101 characters; `jerseyNumber` 0/1/99/100; `heightCm` 139/140/220/221; `weightKg` 39/40/150/151; `dateOfBirth` null and just outside the adult range (proving `@AdultAge` is wired; the exact boundaries are already covered by `AdultAgeValidatorTest`); secondary == primary is rejected, secondary null or different is accepted. The `fullName` setter trims.
- **Repository integration tests** (`PlayerRepositoryIntegrationTest`, through the real club-scoped `PlayerRepository`, not `MongoTemplate`):
  - save stamps `clubId` from `ClubContext`; `medicalStatus` defaults to `FIT` and `active` to `true`; `createdAt` / `updatedAt` are set; the version starts at `0` and a later save increments it; on update, `createdAt` is unchanged.
  - **Isolation:** a player saved in club A is invisible from club B through `findById`, `findAll`, `existsById` and `count`, and club B can neither update it (save with A's id) nor delete it (`deleteById` / `delete`). A's document stays unchanged. Use whatever the existing club-scoped tests expect for each case (exception vs no-op). Don't invent new semantics.
  - **Jersey index:** the index exists with the expected name, `unique`, and partial filter (read it via `indexOps`); two active players in the same club with the same number → `DuplicateKeyException` (or whatever the jar actually throws; say which); same number in different clubs → OK; same number where one is released (`active = false`) → OK; releasing a player frees the number for a new player; re-activating a released player whose number is now taken → the save fails; several players with no number → OK, **including a raw document with an explicit `jerseyNumber: null`** (Verify 2).
  - **Optimistic locking:** two copies of the same player; the first save succeeds, the second throws `OptimisticLockingFailureException`, and the stored document holds the first write.
- Keep all existing tests green, including ArchUnit and the "every repository entity is `ClubScopedEntity` or `@NotClubScoped`" startup check.

## Docs (same commit as the change, per CLAUDE.md rule 7)

- `CLAUDE.md`: in "Architecture at a glance", add a short note that `squad.Player` is a club-scoped roster record (the same person in two clubs = two independent records, no cross-club link), versioned with `@Version` like `User`, with jersey uniqueness enforced by a partial unique index among active players. Update the Status line if it's now inaccurate.
- `README.md`: only if something developer-facing changed (e.g. a note on the new `players` collection and its index). No API section yet. There are no endpoints.
- **Don't edit `docs/spec.md` in your commits.** I'll prepare the updated spec (including this ticket's section 05 changes) and it'll be committed in a separate `docs(spec)` commit before the PR, when I send you the push instructions. In your summary, list every place in `docs/spec.md` that this ticket makes inaccurate or incomplete, so I can include it.

## Conventions

- English code, comments and commits. Conventional Commits with the ticket key. Suggested split: `feat(squad): add Player entity and enums (KAN-25)` (enums, entity, the distinct-positions constraint, validation tests), then `feat(squad): add club-scoped PlayerRepository with jersey index (KAN-25)` (repository and integration tests), then docs. Tests go with the code they cover.
- Save this prompt as `docs/agent-prompts/KAN-25-prompt.md` in a `docs:` commit, like the previous tickets.
- Run `spotless:apply` and the full test suite before you finish.
- **Don't push, open a PR, or touch CI config.** When you're done, stop and give me a summary with:
  - what you built;
  - every design decision you made that this prompt didn't dictate (e.g. the exact partial-filter expression, the index name, how the distinct-positions constraint is shared);
  - for each point under "Verify": the resolved version, the class/method you read, and the test that proves it;
  - the full test list with results;
  - the `docs/spec.md` places that need updating;
  - any open ends or risks you noticed.

## Out of scope

- Any endpoint, service or DTO for players (KAN-26, KAN-27).
- Mapping `DuplicateKeyException` to 409 (KAN-26).
- `EDIT_PARTIAL` semantics.
- A `userId` link to `User`, or any cross-club player lookup.
- Performance data (minutes, distance, speed, sprints): phase 2+.
- Persistence-level Bean Validation (report only, see Verify 3).
