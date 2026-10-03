# KAN-29 — Player photo: upload, serve and remove (GridFS)

## Step 0 — Start from a clean, current master

```
git checkout master && git pull
```

Verify local `master` equals `origin/master` (`git rev-parse master origin/master` must print the same hash; expected to be at or after `ab7ff2d`, the KAN-28 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-29-player-photo`.

## Context

Jira KAN-29 (parent epic KAN-11 "Squad Management"), blocked by KAN-26 (Done), and it blocks KAN-30 (club logo). Each player gets one optional photo. This ticket also builds the **shared image-storage infrastructure** in `common` that KAN-30 will reuse for the club logo, so keep it generic: nothing in `common` may know about players.

Ticket decisions (2026-09-29): storage is **MongoDB GridFS**, behind an `ImageStorage` interface in `common`, so it can move to object storage (S3/R2) in Phase 6 without touching callers. Every stored image carries its `clubId`, and reads and deletes are club-scoped. A Jira comment from KAN-27 adds: **permanent player delete must also delete the player's photo**, with a test. `docs/spec.md` section 05 says the same.

Read first: `CLAUDE.md`, `docs/spec.md` sections 03, 05 and 10, the whole `squad` package, and in `common`: `ClubContext`, `ClubScopedRepositoryImpl` (class Javadoc), `GlobalExceptionHandler`, `BadRequestException`, `ConflictException`, `NotFoundException`, `ApiErrorResponse`, `MongoRepositoryConfig`. Also read `auth/SecurityConfig` (note: it doesn't customize `headers()`), `application.yml`, and these tests: `PlayerControllerTest` (setup + permission matrix), `PlayerApiIntegrationTest` (`rawPlayer`, the hooks), `GlobalExceptionHandlerTest`, and the ArchUnit rules + their rule tests (`ClubScopedRepositoryRules` / `ClubScopedRepositoryMethodNamingRuleTest`, `EndpointAuthorizationRules` / `EndpointAuthorizationRuleTest`, `common/archunitfixture`).

**Why this needs care (clubId isolation, CLAUDE.md rule 4):** GridFS is not a Spring Data repository, so `ClubScopedRepositoryImpl` does **not** protect it. Every GridFS query is hand-built, and spec section 03 says such queries bypass the central layer and nothing catches a missing `clubId` criterion. So the GridFS code must be confined to one class in `common` that applies the `clubId` filter itself, and an ArchUnit rule must stop GridFS from being used anywhere else (see below).

## Agreed design (decided with Gal, implement exactly this)

### 1. `common`: image storage infrastructure

Names are suggestions. Keep the shape and the responsibilities.

- **`ImageKind`** (enum): `PLAYER_PHOTO` only. KAN-30 adds `CLUB_LOGO`. Don't add it now.
- **`ImageOwner`** (record): `ImageKind kind, String ownerId`, both non-null.
- **`ImageType`** (enum): `JPEG`, `PNG`, `WEBP`, each with its media type (`image/jpeg`, `image/png`, `image/webp`), plus `static Optional<ImageType> detect(byte[] content)` based **only on magic bytes**:
  - JPEG: `FF D8 FF`
  - PNG: `89 50 4E 47 0D 0A 1A 0A`
  - WebP: `52 49 46 46` ("RIFF") at offset 0 **and** `57 45 42 50` ("WEBP") at offset 8, so at least 12 bytes
  - Anything else, including SVG, GIF, HTML and too-short input, is empty. The client's `Content-Type` and the file name are **never** consulted.
- **Validation** (one place in `common`, reused by KAN-30, e.g. `ImageValidator` or a static factory on a `ValidatedImage` record holding `byte[] content` + `ImageType type`):
  - empty (0 bytes) → `BadRequestException` scoped to field `file`: `file: must not be empty`
  - larger than the configured maximum → **413** (see "Size limit")
  - type not detected → `BadRequestException` scoped to field `file`: `file: must be a JPEG, PNG or WebP image`
- **`ImageStorage`** (interface): the only API callers use:
  - `void store(ImageOwner owner, ValidatedImage image)`: stores the image as this owner's current one and removes the owner's older ones (see "Replace semantics")
  - `Optional<StoredImage> find(ImageOwner owner)`: the current image (`ImageType`, length, content as a stream/`Resource`)
  - `boolean exists(ImageOwner owner)`
  - `Set<String> ownerIdsWithImage(ImageKind kind)`: the ownerIds of that kind that have an image, in the current club, in **one** query (for list `hasPhoto`)
  - `void delete(ImageOwner owner)`: removes all of the owner's files; a no-op if there are none
  - **No method takes a `clubId` parameter.** The clubId always comes from `ClubContext.requireClubId()` inside the implementation (throws `MissingClubContextException` when absent, like the repository layer).
  - **Storage-neutral contract:** this interface is what lets us move to object storage (S3/R2) in Phase 6 by swapping the implementation only. So its signatures and its records (`ImageOwner`, `ValidatedImage`, `StoredImage`) use only JDK / our own types: no `ObjectId`, no GridFS / driver / Spring Data Mongo types (a plain `InputStream` or a `Resource` declared as `org.springframework.core.io.Resource` is fine, a `GridFsResource` is not). Callers must not depend on GridFS-specific behaviour (file ids, `uploadDate` ordering). That stays inside `GridFsImageStorage`. Document the contract (current image per owner, replace, idempotent delete, club scoping) in the interface Javadoc so an S3 implementation can follow it.
  - Write the storage-level integration tests (section "Tests", items 5, 6, 8, 11) so the behavioural part runs against the `ImageStorage` interface (e.g. an abstract contract test with a GridFS subclass), and a future S3 implementation can reuse it. Index checks stay GridFS-specific.
- **`GridFsImageStorage`**: the only implementation, and the only class in the codebase that touches GridFS.
  - Dedicated bucket **`images`** (not the default `fs`). Find out how to configure a bucket in the installed versions (Boot auto-configured `GridFsTemplate` + bucket property, or your own `GridFsTemplate` / `GridFSBucket` bean in `common`) and report what you chose and why.
  - Metadata on every file: `clubId`, `kind`, `ownerId` (plus the content type). The GridFS filename is a server-generated value such as `<kind>/<ownerId>`. **Never store or echo the client's filename.**
  - **Every query is built by one private helper** that always adds `metadata.clubId = clubContext.requireClubId()`. No query in this class may be built without it.
  - Index on `images.files`: `{metadata.clubId: 1, metadata.kind: 1, metadata.ownerId: 1, uploadDate: -1}` with a name constant. `auto-index-creation` only covers mapped entities, so create it explicitly at startup (e.g. `IndexOperations` / `ensureIndex` in an init hook in `common`). Verify it exists in an integration test.
- **Replace semantics (race-safe without transactions):** define a total order on an owner's files: `(uploadDate, _id)`. `find` returns the **latest** in that order. `store` uploads the new file first, then deletes the owner's files that come strictly **before** the new one in that order. It never deletes "everything except mine", because with two concurrent uploads both would delete each other. Under a race a stale extra file may briefly survive. It's never served (reads take the latest) and the next store/delete removes it. Document this in the Javadoc. Don't use a Mongo transaction for GridFS unless you verify that the installed `GridFsTemplate` actually participates in one, and report what you found.

### 2. Size limit

- App limit: **2 MB** (2 MiB, `DataSize`) in a new property, e.g. `squadpulse.images.max-size: 2MB`, bound to a validated `@ConfigurationProperties` class in `common`. Checked in code by the validator → **413 Payload Too Large**, through a new `common.PayloadTooLargeException` mapped in `GlobalExceptionHandler` (message like `Image must be at most 2 MB`, no echoed values).
- Container limit as the outer guard: `spring.servlet.multipart.max-file-size: 2MB`, `max-request-size: 3MB` (room for multipart overhead). **Verify the property names and defaults in Boot 4.1.1** (the default file limit is 1MB in recent Boot versions, which would reject valid photos). `MaxUploadSizeExceededException` → the same **413** in `GlobalExceptionHandler`.
- **Important:** MockMvc does **not** enforce `spring.servlet.multipart` limits (they live in the servlet container / `MultipartConfigElement`). So the 413 for the in-code check is tested with MockMvc, and the **container** limit needs one real-server test (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) that uploads e.g. 3 MB and ~10 MB. Check what the client actually receives. Tomcat's `max-swallow-size` (default 2MB) may reset the connection instead of returning 413 for a large body. Verify, report, and set `server.tomcat.max-swallow-size` if it's needed to get a clean 413, explaining the value.

### 3. Other multipart errors (currently all fall to 500)

Map in `GlobalExceptionHandler`, following the existing style and message rules:
- `MissingServletRequestPartException` (no `file` part) → 400, details `file: is required`
- any other `MultipartException` (malformed multipart, or a request that isn't multipart at all to the photo endpoint) → 400 `Malformed multipart request`. Make sure the more specific `MaxUploadSizeExceededException` handler wins.
- Check what a non-multipart request (e.g. JSON) to `PUT .../photo` actually throws in this setup, and make sure it's **not** a 500. The global `HttpMediaTypeNotSupportedException` → 415 mapping is **KAN-31's** scope. Don't add it here. If the only way to avoid a 500 on this endpoint is that mapping, stop and report.

### 4. `squad`: endpoints

In `PlayerController` (same `/squad/players` mapping; still **not** `@Validated`):

| Endpoint | Permission | Behaviour |
|---|---|---|
| `PUT /squad/players/{id}/photo` (multipart, part name `file`) | `EDIT_FULL` | Validates and stores, replacing any existing photo. **204** |
| `GET /squad/players/{id}/photo` | `VIEW_ONLY` | **200** with the image bytes; `Content-Type` = the stored detected type, `Content-Length`, `X-Content-Type-Options: nosniff`. **404** if the player has no photo |
| `DELETE /squad/players/{id}/photo` | `EDIT_FULL` | Removes the photo. **204**, also when there was none (idempotent) |

Service logic in `PlayerService` (inject `ImageStorage`):
- All three load the player through the existing club-scoped `get(id)` first. A missing player or another club's → **404**, before anything touches storage.
- **Released player is read-only** (Gal's decision): `PUT`/`DELETE` photo on a released player → **409** with the existing `ReleasedPlayerException` (check its message fits; adjust the wording only if it says "update" specifically). `GET` works for released players. Release and re-activation keep the photo.
- **Upload vs. a concurrent permanent delete:** after `store`, re-check that the player still exists (club-scoped `existsById`). If it doesn't, delete the owner's images and return 404, so no orphan is left behind. Document the remaining tiny window in the Javadoc.
- **Permanent delete (`PlayerService.delete`)**: delete the photo **first**, then the player document. That way a failure between the two steps leaves a player without a photo, and retrying the `DELETE` cleans up fully. The other order would leave an orphan file that no API call can reach any more. Concurrent double delete must still give 204 to both (existing behaviour; keep its test green). Update the Javadoc.
- No version check on photo operations: the `Player` document isn't modified, so `version` doesn't change. That's the reason for the metadata-based design (Gal's decision). A photo upload must never make a client's `version` stale. Test this.

### 5. `hasPhoto` on `PlayerResponse` (Gal's decision)

Add `boolean hasPhoto` to `PlayerResponse`, in every response that returns a player:
- list: **one** `ownerIdsWithImage(PLAYER_PHOTO)` call per request, never one query per player
- get / update / release / reactivate: `exists(...)`
- create: `false` without a query

### 6. ArchUnit: GridFS only in `common`

New rule: no class outside `com.squadpulse.common` may depend on `GridFsTemplate`, `GridFsOperations`, `GridFsResource` or the driver's `com.mongodb.client.gridfs..` classes. Follow the existing rules + rule-test pattern (a violating fixture proves the rule fails, the real codebase passes). **Fixtures must not be Spring beans** (no `@Component` / `@Controller`), because existing `archunitfixture` controllers already get component-scanned into `@SpringBootTest` contexts, a known issue. Don't add to it.

## Verify against what's installed, don't assume

Boot 4.1.1, Spring Data MongoDB 5.1.1, driver 5.8.1, Spring Framework 7.0.9, Spring Security 7.x (check `pom.xml` / `./mvnw dependency:tree` for exact versions). Report evidence (what you checked, where, what you found) for:
1. The multipart property names and defaults in Boot 4.1.1, and that your values are actually applied (the real-server test proves it).
2. How the GridFS bucket is configured, and the `GridFsTemplate` / `GridFSBucket` API you used for metadata queries, sorting and delete-by-query.
3. Whether GridFS operations join a `MongoTransactionManager` transaction (only if you considered using one).
4. That Spring Security 7 adds `X-Content-Type-Options: nosniff` by default with this `SecurityConfig`, proved by a test on the photo `GET` response, not by docs.
5. Which exception a non-multipart request, a missing part and an oversize file each actually raise, and the resulting status codes.
6. Tomcat behaviour for oversize bodies (413 vs. connection reset) and `max-swallow-size`.
7. The resolution of `uploadDate` and that the `(uploadDate, _id)` ordering works as designed.

If anything differs from this prompt, stop and report rather than improvising.

## Tests (required)

Follow the existing style. Testcontainers for anything touching GridFS.

1. **`ImageType.detect`**: real minimal JPEG/PNG/WebP headers detected. Rejected: SVG (`<svg` / `<?xml`), GIF, HTML, PDF, random bytes, empty, an 11-byte "RIFF....WEBP" truncation, "RIFF" + non-WEBP (e.g. WAVE). A PNG body sent with `Content-Type: image/jpeg` and filename `x.jpg` is stored and served as `image/png`.
2. **Validation**: empty → 400 `file: must not be empty`; exactly 2 MiB → accepted; 2 MiB + 1 byte → 413 (MockMvc); SVG → 400; missing part → 400 `file: is required`; non-multipart request → not 500 (assert the actual status). No rejected value or filename is echoed in any error.
3. **Container limit** (real server, `RANDOM_PORT`): 3 MB and ~10 MB uploads → clean 413 with the standard error JSON (or report precisely what happens). An unauthenticated oversize upload → 401.
4. **Permission matrix**: PUT/DELETE photo: `VIEW_ONLY`, `EDIT_PARTIAL` → 403; `EDIT_FULL`, `ADMIN` → success. GET: all four levels → 200. No token → 401 on all three. ArchUnit authorization rule covers the new handlers.
5. **Club isolation (integration, real Mongo)**: club B gets 404 on GET/PUT/DELETE of club A's player photo, and A's file is untouched afterwards. **Storage level**: under club B's context, `find`/`exists` with A's owner → empty/false, `delete` removes nothing of A's, `ownerIdsWithImage` excludes A's owners. Any storage call with no club context → `MissingClubContextException`.
6. **Lifecycle**: upload → GET returns identical bytes + headers (`Content-Type`, `Content-Length`, `nosniff`); replace → GET returns the new one and **exactly one** file remains for that owner in `images.files`; DELETE → GET 404 and zero files; DELETE with no photo → 204. Released player: PUT/DELETE → 409, GET → 200; re-activated → photo still there.
7. **Permanent player delete removes the photo**: after `DELETE /squad/players/{id}`, no file with that owner remains in `images.files`. The existing concurrent double-delete test still passes.
8. **Races**: two concurrent uploads for the same player → afterwards exactly one file remains and it's one of the two uploads (deterministic barrier style like `PlayerInsertBarrier`, not sleeps). Upload racing a permanent delete → no orphan file (use a hook to delete the player between `store` and the re-check).
9. **Version untouched**: upload/delete photo doesn't change the player's `version`, and a subsequent `PUT /squad/players/{id}` with the version loaded before the upload succeeds.
10. **`hasPhoto`**: true/false correctly in list, get, update, release, reactivate; `false` on create. The list performs **one** storage query regardless of player count (verify with a spy/mock on `ImageStorage`).
11. **Index**: the `images.files` index exists with the expected keys after startup.
12. **ArchUnit GridFS rule**: fails on a fixture, passes on the codebase.

Run the full build with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25): `./mvnw verify`. Everything green, Spotless clean. Report the total test count (it was 601 after KAN-28).

## Docs

- **README** "Squad API": the three photo endpoints (permissions, status codes, allowed types by content, 2 MB limit, 413, released → 409, DELETE idempotent), `hasPhoto`, and a note for the frontend: the photo is served only with the `Authorization` header, so an `<img src>` URL won't work; fetch it as a blob. Also an architecture note on `common.ImageStorage` / the `images` GridFS bucket. Update the status line.
- **CLAUDE.md** (rule 7): status line + a short paragraph: images go only through `common.ImageStorage`; `GridFsImageStorage` is the only GridFS code and applies `clubId` itself (ArchUnit-enforced); content type comes from magic bytes only; replace semantics; photo deleted before the player on permanent delete.
- **`docs/spec.md`: do NOT edit it** (CLAUDE.md). List precisely in your summary every place that needs updating and the exact facts to write there. Expected: status line; section 03 (the image store as a documented, confined bypass of the repository layer); section 05 (photo field / `hasPhoto`, the photo endpoints' behaviour under "Squad API behaviour", the permanent-deletion sentence about photos, which becomes implemented); section 10 (upload security: magic bytes, size, nosniff; EXIF not stripped); roadmap table.

## Out of scope (don't do, but mention in "open ends" if relevant)

- Server-side resize / thumbnails / re-encoding. Note: EXIF metadata (possibly GPS location) is stored and served as uploaded; stripping it needs re-encoding, which is a later decision.
- HTTP caching / ETag for photos (Spring Security's default `no-store` applies).
- The global 415 mapping and logging in `handleUnexpected` (KAN-31).
- The club logo (KAN-30).

## Commits

Small, focused commits in the existing style, e.g. `feat(common): add GridFS-backed image storage (KAN-29)`, `feat(squad): add player photo endpoints (KAN-29)`, `test(...)`, `docs(readme): ... (KAN-29)`. **Do not push and do not open a PR.** That comes in a later prompt, after review.

## Summary to return

1. Files changed/added, one line each.
2. The final API as implemented (endpoints, permissions, status codes, headers, example `PlayerResponse` JSON with `hasPhoto`).
3. Evidence for each item in "Verify against what's installed".
4. Test list with counts, and the full build result (command + outcome + total test count).
5. Any deviation from this prompt, and why.
6. The spec update list (see Docs).
7. Open ends / risks you noticed.
8. Print the full final contents of `ImageStorage.java`, `GridFsImageStorage.java`, `ImageType.java`, the validator, the new `GlobalExceptionHandler` handlers, the photo handlers in `PlayerController`, and the photo methods + `delete` in `PlayerService`, so they can be reviewed directly.
