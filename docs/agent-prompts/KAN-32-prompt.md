# KAN-32: Staff photo, upload, serve and remove

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `bd6ff84` (the KAN-30 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-32-staff-photo`.

## Context

This is Jira KAN-32, under Epic KAN-33 ("Club & Staff Administration"). It is the last backend item before the Frontend MVP: the app header shows the current user's photo, and future staff screens show everyone's.

Each staff user (`auth.User`) gets an optional profile photo. Everything security-relevant already exists from KAN-29 (player photo) and KAN-30 (club logo): `common.ImageStorage` (GridFS, club-scoped by construction), `ImageValidator` (JPEG/PNG/WebP sniffed by content, no SVG, size limit), the multipart limits / 413, `nosniff`, `Cache-Control: no-store`. This ticket adds a third image kind. It must not re-implement any of that.

**Parts of the Jira ticket text are out of date. Follow this prompt, not the ticket, where they differ:**
- The ticket says there is no `/me`. There is: `GET /auth/users/me` (KAN-34, `auth.CurrentUserController`).
- The ticket proposes `/auth/users/me/photo` and `/auth/users/{id}/photo`. **Don't use `/auth`** — see decision 1.
- The epic (KAN-33) and module (`auth`) are already decided.

Read these first:
- `CLAUDE.md`: the access-control and error-handling paragraph, and rule 7
- `docs/spec.md`: section 03 (image-store paragraph), section 04 (`/me` paragraph, endpoint table, club logo text), section 05 (player photo bullets), section 10 (refresh cookie, deactivation)
- README: "Auth API", the club logo section, and the player photo part of the squad API
- in `common`: `ImageStorage`, `GridFsImageStorage`, `ImageKind`, `ImageOwner`, `ImageValidator`, `ValidatedImage`, `StoredImage`, `ClubContext`, `ConflictException`, `NotFoundException`, `GlobalExceptionHandler`
- in `squad`: `PlayerController` / `PlayerService` photo methods, `ReleasedPlayerException`
- in `auth`: `User`, `UserRepository`, `UserResponse`, `UserManagementController`, `UserInvitationService`, `UserPermissionLevelService`, `CurrentUserController`, `CurrentUserService`, `CurrentUserResponse`, `ClubLogoController`, `ClubLogoService`, `AuthController` (`REFRESH_COOKIE_PATH`), `SecurityConfig`, `package-info.java`
- tests: `ClubLogoControllerTest`, `ClubLogoIntegrationTest`, `ClubLogoUploadLimitIntegrationTest`, `PlayerPhotoIntegrationTest`, `CurrentUserIntegrationTest`, the `CurrentUserController` slice test, the `UserManagementController` tests, `EndpointAuthorizationRules`, `GridFsRules`, `PublicEndpointsConsistencyTest`

**Facts I checked on master (`bd6ff84`). Re-confirm them; don't take them on faith:**
- `ImageKind` is `PLAYER_PHOTO, CLUB_LOGO`.
- `AuthController.REFRESH_COOKIE_PATH = "/auth"`; `ClubLogoController`'s Javadoc explains why the logo is not under `/auth`. `GET /auth/users/me` *is* under `/auth` (not an image; leave it alone).
- `User` is a `ClubScopedEntity` with `@Version` (KAN-24) and `boolean active`. There is no endpoint that deactivates or permanently deletes a user.
- `UserRepository.findById` is club-scoped: a user of another club is not found.
- `UserResponse` is `(id, email, fullName, title, permissionLevel, dateOfBirth, active)`. `CurrentUserResponse` has the same fields in the same order plus `club`, and its Javadoc requires that parity.
- `ReleasedPlayerException extends ConflictException` → 409.
- The last full build after KAN-30 had 811 tests.

## Decisions agreed with Gal (implement exactly these)

1. **Paths: `/users/me/photo` (the caller's own) and `/users/{id}/photo` (any user of the caller's club). Code in `auth`.** Deliberately **not** under `/auth`: the browser attaches the `Path=/auth` refresh cookie to every request below `/auth`, and staff photos will be fetched often (header, staff lists). Put a short comment on the controller explaining this, pointing at `ClubLogoController`'s identical reasoning, so nobody moves it into `/auth/users/...` to "match" the other user endpoints.
2. **Permissions:**
   - `GET /users/me/photo`, `PUT /users/me/photo`, `DELETE /users/me/photo`: `VIEW_ONLY` (every authenticated user manages their own photo). This is the first endpoint where `VIEW_ONLY` writes something — say so explicitly in the controller Javadoc: it's the caller's own profile, never another user's or club data.
   - `GET /users/{id}/photo`: `VIEW_ONLY`.
   - `PUT /users/{id}/photo`, `DELETE /users/{id}/photo`: `ADMIN`. An admin may use it on their own id too.
   - "me" = the user id from the access token (`AuthenticatedUser.userId()`); never read from the request.
   - A user id not in the caller's club (or not existing) → 404, on all `{id}` operations, via the club-scoped `UserRepository.findById`.
3. **Deactivated users (`active == false`): same rule as a released player.** Reading the photo is allowed; `PUT` and `DELETE` → 409 (new `ConflictException` subclass, e.g. `DeactivatedUserException`, message without ids). This applies whoever the caller is — including a deactivated user still holding a valid access token calling `/users/me/...`. Deactivation (when it's built) keeps the photo.
4. **`hasPhoto`** is added to both `UserResponse` and `CurrentUserResponse`, right after `active` (so `CurrentUserResponse` is `..., active, hasPhoto, club`). Keep the parity documented in `CurrentUserResponse`.
   - invite: always `false`, **no** storage query (a new user can't have one yet — same as `PlayerController.create`).
   - permission-level change: one `exists` query.
   - `/me`: one `exists` query.
   - No staff list endpoint in this ticket (`GET /auth/users` is a separate future ticket).
5. **Limits are the same as the player photo and logo:** the same `ImageValidator`, `squadpulse.images.max-size` (2MB), no new config property.

## Design

- **`ImageKind.STAFF_PHOTO`**, owner id = the user id: `new ImageOwner(ImageKind.STAFF_PHOTO, userId)`. Update the `ImageKind` / `ImageStorage` Javadoc.
- **The photo is never stored on `User`, and `User` is never saved by any photo operation** — so the `@Version` is untouched and there is nothing to retry. Only `userRepository.findById` (read) is used. State this in the service Javadoc.
- **New controller** `auth.StaffPhotoController` (or similar), package-private. Mirror `ClubLogoController`'s handlers and their documented choices: no `consumes` (non-multipart → 400, not 415), `@RequestPart("file")`, `204` for upload and delete, `GET` returns the bytes with detected `Content-Type` and `Content-Length`. The `me` handlers just delegate with the caller's user id. Verify that the literal `/users/me/photo` mapping wins over `/users/{id}/photo` with the installed Spring Framework (a test must prove `GET /users/me/photo` returns the caller's photo, and that `PUT /users/me/photo` by a non-admin is not caught by the `ADMIN` `{id}` rule).
- **New service** (e.g. `auth.StaffPhotoService`), so the controller has no logic:
  - `upload(userId, ValidatedImage)`: load the user (404 if not found in the club), 409 if inactive, then `imageStorage.store(...)`.
  - `photo(userId)`: load the user (404), then `find`, or `NotFoundException` ("This user has no photo" or similar, no ids) → 404. Works for an inactive user.
  - `delete(userId)`: load the user (404), 409 if inactive, then `imageStorage.delete(...)`; idempotent → 204 when there was none.
  - `hasPhoto(userId)`: `imageStorage.exists(...)`.
  - **Not needed, and don't add:** the post-store re-check from `PlayerService.uploadPhoto`. It guards against a concurrent permanent delete of the owner, and users can't be permanently deleted. State in the Javadoc: (a) if a user hard-delete is ever added, it must delete the photo and upload must gain that re-check (same as the KAN-29/30 notes); (b) a deactivation racing an upload could leave a photo stored for a just-deactivated user — harmless, since deactivation keeps the photo anyway.
- **`/me`:** `CurrentUserService` adds `hasPhoto` for the caller. That's now user + club + logo-exists + photo-exists queries; acceptable, mention it in the Javadoc.
- **`UserManagementController`:** `invite` → `hasPhoto=false`; `changePermissionLevel` → `exists` query. Keep `UserResponse.from(...)` simple (e.g. `from(User, boolean hasPhoto)`), don't hide a storage call inside the record.
- **Security:** `/users/**` must not be public. Don't touch `PUBLIC_ENDPOINTS`. Confirm `anyRequest().authenticated()` covers the new paths with no `SecurityConfig` change; if a change is needed, report why.
- **Module docs:** update `auth/package-info.java`.

## Verify against what's installed

Don't rely on general knowledge. Check against the versions actually in `pom.xml` / `./mvnw dependency:tree` (Boot 4.1.1, Spring Framework 7.0.9, Spring Security 7.1.1):
- that the refresh cookie path is `/auth` and therefore isn't sent to `/users/...` (quote the code).
- that a literal path segment (`me`) takes precedence over `{id}` in the installed `PathPattern` matching — proven by tests, not assumed.
- that `nosniff` and `Cache-Control: no-store` are on the `GET` photo response (asserted in a test).
- 413 for an oversize upload on this path: one real-server case if `ClubLogoUploadLimitIntegrationTest`'s setup makes it cheap; otherwise explain why the container-wide limit is already covered, and skip.

## Tests

1. **Controller slice** (`@WebMvcTest` with the real security chain via `AuthWebMvcTestConfig`, tokens from `TestAccessTokens`):
   - `/users/me/photo` `GET`/`PUT`/`DELETE`: all four levels allowed.
   - `/users/{id}/photo` `PUT`/`DELETE`: `ADMIN` allowed; `VIEW_ONLY`, `EDIT_PARTIAL`, `EDIT_FULL` → 403.
   - `/users/{id}/photo` `GET`: all four levels allowed.
   - no token → 401 on all six.
2. **Integration** (real Mongo, through HTTP):
   - self: a `VIEW_ONLY` user uploads PNG to `/users/me/photo` → `GET /users/me/photo` and `GET /users/{ownId}/photo` (by another user of the club) return identical bytes, `image/png`, `nosniff`, `no-store`. Upload JPEG → replaced, only one file left for that owner. `DELETE` → 404 on `GET`; second `DELETE` still 204.
   - admin: `ADMIN` uploads / deletes another user's photo via `/users/{id}/photo`; the target sees it via `/users/me/photo`.
   - a non-admin `PUT /users/{otherId}/photo` → 403 and nothing stored.
   - SVG, empty file, missing `file` part, non-multipart → 400, nothing stored.
   - unknown user id → 404 on `GET`/`PUT`/`DELETE` (admin), nothing stored.
   - **deactivated user** (set `active=false` directly in Mongo): `GET` of an existing photo → 200; admin `PUT`/`DELETE` → 409 and photo unchanged; the deactivated user's own `PUT`/`DELETE /users/me/photo` with a still-valid access token → 409.
   - **`User` untouched:** upload and delete don't change the user document — `version` and `updatedAt` identical before and after.
   - **club isolation:** a photo of a club A user; club B's admin `GET`/`PUT`/`DELETE /users/{aUserId}/photo` → 404, and A's photo is unchanged.
   - **no collision with other kinds:** a player and a club whose ids are set equal to a user's id (insert directly) each have their image; the staff photo stays independent (upload, get, delete one; the others are unaffected).
   - `hasPhoto`: `/me` false → true after upload → false after delete. Invite response `hasPhoto: false`. Permission-level change response reflects the target's photo (true and false cases).
3. **Regression:** update the existing `/me`, invite and permission-level tests for the new field. `EndpointAuthorizationTest`, `GridFsConfinementTest`, `PublicEndpointsConsistencyTest` pass. All other tests pass unchanged.

Run `./mvnw verify` with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25). Everything green and Spotless clean. Report the total test count (811 after KAN-30).

## Docs

- **README:** document the six endpoints (a short "Staff photo" section near the club logo one): permissions (including that `me` is open to `VIEW_ONLY`), status codes (404 / 409 / 400 / 413), refer to the player photo rules for limits instead of repeating them, `GET` needs the access token (frontend fetches as blob), and why the path is not under `/auth`. Add `hasPhoto` to the `/me`, invite and permission-level response descriptions.
- **CLAUDE.md:** only if something there becomes inaccurate. Say what you changed, or that nothing needed changing.
- **`docs/spec.md`: do NOT edit it.** In your summary, list exactly what needs updating, with the exact facts. Expected at least: section 03 image-store paragraph (third kind), section 04 (`/me` and user responses get `hasPhoto`; six new endpoint table rows; staff photo text next to the club logo text; the `VIEW_ONLY`-writes-own-photo exception), section 10 if deactivation text should mention the photo, status line and roadmap (section 13).

## Commits

Small, focused commits, for example:
- `feat(auth): add staff photo upload, serve and remove at /users/{me|id}/photo (KAN-32)`
- `feat(auth): expose hasPhoto on user responses and GET /auth/users/me (KAN-32)`
- `test(auth): ... (KAN-32)`

Docs go in the same commit as the change they describe (rule 7). **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. Sample JSON: `/me`, invite response, permission-level response (with `hasPhoto`). Exact status and headers of `GET /users/me/photo` (200 and 404) and of a 409.
3. Evidence for each item in "Verify against what's installed".
4. The test list with counts, and the full build result (command, outcome, total count).
5. Deviations from this prompt, and why.
6. The spec update list.
7. Open ends and risks.
8. Print the full final controller and service classes, and the diffs of `UserResponse`, `CurrentUserResponse`, `CurrentUserService` and `UserManagementController`, so they can be reviewed directly.
