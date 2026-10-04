# KAN-36: Staff list endpoint, GET /auth/users

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `4a0b72d` (the KAN-32 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-36-staff-list`.

## Context

This is Jira KAN-36, under Epic KAN-33 ("Club & Staff Administration"). Today an `ADMIN` can invite a user and change a user's permission level, but there is no way to see who the club's users are. This ticket adds the list: the basis for a staff-management screen, and for KAN-37 (user deactivation), which needs something to act on.

It also adds an `activated` flag to the user responses, so an admin can tell an invited user who hasn't set a password yet from one who has.

**The Jira ticket leaves several points "to decide". They are decided — follow this prompt.**

Read these first:
- `CLAUDE.md`: the access-control and error-handling paragraph, and rule 7
- `docs/spec.md`: section 03 (central club-scoping layer, "Filtering happens in memory"), section 04 (`/me` paragraph, staff photo paragraph, endpoint table), section 09 (invite / activation), section 10 (refresh cookie, deactivation, concurrent writes)
- README: "Auth API" and the staff photo section
- in `common`: `ClubScopedRepositoryImpl` (`findAll`), `ImageStorage` (`ownerIdsWithImage`), `ImageKind`
- in `auth`: `User`, `UserRepository`, `UserResponse`, `CurrentUserResponse`, `CurrentUserService`, `UserManagementController`, `UserInvitationService`, `PasswordResetService` (where `passwordHash` gets set on activation), `ClubBootstrapService`, `StaffPhotoService`, `package-info.java`
- in `squad`: `PlayerController.list` / `PlayerService.list`, `SQUAD_ORDER`, `playerIdsWithPhoto()`: the existing pattern for a club-scoped list with one photo query
- tests: `UserManagementControllerTest`, `CurrentUserIntegrationTest`, `CurrentUserControllerTest`, `CurrentUserServiceTest`, `StaffPhotoIntegrationTest`, the squad list integration test, `EndpointAuthorizationRules` / `EndpointAuthorizationTest`, `PublicEndpointsConsistencyTest`

**Facts I checked on master (`4a0b72d`). Re-confirm them; don't take them on faith:**
- `UserManagementController` is mapped to `/auth/users`, documented as `ADMIN`-only, and has `POST /invite` and `PATCH /{id}/permission-level`. `GET /auth/users/me` is in `CurrentUserController`.
- `UserResponse` is `(id, email, fullName, title, permissionLevel, dateOfBirth, active, hasPhoto)`, built by `UserResponse.from(User, boolean hasPhoto)`.
- `CurrentUserResponse`'s Javadoc requires **the same fields, JSON names and order as `UserResponse`**, plus `club`, so the client can reuse one user type.
- `User.passwordHash` is `null` for an invited user until they set a password through the activation code. It's set in `PasswordResetService` (activation / reset) and `ClubBootstrapService` (the owner). Nothing sets it back to `null`.
- `UserRepository` extends `MongoRepository<User, String>` with no custom list method. `findAll()` goes through `ClubScopedRepositoryImpl` and is club-scoped.
- `StaffPhotoService` has `hasPhoto(User)` but no list-wide method. `PlayerService.playerIdsWithPhoto()` calls `imageStorage.ownerIdsWithImage(ImageKind.PLAYER_PHOTO)`.
- The last full build (KAN-32) had 878 tests.

## Decisions agreed with Gal (implement exactly these)

1. **Endpoint:** `GET /auth/users`, in `UserManagementController`, `@PreAuthorize("hasAuthority('ADMIN')")`. Code stays in `auth`. This also settles the epic's module-placement question, as KAN-30/32/34 already did in practice.
   - `ADMIN` only: the list exposes emails, dates of birth, permission levels and deactivated users. It's a management screen, not a staff directory. If a directory for everyone is ever needed, it gets its own slimmer response in a separate ticket. Say this in the handler's Javadoc.
   - The path is under `/auth`, so the browser sends the `Path=/auth` refresh cookie with it. Accepted, the same as `/auth/users/me`: it's not an image and isn't fetched in bulk. Don't move it.
2. **Response:** a plain JSON array of `UserResponse` (same as `GET /squad/players`). No pagination, no wrapper object, no query parameters. A club has a handful of staff users.
3. **Scope:** all users of the caller's club, **including deactivated ones** (`active: false`) and **invited users who haven't activated yet** (`activated: false`). Never users of another club. The caller is in the list too.
4. **Order (deterministic, in Java):** active users first, then by `fullName`, then by `id`. Use a `Comparator` with `nullsLast` like `SQUAD_ORDER` (a stored document may lack a field). Plain `String` order, no Hebrew collation (same accepted limitation as the squad list). Put the comparator in a named constant and document it.
5. **Queries: exactly two per call.** One club-scoped `userRepository.findAll()`, and one `imageStorage.ownerIdsWithImage(ImageKind.STAFF_PHOTO)` (add e.g. `StaffPhotoService.userIdsWithPhoto()`, mirroring `playerIdsWithPhoto()`). **Never** a per-user `hasPhoto` call. No `MongoTemplate`, no new custom repository method (spec section 03).
6. **New field `activated`** (boolean), = `passwordHash != null`:
   - `UserResponse`: `(..., active, hasPhoto, activated)`, i.e. the new field goes **last**, after `hasPhoto`.
   - `CurrentUserResponse`: add it too, in the same place (`..., active, hasPhoto, activated, club`), to keep the documented parity. On `/me` it's always `true` (a user without a password can't log in, so can't have a token). Document that next to the existing "`active` is always `true`" note.
   - Invite response: always `false`.
   - Permission-level change: the target's real value (an admin may change the level of a not-yet-activated user; that's allowed today).
   - Compute it in the `from(...)` factories from the `User` (no query). The response must still **never** expose `passwordHash` itself or anything derived from it beyond this boolean.
7. **Read-only.** The list never writes a `User`, so it can't conflict with a concurrent write (KAN-24). Say so in the Javadoc.

## Design

- Put the list logic in a small service (e.g. `auth.StaffListService`, or a method on an existing service if that's clearly cleaner, explain your choice), so the controller has no logic beyond mapping. It returns the sorted users and the set of ids with a photo, or the finished `List<UserResponse>`. Your call; keep `UserResponse` free of storage calls.
- Javadoc on the service: club isolation (club-scoped `findAll` + club-scoped `ownerIdsWithImage`; nothing is read from the request), the two-query budget, the order, why deactivated and not-activated users are included, read-only.
- Update `UserManagementController`'s class Javadoc if needed, and `auth/package-info.java`.
- Security: no `SecurityConfig` change expected. `GET /auth/users` must not be public. Don't touch `PUBLIC_ENDPOINTS`. Confirm `EndpointAuthorizationTest` picks up the new handler (it must have `@PreAuthorize`).
- Check that `GET /auth/users` and `GET /auth/users/me` don't clash: `/me` must still be served by `CurrentUserController` (a test proves it).

## Verify against what's installed

Don't rely on general knowledge. Check against the versions actually in `pom.xml` / `./mvnw dependency:tree` (Boot 4.1.1, Spring Data MongoDB 5.1.1, Spring Framework 7.0.9, Spring Security 7.1.1):
- that `findAll()` on `UserRepository` really is routed through `ClubScopedRepositoryImpl` (quote the code path, and prove it with the club-isolation test below).
- that `ownerIdsWithImage` is club-scoped (quote `GridFsImageStorage`'s query helper).
- the exact query count: prove "two queries" with a test (e.g. a unit test with mocks verifying `findAll` once, `ownerIdsWithImage` once, and no `exists`/`hasPhoto` calls), not just by reading the code.
- how Jackson 3 (`tools.jackson`) serializes the new `activated` component: name and position, asserted in a JSON test.

## Tests

1. **Controller slice** (`@WebMvcTest` with the real security chain via `AuthWebMvcTestConfig`, tokens from `TestAccessTokens`):
   - `GET /auth/users`: `ADMIN` → 200; `VIEW_ONLY`, `EDIT_PARTIAL`, `EDIT_FULL` → 403; no token → 401.
   - JSON shape: exact field names and order, including `activated`; no `passwordHash`, `version`, `clubId`, `sessionsInvalidatedAt`, `createdAt`, `updatedAt`.
2. **Service unit test:** order (active before inactive; by name; `id` tiebreak; null name sorted last), `hasPhoto` from the id set, `activated` from `passwordHash`, and the two-query budget.
3. **Integration** (real Mongo, through HTTP):
   - a club with: the admin (activated), an activated active user with a photo, an invited user (no password, `activated: false`), a deactivated user (set `active=false` directly in Mongo). The list returns all four, in the agreed order, with the right `active` / `activated` / `hasPhoto`.
   - **club isolation:** club B has its own users (one with a photo, and one whose id is in a club-A-like shape if that's cheap). Club A's admin sees none of club B's users, and B's photos don't affect A's `hasPhoto`. And the reverse.
   - after an invite, the new user appears with `activated: false`; after they activate through the real code flow (or by setting the password the way the existing activation tests do), `activated: true`.
   - `GET /auth/users/me` still returns the caller (not the list), now with `activated: true`.
   - the list never changes a user document (`version` / `updatedAt` identical before and after).
4. **Regression:** update the existing `/me`, invite and permission-level tests for the new field (invite → `activated: false`; permission-level change on an invited user → `false`, on an activated one → `true`). `EndpointAuthorizationTest`, `PublicEndpointsConsistencyTest` and the ArchUnit tests pass. All other tests pass unchanged.

Run `./mvnw verify` with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25). Everything green and Spotless clean. Report the total test count (878 after KAN-32).

## Docs

- **README:** document `GET /auth/users` in "Auth API" (`ADMIN` only, plain array, no pagination, includes deactivated and not-yet-activated users, order, `hasPhoto` / `activated`). Add `activated` to the `/me`, invite and permission-level response descriptions.
- **CLAUDE.md:** only if something there becomes inaccurate. Say what you changed, or that nothing needed changing.
- **`docs/spec.md`: do NOT edit it.** In your summary, list exactly what needs updating, with the exact facts. Expected at least: section 04 (the new endpoint and its rules; the endpoint table row; `activated` on `/me` and the user responses, and its meaning), section 09 if the invite/activation text should mention `activated`, the status line and the roadmap (section 13).

## Commits

Small, focused commits, for example:
- `feat(auth): expose activated on user responses and GET /auth/users/me (KAN-36)`
- `feat(auth): add the club staff list at GET /auth/users (KAN-36)`
- `test(auth): ... (KAN-36)`

Docs go in the same commit as the change they describe (rule 7). **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. Sample JSON: `GET /auth/users` (the four-user club above), `/me`, invite response, permission-level response. The exact 403 and 401 bodies for `GET /auth/users`.
3. Evidence for each item in "Verify against what's installed".
4. The test list with counts, and the full build result (command, outcome, total count).
5. Deviations from this prompt, and why.
6. The spec update list.
7. Open ends and risks.
8. Print the full final list service class and `UserManagementController`, and the diffs of `UserResponse`, `CurrentUserResponse`, `CurrentUserService` and `StaffPhotoService`, so they can be reviewed directly.
