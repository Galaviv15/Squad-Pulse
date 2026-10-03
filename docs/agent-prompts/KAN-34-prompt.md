# KAN-34: Current user endpoint, `GET /auth/users/me`

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` must print the same hash, at or after `37d0ce3`, the KAN-31 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-34-current-user`.

## Context

Jira KAN-34, under Epic KAN-33 ("Club & Staff Administration"). It is the first ticket of that epic and a prerequisite for the Frontend MVP (Phase 3).

The frontend needs to know who is logged in, right after login and on every app load. It needs this for the header (name, title, club name) and to hide actions the user can't perform, such as edit buttons for `VIEW_ONLY`. The access token carries only `sub`, `clubId` and `permissionLevel`: no name, no title, no club name. No endpoint returns the caller today.

Read these first:
- `CLAUDE.md`: the access-control and error-handling paragraph, and rule 7
- `docs/spec.md`: section 04 (roles, endpoint table) and section 10 (tokens, deactivation)
- README "Auth API"
- in `auth`: `UserResponse`, `UserManagementController`, `AuthenticatedUser`, `JwtAuthenticationFilter`, `JwtService`, `AuthService.refresh`, `User`, `Club`, `ClubRepository`, `UserRepository`
- in `common`: `ClubScopedRepositoryImpl.findById`, `UnauthorizedException`, `GlobalExceptionHandler`
- tests: `UserManagementControllerTest`, `AuthFlowIntegrationTest`, `TestAccessTokens`, `AuthWebMvcTestConfig`, `EndpointAuthorizationRules`

**Facts I checked on master (`37d0ce3`). Re-confirm them; don't take them on faith:**
- `User` is a `ClubScopedEntity`. `userRepository.findById(id)` runs a query scoped to the clubId in `ClubContext`, which `JwtAuthenticationFilter` sets from the token. A user id from another club therefore comes back `Optional.empty()`, not a `CrossClubAccessException`.
- `Club` is `@NotClubScoped`. `ClubRepository` is a plain `SimpleMongoRepository`.
- `UserResponse` is package-private and maps the **stored** `permissionLevel`.
- `UserManagementController`'s class Javadoc says only `ADMIN` may use it.
- `AuthService.refresh` refuses (401) a user who no longer exists, is deactivated, has no password, or whose sessions were invalidated.
- Spec section 10 says an access token keeps working after deactivation until it expires (at most 15 minutes).
- There is no deactivation endpoint yet: `active = false` can only be set directly in the DB.

## Decisions agreed with Gal (implement exactly these)

1. **Module: `auth`.** `/me` lives in `auth`, next to `User` and `Club`, and so will the club logo (KAN-30) and the staff photo (KAN-32). Don't create a new module and don't make `User` or `UserResponse` public. Update `auth/package-info.java` to say the module also covers the current-user profile (and, later, club and staff administration, Epic KAN-33).
2. **Effective permission level.** The response's `permissionLevel` is the level in the **caller's current access token** (`AuthenticatedUser.permissionLevel()`), i.e. what the server enforces right now. It is not the stored value. Every other field comes from the DB. Document this in the Javadoc, README and spec list: after a level change, `/me` shows the new level only after the next `/auth/refresh`, exactly like every other endpoint's authorization.
3. **401 for a user who is deactivated, deleted, or not found in the token's club.** Use the same generic message the security entry point uses (`"Authentication required"`; confirm the exact text in `SecurityConfig`), via a new `auth` exception that extends `common.UnauthorizedException`. The body must not say why (deactivated vs deleted). This is a deliberate exception to the spec section 10 rule that an access token keeps working after deactivation. It is intended: on app load the frontend gets 401 from `/me`, tries `/auth/refresh`, that fails too, and the user lands on login.
   - Do **not** check `sessionsInvalidatedAt` or `passwordHash` here. Access tokens stay valid after a password reset (spec section 10), and `AuthenticatedUser` doesn't carry `iat`. State this in the Javadoc.
4. **No `hasPhoto`.** It is added in KAN-32.

## Design

- **New controller** `auth.CurrentUserController`: `GET /auth/users/me`, `@PreAuthorize("hasAuthority('VIEW_ONLY')")`, `@AuthenticationPrincipal AuthenticatedUser caller`. Don't add it to `UserManagementController` (that one is ADMIN-only by design). No path id: the endpoint always returns the caller.
- **New service method** (e.g. `auth.CurrentUserService.currentUser(AuthenticatedUser)`), so the controller has no logic:
  - load the user with `userRepository.findById(caller.userId())` (club-scoped). Empty or `!active` → the 401 exception.
  - load the club with `clubRepository.findById(caller.clubId())`. The clubId must **only** come from the token, never from the request.
  - if the club is missing while the user exists, that's a data-integrity bug: throw an exception that ends in the generic **500** (logged at ERROR by the KAN-31 handler), not a 401 or 404. Its message must not reach the client; check that it doesn't.
