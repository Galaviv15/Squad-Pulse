# KAN-27 — Player release, re-activation and permanent deletion

## Step 0 — Start from a clean, current master

```
git checkout master && git pull
```

Verify local `master` equals `origin/master` (`git rev-parse master origin/master` must print the same hash; expected to be at or after `2c13d91`, the KAN-26 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-27-player-lifecycle`.

## Context

Jira KAN-27 (parent epic KAN-11 "Squad Management"), blocked by KAN-26 (Done). It adds the lifecycle operations on top of KAN-25 (Player entity) and KAN-26 (squad API: list/get/create/update).

Read first: `CLAUDE.md`, `docs/spec.md` sections 03, 04 and 05, and the whole `squad` package (`Player`, `PlayerService`, `PlayerController`, `PlayerStatus`, `UpdatePlayerRequest`, `ReleasedPlayerException`, `StalePlayerVersionException`, `JerseyNumberTakenException`) plus their tests (`PlayerControllerTest`, `PlayerApiIntegrationTest`, `PlayerLoadHook`, `PlayerInsertBarrier`, `PlayerTestHooksTest`). Also read `common/ClubScopedRepositoryImpl` (in particular `delete`/`deleteById`) and `common/GlobalExceptionHandler`.

Things that already exist and must be **reused, not duplicated**:
- Listing released players already exists: `GET /squad/players?status=released|all` (KAN-26, `PlayerStatus`). Do **not** add another flag. Only make sure tests cover it (see Tests).
- `PlayerService.get` (club-scoped `findById`, so another club's player is 404), `violatedIndex` + `translateDuplicateKey` (jersey index → `JerseyNumberTakenException`, any other duplicate key rethrown), the OLFE → `StalePlayerVersionException` pattern, the "check the version before writing, never copy it onto the entity" rule.
- Permission hierarchy `ADMIN > EDIT_FULL > EDIT_PARTIAL > VIEW_ONLY` (`PermissionLevel`, RoleHierarchy). Every handler needs an explicit `@PreAuthorize` (ArchUnit `EndpointAuthorizationRuleTest`).

## Agreed design (decided with Gal — implement exactly this)

### Endpoints

| Action | Endpoint | Body | Min. level | Success |
|---|---|---|---|---|
| Release | `POST /squad/players/{id}/release` | `{ "version": <long> }` | `EDIT_FULL` | `200` + `PlayerResponse` |
| Re-activate | `POST /squad/players/{id}/reactivate` | `{ "version": <long>, "jerseyNumber": <1–99 or null> }` | `EDIT_FULL` | `200` + `PlayerResponse` |
| Permanent delete | `DELETE /squad/players/{id}` | none | `ADMIN` | `204`, no body |

All operations are always within the caller's club (`ClubContext`). A player of another club, or an unknown id → `404` "Player not found" (identical to a non-existent id: no leak).

### Release
- Sets `active = false`. **Does not touch `jerseyNumber`** — the number stays on the record as history; the partial index (`active: true` in its filter) simply stops reserving it. Do not change the index.
- Order of checks (same as `update`): load (404) → **state** → **version** → save.
- Already released → `409` with a dedicated message, e.g. "This player has already been released". Decided: **409, not idempotent 2xx** (consistent with KAN-26, and the version has moved anyway after the first call).
- `version` required (`@NotNull`); missing → `400` via the existing validation path. Mismatch → `StalePlayerVersionException` (409). Save loses a race (OLFE) → same 409.

### Re-activate
- Sets `active = true` **and** sets `jerseyNumber` to the value in the body. **Full-replacement semantics for that one field, exactly like the PUT:** `null`/absent means "no number". The client pre-fills the player's old number; if it's taken, the user picks another or none. This is the only way to change a released player's number — editing a released player via PUT stays refused (KAN-26 behaviour unchanged), which is why the number must be settable here; otherwise a released player whose number was taken could never come back.
- `jerseyNumber` validated `@Min(1) @Max(99)` like the other request DTOs.
- Order of checks: load (404) → **state** (already active → `409`, dedicated message, e.g. "This player is already active") → **version** → set fields → save.
- Number taken by an active player → `JerseyNumberTakenException` (409), **index-backed only** — no pre-check query. Reuse `translateDuplicateKey`. Must hold under a race (see Tests).
- Do **not** silently fall back to `null` when the number is taken. That was considered and rejected.

### Permanent delete
- `ADMIN` only; allowed on active **and** released players.
- Load via club-scoped `findById` (404 if missing / other club), then delete through the club-scoped repository (`deleteById` / `delete` — both scope by `clubId`). Never a raw `MongoTemplate` remove.
- **No version check — a deliberate decision.** It's an ADMIN cleaning up a mistaken record; a concurrent edit losing to a delete is acceptable. Note that `ClubScopedRepositoryImpl.delete(entity)` ignores `@Version` (removes by id + clubId only) — state this explicitly in the Javadoc so nobody later assumes delete is version-guarded. Do not change `ClubScopedRepositoryImpl`.
- Add a code comment (service Javadoc) **and** list for the spec: *once training sessions / lineups reference players, permanent delete of a referenced player must be blocked with 409.* Nothing references players yet, so no check now.
- KAN-29 (player photo) is not built yet. Do **not** add anything for photos; mention in your summary that KAN-29 must hook photo deletion into permanent delete.

### General
- Request DTOs: new records (e.g. `ReleasePlayerRequest`, `ReactivatePlayerRequest`) in `squad`, package-private like the existing ones. Unknown body fields are ignored (Jackson default — verify, don't assume).
- New exceptions extend `common.ConflictException` (→ 409 through `GlobalExceptionHandler`); reuse existing ones where they fit. Error format and the "values never echoed" rule stay as in KAN-26.
- Keep `PlayerController` **not** `@Validated` (see its Javadoc).
- Update class-level Javadocs of `PlayerService`/`PlayerController` (permissions, lifecycle). Update the `ReleasedPlayerException` message only if it no longer reads correctly.

## Verify against what's installed

Don't rely on general knowledge for framework behaviour — check the versions in `backend/pom.xml` / the resolved dependencies (notes say: Spring Boot 4.x, Spring Framework 7.0.9, Spring Data MongoDB 5.1.1, driver 5.8.1, Jackson 3.1.5 under `tools.jackson`, Hibernate Validator 9.1.3, mongo:7). In particular verify, in the installed sources or with a test, not from memory:
1. That `save` on an existing `@Version` entity whose stored version changed throws `OptimisticLockingFailureException` (already relied on by KAN-26 — reuse that evidence).
2. That an unknown/extra body field and an absent `jerseyNumber` on reactivate behave as described.
3. What `deleteById` in `ClubScopedRepositoryImpl` does when the document is already gone (it returns void — make sure the endpoint's behaviour in that race is what you report).
4. That a `DELETE` with no body and a `POST` with an empty body behave as expected in this Spring version (missing body on release/reactivate → 400, not 500).

If anything differs from this prompt, stop and report rather than improvising.

## Tests (required)

Follow the existing style: WebMvc slice tests in `PlayerControllerTest` (auth via `AuthWebMvcTestConfig` / `TestAccessTokens`), end-to-end in `PlayerApiIntegrationTest` (real Mongo). Use the existing deterministic hooks (`PlayerLoadHook`, `PlayerInsertBarrier`) for races — no sleeps.

1. **Permission matrix** for all three endpoints across `VIEW_ONLY`, `EDIT_PARTIAL`, `EDIT_FULL`, `ADMIN` (+ unauthenticated → 401): release/reactivate 403 below `EDIT_FULL`, allowed for `EDIT_FULL` and `ADMIN`; delete 403 for everything below `ADMIN` **including `EDIT_FULL`**, 204 for `ADMIN`.
2. **Isolation:** release, reactivate and delete of another club's player → 404, and the other club's document is unchanged/still present (assert in the DB).
3. **State transitions:** active → release → `active:false`, number still stored, version incremented; released → reactivate → `active:true`; release of a released player → 409; reactivate of an active player → 409; PUT on a released player still 409 (regression).
4. **Jersey:** after release, another player can take the number (create and update); reactivate with the old number when taken → 409 `JerseyNumberTakenException` and the player stays released (assert in DB); reactivate with a different free number / `null` → 200 with that value; reactivate with no number never conflicts.
5. **Race on reactivation:** a concurrent write takes the number between load and save (`PlayerLoadHook` / `PlayerInsertBarrier`) → 409 jersey conflict, not 500, player still released.
6. **Stale version:** release and reactivate with a wrong version → 409; concurrent save between load and save (`PlayerLoadHook`) → 409.
7. **Validation:** missing `version` → 400; `jerseyNumber` 0 / 100 → 400 naming the field; missing body → 400.
8. **Permanent delete:** of an active player and of a released player → 204, then `GET` → 404 and gone from the DB; second delete → 404; listing `?status=all` no longer contains it.
9. **Listing released players** (existing KAN-26 feature): after a release, `?status=active` excludes it, `?status=released` and `?status=all` include it — add only if not already covered.

Run the full build with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25): `./mvnw verify` (or the project's usual command) — all green, Spotless clean.

## Docs

- **README** "Squad API" section: add the three endpoints, permissions, request bodies, the 409 cases (already released / already active / jersey taken on reactivate / stale version), the reactivate `jerseyNumber` semantics, and that delete is ADMIN-only and not version-checked. Remove the "Releasing / re-activating players isn't there yet" sentences (status line at the top and the Squad API section).
- **`docs/spec.md`: do NOT edit it** (CLAUDE.md). Instead list in your summary, precisely, every place in the spec that needs updating (expected: status line, section 04 permission table, section 05 "Leaving the club, and permanent deletion" + API rules, phase table in the roadmap) and the exact facts to write there.

## Commits

Small, focused commits in the existing style, e.g. `feat(squad): ... (KAN-27)`, `test(squad): ... (KAN-27)`, `docs(readme): ... (KAN-27)`. **Do not push and do not open a PR** — that comes in a later prompt after review.

## Summary to return

1. Files changed/added, one line each.
2. The final API (endpoints, bodies, status codes) as actually implemented.
3. Evidence for each item in "Verify against what's installed" (what you checked, where, what you found).
4. Test list with counts, and the full build result (command + outcome).
5. Any deviation from this prompt, and why.
6. The spec update list (see Docs).
7. Open ends / risks you noticed (including the KAN-29 photo-deletion hook and the future "block delete of referenced players" rule).
