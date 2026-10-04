# KAN-37: User deactivation and re-activation

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `fe2263a` (the KAN-36 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-37-user-deactivation`.

## Context

This is Jira KAN-37, under Epic KAN-33 ("Club & Staff Administration"). The `User.active` flag has existed since Phase 1 so a Club Manager can cut a departed staff member's access without deleting the account. It is already **enforced** almost everywhere, but **nothing can change it**: no endpoint sets it. This ticket adds `deactivate` and `reactivate`, and closes two security gaps that only matter once deactivation is real.

**The Jira ticket leaves several points "to decide". They are decided. Follow this prompt.**

Read these first:
- `CLAUDE.md`: the access-control and error-handling paragraph, and rule 7
- `docs/spec.md`: section 04 (`/me`, staff photo, staff list and `activated` paragraphs, endpoint table), section 09 (invite / activation, the `User.active` paragraph), section 10 (refresh tokens, "Access tokens themselves can't be revoked early…", the `/me` exception, "Concurrent writes")
- README: "Auth API"
- in `auth`: `User` (`active`, `sessionsInvalidatedAt`, `version`), `UserRepository`, `UserResponse`, `UserManagementController`, `UserPermissionLevelService`, `CannotChangeOwnPermissionLevelException`, `UserWriteRetry`, `AuthService` (`login`, `refresh`, `invalidatedSince`), `RefreshTokenService`, `PasswordResetService` (`requestReset`, `resetPassword`, and how it injects a `Clock`), `CurrentUserService`, `CurrentUserUnavailableException`, `StaffPhotoService`, `DeactivatedUserException`, `StaffListService`, `UserInvitationService`, `package-info.java`
- in `squad`: `PlayerService.release` / `reactivate` (the player equivalent; note the **differences** below)
- tests: `UserManagementControllerTest`, `UserPermissionLevelServiceTest`, `UserConcurrentWriteIntegrationTest`, `UserLoadHook`, `AuthFlowIntegrationTest`, `PasswordResetFlowIntegrationTest`, `CurrentUserIntegrationTest`, `StaffListIntegrationTest`, `StaffPhotoIntegrationTest`, `EndpointAuthorizationRules` / `EndpointAuthorizationTest`, `PublicEndpointsConsistencyTest`

**Facts I checked on master (`fe2263a`). Re-confirm them; don't take them on faith:**
- `active == false` is already rejected by: `AuthService.login` (generic 401), `AuthService.refresh` (revokes the family and returns 401), `PasswordResetService.requestReset` (silently sends nothing), `PasswordResetService.resetPassword` (re-checked on every retry attempt), `CurrentUserService.currentUser` (`/me` → generic 401 via `CurrentUserUnavailableException`), and `StaffPhotoService` writes (409 `DeactivatedUserException`).
- No production code calls `User.setActive(...)`.
- `AuthService.refresh` checks `active` **only when a refresh happens**. A refresh family whose cookie isn't presented while the user is deactivated stays in Redis, valid for up to 30 days.
- `invalidatedSince` rejects a family whose `issuedAt` is before `User.sessionsInvalidatedAt` (millisecond precision). Today only a password reset sets that field.
- `JwtAuthenticationFilter` does not look the user up, so an already-issued access token (≤ 15 min) keeps working after deactivation. Spec section 10 accepts this on purpose; `/me` is the one exception.
- `UserPermissionLevelService` refuses a self-change (409) instead of a "last ADMIN" count, and uses `UserWriteRetry` (reload + re-apply, 409 only when all attempts conflict).
- The last full build (KAN-36) had 896 tests.

## Decisions agreed with Gal (implement exactly these)

1. **Endpoints**, in `UserManagementController`, both `@PreAuthorize("hasAuthority('ADMIN')")`, no request body:
   - `POST /auth/users/{id}/deactivate`
   - `POST /auth/users/{id}/reactivate`

   Both return `200` with the target's `UserResponse` (with the real `hasPhoto` and `activated`). A user that doesn't exist **or belongs to another club** → the same `404` ("User not found") as permission-level, via the club-scoped `findById`. Code stays in `auth`, in a new service (e.g. `UserActivationService`). Don't fold it into `UserPermissionLevelService`.

2. **Idempotent: an already-deactivated / already-active target is a `200` no-op.** Return the current state and **don't save**: no `version` bump, no `updatedAt` change, no `sessionsInvalidatedAt` change. Check this on the reloaded user on **every** retry attempt, the same way `changePermissionLevel` does. This differs from players (`release` of a released player is a 409) on purpose: players carry a client `version` because they are form edits. These are absolute single-field commands with no version in the request. Say this in the Javadoc.

3. **No self-deactivation / self-reactivation:** `{id}` equal to the caller's own id → `409`, with a new `CannotChangeOwnActiveStatusException extends ConflictException`. Suggested message: "You can't deactivate or reactivate yourself; another ADMIN must do it". It uses the same reasoning as `CannotChangeOwnPermissionLevelException`: there is no read-then-write "last ADMIN" count, and the caller always remains an active ADMIN. Deactivating **another** ADMIN is allowed. The target's permission level is left unchanged.

4. **Sessions: both operations set `sessionsInvalidatedAt`.** This is the main security point of the ticket.
   - **Deactivate** sets `active = false` and `sessionsInvalidatedAt = now`. Without it, a refresh cookie that wasn't presented while the user was deactivated would come back to life on reactivation.
   - **Reactivate** sets `active = true` and **also** `sessionsInvalidatedAt = now`. No legitimate session can exist while the user is inactive (login is refused), so this costs nothing. It also covers users deactivated **directly in the database** before this ticket existed, which never got the deactivation timestamp.
   - Take `now` from an injected `java.time.Clock`, following the `PasswordResetService` pattern (a package-private constructor for tests). Compute it **inside each retry attempt**. **Never move the stored value backwards**: if the reloaded user already has a later `sessionsInvalidatedAt` (e.g. from a concurrent password reset), keep the later one. Explain why in the Javadoc. An earlier timestamp computed before a retry could otherwise let a session started in between survive.
   - The `refresh` logic doesn't change. Access tokens already issued keep working until they expire (≤ 15 min), as spec section 10 already says. The `/me` exception and the caller re-check below are the only exceptions.

5. **Caller re-check, against the 15-minute window.** A deactivated ADMIN still holds a valid access token for up to 15 minutes. Without a check, they could use it to deactivate or demote the club's last remaining ADMIN, which leaves the club with no one able to manage users. The only fix for that today is manual database work. So every **user-management write** in `UserManagementController` re-reads the **caller** first and answers the generic `401` (`CurrentUserUnavailableException`, the same body as a request without a token) if the caller no longer exists in their club or is deactivated. The writes are: `invite`, `PATCH /{id}/permission-level`, `deactivate` and `reactivate`.
   - Put it in one small shared place (e.g. a package-private `ActiveCallerCheck` / method used by the three services). Don't copy it into each service. Reuse `CurrentUserService`'s logic or extract it if that's clean, and explain your choice.
   - Order: caller re-check (401) → self check (409) → target lookup (404) → change. A deactivated caller always gets 401, whatever the target.
   - Do it **once, before** the retry loop. Not per attempt.
   - One club-scoped read; no per-request lookup anywhere else. The read-only `GET /auth/users` is unchanged (no re-check). Note in the Javadoc that two admins deactivating each other **in the same instant** could both pass this check; that is accepted (deliberate, concurrent, and rare).
   - Update `UserPermissionLevelService`'s and `CurrentUserService`'s Javadoc where they describe the token trade-off.

6. **Concurrency (KAN-24):** both are absolute single-field changes, so use `UserWriteRetry`. Reload the target and re-apply per attempt, and re-check the "already in that state" no-op per attempt. If every attempt conflicts → 409 via the existing handler.

7. **What stays:** the password hash, permission level, title, photo and the staff-list entry are all kept. Reactivation sends no email and needs no new password. A deactivated user who never activated (no password) can be deactivated and reactivated too; `activated` stays `false`. Staff photo writes on a deactivated user stay 409 (already implemented). The staff list already shows `active` and sorts deactivated users last. Don't change it.

8. **Pending activation / reset codes:** don't add anything. `resetPassword` already re-checks `active` when the code is used. A code that was issued before deactivation and is still within its TTL after reactivation is accepted (it went to the user's own mailbox). Mention it under "open ends".

## Verify against what's installed

Don't rely on general knowledge. Check against `pom.xml` / `./mvnw dependency:tree` (Boot 4.1.1, Spring Data MongoDB 5.1.1, Spring Security 7.1.1, Jackson 3.1.5):
- that a no-op really issues **no** write. Prove it in an integration test (`version` and `updatedAt` identical before and after), not by reading the code.
- that `findById` on `UserRepository` is club-scoped for both the target and the caller lookup (quote the code path and prove it with the cross-club test).
- that the `Instant` stored in `sessionsInvalidatedAt` round-trips with millisecond precision, as `invalidatedSince` assumes. The existing reset tests may already prove it; cite them or add one.
- that `EndpointAuthorizationTest` picks up both new handlers.

## Tests

1. **Controller slice** (`@WebMvcTest`, real security chain via `AuthWebMvcTestConfig`, `TestAccessTokens`): for both endpoints, `ADMIN` → 200; `VIEW_ONLY`, `EDIT_PARTIAL`, `EDIT_FULL` → 403; no token → 401. Exact 404, 409 (self) and 401 (caller re-check) bodies.
2. **Service unit tests:** check order (401 → 409 → 404); a no-op doesn't call `save`; `sessionsInvalidatedAt` is set on both operations from the injected clock; it is never moved backwards; a retry re-applies to the reloaded user.
3. **Integration (real Mongo + Redis, through HTTP). Each of these is required:**
   - **Deactivation ends everything:** user U logs in (refresh cookie C1). Admin deactivates U → U's login is 401 (generic body), refresh with C1 is 401, `/me` with U's still-valid access token is 401, forgot-password sends no email, and the staff list shows U with `active: false`.
   - **The resurrection test (the key one):** U logs in and keeps cookie C2, which is **never presented while U is deactivated**. Admin deactivates, then reactivates U → refresh with C2 is **401**. A fresh login then works, and its refresh works.
   - **Legacy data:** set `active = false` directly in Mongo with no `sessionsInvalidatedAt`, while U holds an unused cookie C3. Reactivate via the API → C3 is rejected.
   - **Idempotent:** deactivating a deactivated user, and reactivating an active one, returns 200 with the current state and changes nothing (`version`, `updatedAt`, `sessionsInvalidatedAt` identical).
   - **Self:** an admin deactivating themselves → 409, and nothing changes.
   - **Another ADMIN** can be deactivated, and keeps `permissionLevel: ADMIN`.
   - **Caller re-check:** admin A1 holds an access token. A1 is deactivated by admin A2. A1's still-valid token on `deactivate`, `reactivate`, `permission-level` and `invite` → 401, and no target is changed (or created, for invite). The same token still works on a read endpoint that doesn't re-check (e.g. `GET /squad/players`), which documents the accepted window.
   - **Club isolation:** club A's admin on a club-B user id → 404 for both endpoints, and the club-B user is unchanged. Do it the other way round too.
   - **Concurrency** (`UserLoadHook`, no sleeps): a permission-level change landing between deactivation's load and save → both survive. A password reset landing in the same place → the deactivation wins, and `sessionsInvalidatedAt` is the later of the two. A deactivation landing inside a reset's load/save → the reset fails, as today (keep or extend the existing test).
   - Reactivated users can be changed again: a staff photo upload works after reactivation.
4. **Regression:** existing permission-level and invite tests are updated for the caller re-check where needed. `EndpointAuthorizationTest`, `PublicEndpointsConsistencyTest` and ArchUnit pass. Everything else passes unchanged.

Run `./mvnw verify` with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25). Everything green, Spotless clean. Report the total test count (896 after KAN-36).

## Docs

- **README** ("Auth API"): both endpoints. Cover ADMIN only, no body, 200 + user response, idempotent no-op, self → 409, other club → 404, what deactivation does (sessions ended, login / refresh / forgot-password / `/me` refused, access tokens ≤ 15 min, data kept) and the caller re-check on user-management writes.
- **CLAUDE.md:** only if something becomes inaccurate. Say what you changed, or that nothing needed changing.
- **`docs/spec.md`: do NOT edit it.** In your summary, list exactly what needs updating, with the exact facts. Expected at least:
  - section 04: the two endpoints and their rules, plus endpoint-table rows.
  - section 04: the sentence "a deactivated user keeps `activated: true`" is wrong for a user who never activated. `activated` simply doesn't change on deactivation.
  - section 09: the `User.active` paragraph now has an endpoint.
  - section 10: deactivation now sets `sessionsInvalidatedAt`, and so does reactivation. The caller re-check is a second exception next to `/me`. In "Concurrent writes", deactivation is no longer "future".
  - the status line and the roadmap (section 13).

## Commits

Small, focused commits, for example:
- `feat(auth): re-check the caller is still active on user-management writes (KAN-37)`
- `feat(auth): add user deactivation and re-activation (KAN-37)`
- `test(auth): ... (KAN-37)`

Docs go in the same commit as the change they describe (rule 7). **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. Sample JSON: deactivate and reactivate responses. The exact 401 / 403 / 404 / 409 bodies.
3. Evidence for each item in "Verify against what's installed".
4. The test list with counts, and the full build result (command, outcome, total count).
5. Deviations from this prompt, and why.
6. The spec update list.
7. Open ends and risks.
8. Print the full final activation service, the caller-check class, `UserManagementController` and the diff of `UserPermissionLevelService` and `UserInvitationService`, so they can be reviewed directly.