- **New response record** (package-private, e.g. `CurrentUserResponse`):
  - fields: `id, email, fullName, title, permissionLevel, dateOfBirth, active, club { id, name }`
  - same JSON names and order as `UserResponse` plus `club`, so the frontend can reuse the type
  - `active` is always `true` in a 200; keep it for parity and say so in the Javadoc
  - never any password, version or session fields. Check how `User` serializes (`version`, `sessionsInvalidatedAt`, `passwordHash`) and assert in a test that none of them appear.
- Read-only. No write to `User` and no `@Version` interaction.
- **Routing:** `/auth/users/me` must not collide with `/auth/users/{id}/permission-level` (that one is PATCH and takes a longer path). Verify against the installed Spring Framework that a literal segment wins over `{id}`, in case a `GET /auth/users/{id}` is ever added. Report it in one line; don't add that endpoint.
- **Security:** the endpoint must not be public. Don't touch `PUBLIC_ENDPOINTS`.

## Verify against what's installed

Don't rely on general knowledge. Check these in the versions actually in `pom.xml` / `./mvnw dependency:tree` (Boot 4.1.1, Spring Framework 7.0.9, Spring Security, Jackson 3 `tools.jackson`):
- how `@AuthenticationPrincipal` resolves `AuthenticatedUser` (the existing PATCH handler already uses it)
- path-pattern precedence (literal vs `{id}`)
- the exact 401 body the entry point writes vs the one `GlobalExceptionHandler` writes for an `UnauthorizedException`. They must be identical in shape and message, so a client can't tell "no token" from "deactivated". If they differ, report it and make them match.

## Tests

1. **Controller slice** (`@WebMvcTest`, real security chain via `AuthWebMvcTestConfig`, tokens from `TestAccessTokens`):
   - each level (`VIEW_ONLY`, `EDIT_PARTIAL`, `EDIT_FULL`, `ADMIN`) → 200
   - no token or an invalid token → 401
   - the response maps every field, and `permissionLevel` equals the token's level
2. **Integration** (real Mongo, through HTTP), the cases that matter:
   - happy path: every field from the DB, plus `club.id` / `club.name`
   - **effective vs stored level:** the stored level is changed after the token was issued (via `PATCH` by an admin or directly in the repo) → `/me` still returns the token's level, while the name etc. come from the DB. After a real `/auth/refresh`, `/me` returns the new level.
   - deactivated user (`active=false` set in the DB) with a valid token → 401, with the same body as no token
   - deleted user → 401
   - **club isolation:** a valid token with `clubId` = A and `sub` = a user that exists in club B → 401, and no data from B leaks. Mint the token with the test signing key the existing tests use.
   - club document missing → 500, generic body, ERROR logged (reuse the KAN-31 log capture approach)
   - the JSON has no `passwordHash`, `version`, `sessionsInvalidatedAt` or `clubId` at the top level (only `club.id`)
   - read-only: the user's `version` is unchanged after the call
3. **Regression:** `EndpointAuthorizationRules` / `EndpointAuthorizationTest` and `PublicEndpointsConsistencyTest` pass with the new handler. Existing tests pass unchanged.

Run `./mvnw verify` with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25). Everything must be green and Spotless clean. Report the total test count (it was 747 after KAN-31).

## Carry-over from KAN-31 (separate commit)

`common/GlobalExceptionHandler.java`, class Javadoc (around line 52) still says "Messages never echo client input (a rejected value, a `Content-Type`)". KAN-31 narrowed that rule in CLAUDE.md and README (commit `428b5e1`): rejected values and request headers are never echoed, and only the 404 for an unknown path and the 405 name the request's method and path. Make the Javadoc sentence match CLAUDE.md. Javadoc only, no code change. Commit it alone as:
`docs(common): align the no-echo Javadoc with the narrowed rule (KAN-34)`

## Docs

- **README "Auth API":** add a row for `GET /auth/users/me` (access: any authenticated user). Cover the response shape including `club`, that `permissionLevel` is the token's effective level, 401 for missing/invalid token or deactivated/deleted user, and that there's no path id. Update the status line at the top if appropriate.
- **CLAUDE.md:** only if something there becomes inaccurate (e.g. the `auth` module description). Say what you changed, or that nothing needed changing.
- **`docs/spec.md`: do NOT edit it.** In your summary, list exactly what needs updating, with the exact facts. Expected:
  - section 04: endpoint table row, and the effective-level note
  - section 10: the `/me` exception to "access token keeps working after deactivation"
  - section 02 / module description, if `auth` is described narrowly
  - status / roadmap

## Commits

Small, focused commits, for example:
- `feat(auth): add GET /auth/users/me returning the caller and their club (KAN-34)`
- `test(auth): ... (KAN-34)`
- `docs(common): align the no-echo Javadoc with the narrowed rule (KAN-34)`

Docs go in the same commit as the change they describe (rule 7). **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. The exact response JSON for a sample user, and the exact 401 body (for no token and for a deactivated user, side by side).
3. Evidence for each item in "Verify against what's installed".
4. The test list with counts, and the full build result (command, outcome, total count).
5. Deviations from this prompt, and why.
6. The spec update list.
7. Open ends and risks.
8. Print the full final `CurrentUserController`, the service class, and the response record, so they can be reviewed directly.
