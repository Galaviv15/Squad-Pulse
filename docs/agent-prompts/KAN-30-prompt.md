# KAN-30: Club logo, upload, serve and remove

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `c5514ef` (the KAN-34 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-30-club-logo`.

## Context

This is Jira KAN-30, under Epic KAN-33 ("Club & Staff Administration"). It is a prerequisite for the Frontend MVP: the app header shows the club's name (already available from `GET /auth/users/me`, KAN-34) and its logo.

The club gets an optional logo. Everything security-relevant already exists from the player photo (KAN-29): `common.ImageStorage` (GridFS, club-scoped by construction), `ImageValidator` (sniffs JPEG/PNG/WebP by content, no SVG, size limit), the multipart limits, `nosniff`, and `Cache-Control: no-store`. This ticket reuses that work for a second image kind. It must not re-implement any of it.

Two points in the Jira ticket text are out of date: the epic is already assigned (KAN-33), and the module was decided in KAN-34 (`auth`). Ignore the ticket's "decide where the controller lives" note.

Read these first:
- `CLAUDE.md`: the access-control and error-handling paragraph, and rule 7
- `docs/spec.md`: section 03 (the image-store bypass paragraph), section 04 (the paragraph on `/me` and the endpoint table), and section 05 (the player photo bullets)
- README: "Auth API" and the player photo part of the squad API
- in `common`: `ImageStorage`, `GridFsImageStorage`, `ImageKind`, `ImageOwner`, `ImageValidator`, `ValidatedImage`, `StoredImage`, `ImageProperties`, `ClubContext`, `GlobalExceptionHandler` (multipart / 413 handling)
- in `squad`: `PlayerController` (`uploadPhoto` / `photo` / `deletePhoto` and their Javadoc), `PlayerService` (the photo methods)
- in `auth`: `Club`, `ClubRepository`, `CurrentUserController`, `CurrentUserService`, `CurrentUserResponse`, `SecurityConfig`, `package-info.java`
- tests: `PlayerPhotoIntegrationTest`, `PlayerPhotoUploadLimitIntegrationTest`, `CurrentUserIntegrationTest`, the `CurrentUserController` slice test, `EndpointAuthorizationRules`, `GridFsRules`, `PublicEndpointsConsistencyTest`

**Facts I checked on master (`c5514ef`). Re-confirm them; don't take them on faith:**
- `ImageKind` has only `PLAYER_PHOTO`. Its Javadoc already says a player id and a club id can never collide, because each kind has its own owners.
- `ImageStorage` takes no clubId. Every call uses `ClubContext.requireClubId()`, which `JwtAuthenticationFilter` sets from the token.
- `Club` is `@NotClubScoped` and has no `@Version`. Nothing can delete a club: there is no endpoint and no service method for it.
- The refresh-token cookie is scoped to `Path=/auth` (spec section 10). Confirm where it's set (`AuthController` / cookie helper).
- `CurrentUserResponse.ClubSummary` is `(id, name)`.
- `spring.servlet.multipart.max-file-size` is 2MB and `squadpulse.images.max-size` is 2MB.

## Decisions agreed with Gal (implement exactly these)

1. **Path: `/clubs/me/logo`, code in the `auth` module.** The URL deliberately does **not** start with `/auth`, because the browser sends the `Path=/auth` refresh cookie with every request under `/auth`. A logo fetched on every app load must not carry the most sensitive cookie in the system. Put a short comment on the controller explaining this, so nobody "tidies" it into `/auth/...` later. "me" means the caller's club; there is never a club id in the path.
2. **Permissions:** `PUT /clubs/me/logo` is `ADMIN`, `GET /clubs/me/logo` is `VIEW_ONLY`, and `DELETE /clubs/me/logo` is `ADMIN`.
3. **`/me` gets `hasLogo`:** `club { id, name, hasLogo }`, in this PR.
4. **Limits are the same as the player photo:** the same `ImageValidator`, the same `squadpulse.images.max-size` (2MB), JPEG/PNG/WebP sniffed by content, no SVG. No per-kind limit and no new config property.

## Design

- **`ImageKind.CLUB_LOGO`**, with owner id = the clubId itself: `new ImageOwner(ImageKind.CLUB_LOGO, clubId)`. The clubId comes only from `ClubContext` / the token, never from the request. Update the `ImageKind` and `ImageStorage` Javadoc ("later the club logo" becomes current).
- **New controller** `auth.ClubLogoController`, package-private, `@RequestMapping("/clubs/me/logo")`. Mirror `PlayerController`'s three photo handlers, including their documented choices: no `consumes` (a non-multipart request is a 400, not a 415), `@RequestPart("file")`, `204` for upload and delete, and for `GET` the bytes with the detected `Content-Type` and `Content-Length`.
- **New service** (e.g. `auth.ClubLogoService`), so the controller has no logic:
  - `upload(ValidatedImage)`: first load the club with `clubRepository.findById(clubContext.requireClubId())`. If it's missing, that's a data-integrity bug: throw an `IllegalStateException` (generic 500, ids in the log only), the same as `CurrentUserService`. Then `imageStorage.store(...)`. The point is that no file is ever stored under a clubId with no club. The `Club` document is **not** modified.
  - `logo()`: `imageStorage.find(...)`, or `NotFoundException("This club has no logo")` (or similar; no ids in the message) → 404.
  - `delete()`: `imageStorage.delete(...)`, idempotent, so `204` also when there was none.
  - `hasLogo()`: `imageStorage.exists(...)`.
  - **Not needed, and don't add:** the re-check after store that `PlayerService.uploadPhoto` does. It guards against a concurrent permanent delete of the owner, and clubs can't be deleted. There's also no "released" state, so no 409. State both in the service Javadoc in one or two sentences, so a reviewer comparing it with `PlayerService` sees that the difference is deliberate.
- **`/me`:** `CurrentUserResponse.ClubSummary` becomes `(id, name, hasLogo)`. `CurrentUserService` gets it from the storage (one `exists` query). Keep the field order `id, name, hasLogo`. Update the record and service Javadoc.
- **Security:** `/clubs/**` must not be public. Don't touch `PUBLIC_ENDPOINTS`. Confirm `anyRequest().authenticated()` covers the new path with no `SecurityConfig` change. If a change *is* needed, report why.
- **Module docs:** update `auth/package-info.java`, which now also covers the club logo.

## Verify against what's installed

Don't rely on general knowledge. Check these against the versions actually in `pom.xml` / `./mvnw dependency:tree` (Boot 4.1.1, Spring Framework 7.0.9, Spring Security, Jackson 3 `tools.jackson`):
- that the refresh cookie's path is really `/auth`, and therefore isn't sent to `/clubs/me/logo`. Quote the code that sets it.
- that `nosniff` and `Cache-Control: no-store` are on the `GET /clubs/me/logo` response (asserted in a test, not assumed from the player test).
- that the 413 for an oversize upload comes from the same global multipart handling for this path too. One real-server case is enough, if `PlayerPhotoUploadLimitIntegrationTest`'s setup makes it cheap. Otherwise explain why the existing test already covers it (the limit is container-wide) and skip it.

## Tests

1. **Controller slice** (`@WebMvcTest` with the real security chain via `AuthWebMvcTestConfig`, tokens from `TestAccessTokens`):
   - `PUT` and `DELETE`: `ADMIN` gets 204. `VIEW_ONLY`, `EDIT_PARTIAL` and `EDIT_FULL` get 403.
   - `GET`: all four levels get 200 (or 404 when there's no logo).
   - no token gets 401 on all three.
2. **Integration** (real Mongo, through HTTP):
   - upload PNG → `GET` returns the identical bytes with `image/png`, `nosniff` and `no-store`. Then upload JPEG → `GET` returns the JPEG (replace), and only one file is left for the owner.
   - `DELETE` → `GET` is 404. A second `DELETE` is still 204.
   - an SVG, an empty file, a missing `file` part, or a non-multipart request → 400, with nothing stored.
   - **club isolation:** club A's admin uploads a logo. Club B's user gets 404 on `GET`. Club B's admin `DELETE` doesn't remove A's logo, and A still gets it back. Club B's `/me` shows `hasLogo: false`.
   - **no collision with player photos:** a player in club A whose id is set equal to club A's id (insert it directly) has a photo. The club logo and that photo stay independent (upload, get, and delete one, and the other is unaffected).
   - `/me`: `club.hasLogo` is `false` before upload, `true` after, and `false` after delete.
   - the `Club` document is unchanged by an upload (compare it before and after).
   - club document missing (delete it directly) → `PUT` is a 500 with a generic body, and **no** file is stored.
3. **Regression:** update the existing `/me` tests for the new `hasLogo` field. `EndpointAuthorizationTest`, `GridFsConfinementTest` and `PublicEndpointsConsistencyTest` must pass. All other tests pass unchanged.

Run `./mvnw verify` with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25). Everything must be green and Spotless clean. Report the total test count (it was 765 after KAN-34).

## Docs

- **README:** document the three endpoints. Find where they belong best: "Auth API", or a new short "Club API" section. Cover permissions, status codes, limits (refer to the player photo rules, don't repeat them), that the club comes from the token, and that `GET` needs the access token (no public URL, so the frontend fetches it as a blob). Also add `club.hasLogo` to the `/me` description.
- **CLAUDE.md:** only if something there becomes inaccurate. Say what you changed, or that nothing needed changing.
- **`docs/spec.md`: do NOT edit it.** In your summary, list exactly what needs updating, with the exact facts. Expected:
  - section 03: the image-store paragraph ("later the club logo" becomes current)
  - section 04: the `/me` paragraph (`club { id, name, hasLogo }`) and three new table rows
  - a short club logo description: where it fits best (section 04 or 05, or the club / onboarding text)
  - status line and roadmap (section 13)

## Commits

Small, focused commits, for example:
- `feat(auth): add club logo upload, serve and remove at /clubs/me/logo (KAN-30)`
- `feat(auth): expose club.hasLogo on GET /auth/users/me (KAN-30)`
- `test(auth): ... (KAN-30)`

Docs go in the same commit as the change they describe (rule 7). **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. A sample `/me` JSON with `hasLogo`, and the exact status and headers of a `GET /clubs/me/logo` (200 and 404).
3. Evidence for each item in "Verify against what's installed".
4. The test list with counts, and the full build result (command, outcome, total count).
5. Deviations from this prompt, and why.
6. The spec update list.
7. Open ends and risks.
8. Print the full final `ClubLogoController` and the service class, and the diff of `CurrentUserService` / `CurrentUserResponse`, so they can be reviewed directly.
