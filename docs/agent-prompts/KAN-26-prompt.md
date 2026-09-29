Implement KAN-26 ("Squad API: list (with filters), get, create and update players") in the SquadPulse backend (`backend/src/main/java/com/squadpulse/`). It's the second ticket in the KAN-11 "Squad Management" epic. It builds on the `Player` entity and `PlayerRepository` from KAN-25, which is already merged. Read the full ticket description in Jira (KAN-26), and read KAN-25 for context. This prompt adds the decisions we made on top of the ticket, plus several things I found in the existing code that the ticket doesn't mention.

**Out of scope, with a ticket each:** release / re-activate / permanent delete → KAN-27. Squad summary (average age, players per line) → KAN-28. Player photo → KAN-29. Club logo → KAN-30. Don't start any of these, and don't add a `line` to `Position` (that's KAN-28).

## Step 0: sync and branch

Before anything else:
1. `git checkout master && git pull`
2. Verify that local `master` is identical to `origin/master`: `git status` is clean and `git rev-parse master` == `git rev-parse origin/master`. If they differ, stop and tell me. Don't try to fix it yourself.
3. `git checkout -b feature/KAN-26-squad-api`

## Design decisions (already agreed, don't reopen them silently)

- **Endpoints and minimum levels.** Each handler carries `@PreAuthorize` with the lowest level allowed. The role hierarchy admits the levels above it.
  | Endpoint | Level |
  |---|---|
  | `GET /squad/players` | `VIEW_ONLY` |
  | `GET /squad/players/{id}` | `VIEW_ONLY` |
  | `POST /squad/players`: 201 with a `Location` header | `EDIT_FULL` |
  | `PUT /squad/players/{id}`: **full replacement** of all editable fields | `EDIT_FULL` |
  `EDIT_PARTIAL` is out of scope for now. Don't give it any meaning.
- **`clubId` only ever comes from `ClubContext`.** It never comes from the path, a query param or the body.
- **Another club's player → 404, not 403.** The club-scoped `findById` already returns empty for it. Always load the player through it first, so the `CrossClubAccessException` path in `save` is never the thing that answers.
- **Stale edits → 409.** `PUT` requires a `version` field, the version the client loaded. The server compares it to the stored version and answers 409 on a mismatch, before any write. This is the only way to catch "Coach A's form is stale because Coach B saved in the meantime". Without it, load-modify-save inside one request always sees a fresh version. A race lost at save time still raises `OptimisticLockingFailureException`, which `GlobalExceptionHandler` already maps to 409. **No retry**: this is a multi-field form edit, and a retry would silently overwrite someone else's change. `Player.version` has no setter and must stay that way. Compare the values; don't copy the client's version onto the entity.
- **Released players (`active == false`).** `GET /squad/players/{id}` returns them, with `active: false`. `PUT` on a released player → 409, because it has to be re-activated first (KAN-27). The update body has no `active` field. Neither create nor update can change `active`.
- **Jersey clash → 409, from the database index.** Don't add a pre-check query to the service, since it can't win a race. Catch `DuplicateKeyException` on create and update. If it names `Player.JERSEY_NUMBER_INDEX`, throw a `ConflictException` subclass in `squad` (e.g. `JerseyNumberTakenException`, with a message that includes the number). **Any other duplicate key is rethrown unchanged.** Don't swallow it, and don't turn it into a jersey error.
- **The list filters happen in memory, never with a hand-built `MongoTemplate` / `Criteria` query.** A custom template query bypasses `ClubScopedRepositoryImpl`, and the ArchUnit rule only checks repository method *names*. So a filter written that way could leak other clubs' players with no test failing. Load the club's players through a club-scoped repository method, then filter and sort in Java. A squad is about 30 to 40 players.
- **Response DTOs, never the entity.** The response includes `id`, all fields, `active`, `version`, `createdAt` and `updatedAt`. It does not include `clubId`. The client needs `version` in order to send it back.

## List endpoint: `GET /squad/players`

All query params are optional and combined with AND:

| Param | Meaning |
|---|---|
| `status` | `active` (the default), `released`, or `all` |
| `position` | a `Position`. Matches the **primary position only** (agreed). A player whose *secondary* position is the filter value is not returned |
| `minAge`, `maxAge` | integers, inclusive, each between 18 and 99. `minAge > maxAge` → 400 |
| `medicalStatus` | a `MedicalStatus` |
| `preferredFoot` | a `PreferredFoot` |

- **Repository:** add `findByClubIdAndActive(String clubId, boolean active)` to `PlayerRepository`. The name must contain `ClubId`, because `ClubScopedRepositoryMethodNamingTest` enforces it. The service passes `clubContext.requireClubId()`. For `status=all`, use the inherited `findAll()`, which is already club-scoped. Add nothing else to the repository.
- **Age** is derived from `dateOfBirth` (completed years), using an injected `java.time.Clock`. Follow the pattern the existing services use (`JwtService`, `PasswordResetService`): a public constructor that passes a default clock, and a package-private one that takes a `Clock` for tests. There is no `Clock` bean. **Pick the zone deliberately.** `@AdultAge` uses the Bean Validation `ClockProvider`. Check which clock/zone the installed Hibernate Validator's default `ClockProvider` actually uses, and use the same one, so that "18 years old" means the same thing in validation and in the filter. In your summary, say which zone it is and where you read it.
- **Order, fixed and deterministic:** `primaryPosition` in enum order (GK → ST), then `jerseyNumber` ascending with nulls last, then `fullName`.
- **No pagination.**
- **Invalid params must return 400, not 500.** This is a trap in the current code. An unknown enum value in a query param throws `MethodArgumentTypeMismatchException`, and a constraint on a `@RequestParam` (e.g. `@Min(18)`) throws the framework's method-validation exception. `GlobalExceptionHandler` maps **neither**, so both fall into `handleUnexpected` → 500. **Verify which exception types the installed Spring Framework version actually throws** (check it in the resolved jars, not from memory; the method-validation exception changed in Spring 6.1+). Add handlers for them in `GlobalExceptionHandler` that return 400 in the existing `ApiErrorResponse` shape, with one detail per invalid parameter. Cover each one with a `GlobalExceptionHandlerTest` case and a WebMvc test through the real endpoint.

## Create and update bodies

- `CreatePlayerRequest` and `UpdatePlayerRequest` are records in `squad`, validated with `@Valid`. The constraints mirror the entity exactly (see `Player.java` and spec section 05):
  - `fullName`: `@NotBlank @Size(max = 100)`. The entity setter already trims. Make sure a name that is only whitespace, or longer than 100 characters *after* trimming, is handled consistently, and test both.
  - `primaryPosition`: `@NotNull`. `secondaryPosition`: optional.
  - `jerseyNumber` 1–99, `heightCm` 140–220, `weightKg` 40–150: optional.
  - `dateOfBirth`: `@NotNull @AdultAge`.
  - `preferredFoot`: optional.
  - `medicalStatus`: optional on create (defaults to `FIT`), **required on update**. With a full replacement, a missing field means null.
  - Update only: `version`, `@NotNull Long`.
  - Both records implement `HasPositions` and carry `@DistinctPositions`. The record accessors satisfy the interface with no extra code, and the violation is already reported on `secondaryPosition` as a field error.
- **Full replacement:** on `PUT`, an optional field that is absent or null clears the stored value.
- **Unknown or forbidden body fields** (`clubId`, `active`, `id`, `createdAt`): check what the installed Jackson (Jackson 3 under Boot 4.1.1; see the note in `pom.xml`) plus Boot's defaults do with unknown properties, i.e. whether they are ignored or rejected. Whatever the behavior, add tests proving that a body with `"clubId": "<other club>"` and `"active": false` changes neither. Report which behavior you found.
- **Update flow, in this order:** load through club-scoped `findById` (→ 404) → released? (→ 409) → `request.version()` differs from the stored version? (→ 409) → apply all fields → `save` (a lost race → 409 via the existing handler; a jersey clash → 409 via the mapping above).
- Use a `ConflictException` subclass for the released case and for the version mismatch, each with a clear message. For the mismatch, the message tells the client to reload. Leave `GlobalExceptionHandler.CONCURRENT_MODIFICATION_MESSAGE` as it is.

## Verify against what's actually installed

Boot **4.1.1**. KAN-25 verified Spring Data MongoDB 5.1.1, driver 5.8.1, Hibernate Validator 9.1.3, and `mongo:7`. Check each point below in the resolved jars' source and prove it with a test. General knowledge isn't enough here. For each point, give in your summary the class/method you read and the test that proves it:

1. **The duplicate-key path on both writes.** A jersey clash on `insert` (create) and on a **versioned `save`** of an existing player (update, which KAN-25 found is a full `replaceOne` filtered on `_id` + `version`) both surface as `DuplicateKeyException`. Neither one is translated into `OptimisticLockingFailureException` or something else. Also: how do you reliably read the index name from the exception (the message, or the cause's `MongoWriteException` / `WriteError`)? Choose the most robust option, and prove that a *different* duplicate key is not misclassified. For example, insert a raw document with a duplicate `_id` through the repository.
2. **The query-param exceptions** from the list section above.
3. **Jackson's unknown-property behavior**, from the body section above.
4. **The `ClockProvider` zone**, from the list section above.

If any of these is different from what's described here and that changes the design, **stop and tell me** before you build around it.

## Read these first

- `squad/Player.java`, `PlayerRepository.java`, `HasPositions.java`, `DistinctPositions.java`, `Position.java`, `MedicalStatus.java`, `PreferredFoot.java`
- `auth/UserManagementController.java`, `UserResponse.java`, `InviteUserRequest.java`, `UserInvitationService.java` (the `DuplicateKeyException` → domain exception pattern), `UserPermissionLevelService.java`
- `common/GlobalExceptionHandler.java`, `ConflictException.java`, `NotFoundException.java`, `ClubContext.java`, `ClubScopedRepositoryImpl.java`, `AdultAgeValidator.java`
- Tests: `UserManagementControllerTest` + `AuthWebMvcTestConfig` (the WebMvc pattern behind the real security chain), `PermissionLevelHierarchyWebMvcTest`, `AuthFlowIntegrationTest` (end-to-end isolation), `PlayerRepositoryIntegrationTest`, `UserConcurrentWriteIntegrationTest` (deterministic races), and the ArchUnit tests (`EndpointAuthorizationTest`, `ClubScopedRepositoryMethodNamingTest`). **Reuse the existing Testcontainers setup. Don't create a new one.**

## Changes

1. `squad/PlayerController` (`/squad/players`), `squad/PlayerService`, the request/response records, the `ConflictException` subclasses, and the new repository method.
2. `GlobalExceptionHandler`: only the 400 handlers described above. Nothing else changes there.
3. Nothing in `auth` changes. `Player.java` doesn't change.

## Tests (deterministic, no `Thread.sleep`)

- **WebMvc (`PlayerControllerTest`), behind the real security chain:**
  - The full permission matrix: each of the 4 endpoints × `VIEW_ONLY` / `EDIT_PARTIAL` / `EDIT_FULL` / `ADMIN`, plus no token → 401. Use a parameterized test.
  - Every validation rule → 400 with the field in the details.
  - Invalid query params → 400.
  - `version` missing on update → 400.
  - 201 with `Location` on create.
  - The response never contains `clubId`.
- **Integration (Testcontainers):**
  - **Isolation:** club B can't see club A's player in the list (any `status`) or by id (404), and can't update it (404). The document stays unchanged.
  - **Jersey:**
    - A clash on create → 409.
    - A clash on update → 409.
    - A released player's number is free for a new player.
    - Two players with no number don't collide.
    - A deterministic concurrent race: two creates with the same number, exactly one wins, the other gets 409. Use a latch/barrier pattern like `UserConcurrentWriteIntegrationTest`.
    - A non-jersey duplicate key is not mapped to the jersey 409.
  - **Version:** a stale `version` → 409 and nothing is written. A save race → 409. A correct `version` → 200, and the version is incremented in the response.
  - **Released:** `GET` works and shows `active: false`. `PUT` → 409.
  - **Full replacement:** omitting an optional field on `PUT` clears it.
  - **Filters:**
    - Each param on its own, and a combination.
    - `position` does **not** match a player whose secondary position is the filter value.
    - Age boundaries with a fixed `Clock`: the day before and the day of a birthday, `minAge` and `maxAge` inclusive.
    - Each `status` value.
    - The sort order, including null jersey numbers last.
- **ArchUnit tests pass unchanged.** Don't loosen any rule.

## Docs (same commit as the change, per CLAUDE.md rule 7)

- `README.md`: add a "Squad API" subsection next to "Auth API", with the endpoints, levels, filters, the `version` requirement, and the 404 / 409 cases.
- **Don't edit `docs/spec.md`.** In your summary, list exactly which spec sections need updating and what should change. I expect at least: section 04, the endpoint → permission matrix; section 05, released players being read-only until re-activated and the `version` requirement for edits. Add anything else you find.

## Conventions

- Follow the existing style: package-private controllers and records where possible, Javadoc that explains *why*, no Lombok.
- Commits in English with conventional prefixes and `(KAN-26)`, e.g. `feat(squad): ...`, `fix(common): ...`, `test(squad): ...`, `docs: ...`. Split them logically.
- Run the full build (`mvn verify`, including Spotless / formatting and ArchUnit) before you report.
- **Don't push and don't open a PR.** I'll send a separate prompt for that after review.

## Your summary must include

1. The files you changed/added, per commit.
2. The four "verify against what's installed" points, each with the source you read and the test that proves it.
3. The exact HTTP status and body for each error case (400 body / 400 query / 404 / 409 jersey / 409 released / 409 stale version / 409 race).
4. The spec sections to update, with the proposed content.
5. Anything you had to decide that this prompt didn't cover, and anything left open.
