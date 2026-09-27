Implement KAN-20 ("RBAC enforcement by permission level") in the SquadPulse backend (`backend/src/main/java/com/squadpulse/`). It belongs to epic KAN-10 (Auth & Roles) and builds directly on the already-merged KAN-15 (clubId isolation layer), KAN-17 (User entity) and KAN-19 (JWT + auth endpoints + ADMIN-only invite). The agreed scope is also recorded as a comment on the KAN-20 Jira ticket.

## Why this ticket exists, and what's actually missing

Most of the RBAC plumbing already exists from KAN-19. Don't rebuild it:
- `auth/JwtAuthenticationFilter.java` already grants exactly **one** authority per request, named after the caller's `PermissionLevel` (`ADMIN`, `EDIT_FULL`, `EDIT_PARTIAL`, `VIEW_ONLY`, with no `ROLE_` prefix).
- `auth/SecurityConfig.java` already has `@EnableMethodSecurity`, `PUBLIC_ENDPOINTS`, `.anyRequest().authenticated()`, and a JSON 401/403 entry point and access-denied handler.
- `common/GlobalExceptionHandler.java` already maps `AccessDeniedException` → 403.
- `auth/AuthService.refresh()` already re-reads the user from the DB, so a changed permission level takes effect on the caller's next refresh (≤15 minutes, the access-token TTL).

There are three real gaps:

1. **No hierarchy.** Each user carries exactly one authority, so a future `@PreAuthorize("hasAuthority('EDIT_FULL')")` would **reject an ADMIN**. That contradicts the ticket's requirement that "ADMIN can reach everything within their own club." It's a latent bug: nothing triggers it yet only because the one protected endpoint today (`/auth/users/invite`) is `ADMIN`-only.
2. **No way to change a user's permission level after invite.** The ticket requires "upgrading an individual user from `VIEW_ONLY` to `EDIT_PARTIAL`", but today the level is set only at invite time.
3. **"Every endpoint" isn't enforced by anything.** A future controller method with no `@PreAuthorize` would silently be reachable by any authenticated user, including `VIEW_ONLY`. The codebase already solves the equivalent problem for clubId with a build-time ArchUnit rule (`common/ClubScopedRepositoryRules.java`). Do the same here.

## Context you need before starting

Read these first. Match their conventions exactly and don't invent new ones:
- `auth/SecurityConfig.java`, `auth/JwtAuthenticationFilter.java`, `auth/AuthenticatedUser.java`, `auth/PermissionLevel.java`, `auth/Title.java`, `auth/User.java`
- `auth/UserInvitationController.java`, `auth/UserInvitationService.java`, `auth/InviteUserRequest.java`, `auth/UserResponse.java`, `auth/EmailAlreadyRegisteredException.java`. The new endpoint should look like a sibling of the invite endpoint.
- `common/ClubScopedRepositoryImpl.java`. Note that `findById` is club-scoped: an id belonging to **another club** returns `Optional.empty()`, not an exception. `save()` validates the stored document's real clubId before writing. Rely on both; don't bypass them.
- `common/ClubScopedRepositoryRules.java`, `common/ClubScopedRepositoryMethodNamingTest.java`, `common/ClubScopedRepositoryMethodNamingRuleTest.java`, `common/archunitfixture/`. This is the house pattern for an ArchUnit rule: the rule lives in one shared class, one test runs it against the real codebase, and a second test proves against fixtures that the rule catches a violation and allows a compliant case.
- `auth/SecurityConfigTest.java`, `auth/AuthWebMvcTestConfig.java`, `auth/UserInvitationControllerTest.java`. This is the house style for testing behind the real security chain with genuine signed JWTs.
- `auth/AuthFlowIntegrationTest.java` / `auth/ClubBootstrapIntegrationTest.java` show the Testcontainers integration-test style.
- `docs/spec.md` sections 03, 04 and 10, and `CLAUDE.md` (especially standing rules 4 and 7 and the "Access control" paragraph).

**Verify against what's actually installed, not general knowledge.** This repo is on Spring Boot 4.1.1 / Java 21 (see `backend/pom.xml`), which means Spring Security 7.x. The `RoleHierarchy` API has changed across recent Spring Security versions:
- `RoleHierarchyImpl.setHierarchy(String)` was deprecated in favor of static factories such as `fromHierarchy(...)` and `withDefaultRolePrefix()` / `withRolePrefix(...)`.
- How method security picks up a `RoleHierarchy` bean has also changed.
- Whether the hierarchy applies to `hasAuthority(...)` (not just `hasRole(...)`) matters here, because our authorities have no `ROLE_` prefix.

Check the actual classes and javadoc in the resolved `spring-security-core` / `spring-security-config` jars (e.g. `./mvnw dependency:tree`, then the sources jar or your IDE) before writing the config. Don't rely on memory. Then prove it with the tests below rather than assuming it's wired. KAN-23 hit exactly this trap with a config property that turned out to be deprecated in 4.1.1.

## What to build

### 1. Permission hierarchy

Define `ADMIN > EDIT_FULL > EDIT_PARTIAL > VIEW_ONLY` as a `RoleHierarchy` bean, using the non-deprecated API for the installed version and **no role prefix**, so it matches the bare authority names the filter grants. It must apply to both:
- method security (`@PreAuthorize("hasAuthority(...)")`), which is what controllers use, and
- `authorizeHttpRequests`, in case URL rules use authorities later.

Verify how each picks the bean up in this version. If either needs explicit wiring (e.g. a `MethodSecurityExpressionHandler` with the hierarchy set), do that wiring explicitly.

- There must be one source of truth for the order. Prefer deriving it from `PermissionLevel` itself (e.g. the enum's declared order, documented on the enum as meaningful) over a hand-typed string that can drift if a level is ever added. Either way, a test must fail if the order and the hierarchy disagree.
- Update `PermissionLevel`'s Javadoc: it now encodes the hierarchy, and **`EDIT_PARTIAL` is intentionally unused by any endpoint for now**. It exists so an ADMIN can already assign it (see below), and its concrete meaning will be defined once feature endpoints (Player etc.) exist. It currently grants exactly what `VIEW_ONLY` grants, since no endpoint requires `EDIT_PARTIAL`. Say that plainly in the Javadoc so nobody assumes it does something today.
- Keep `JwtAuthenticationFilter` granting a single authority. The hierarchy should do the expansion, not the filter. Don't change the JWT claims.

### 2. `PATCH /auth/users/{id}/permission-level` (ADMIN only)

Put this in `UserInvitationController` (or rename it to something like `UserManagementController` if you think that reads better; your call, but keep it in the `auth` module and keep git history readable). Add a service method alongside `UserInvitationService` or in a small new service; again your call, keep it consistent.

- `@PreAuthorize("hasAuthority('ADMIN')")`.
- Body: a new record, e.g. `UpdatePermissionLevelRequest(@NotNull PermissionLevel permissionLevel)`. It must accept only the permission level. Unknown fields (`clubId`, `title`, `email`, …) must have no effect, the same as the invite request's documented behavior. An unknown enum value → 400 via the existing `HttpMessageNotReadableException` handler.
- Any level can be set, including granting or removing `ADMIN`. Per spec section 04, anyone holding `ADMIN` manages Title + Permission Level. `Title` is **not** changed by this endpoint.
- Look the target up with the normal club-scoped `userRepository.findById(id)`. Not found → **404** via `common.NotFoundException`. This includes an id that exists in **another club**: it must be indistinguishable from a nonexistent id (same status, same message), so the endpoint can't be used to probe other clubs' user ids. No `@GloballyScoped` method, and no new repository method at all is expected.
- **Self-change is rejected with 409**: if the target id equals the caller's `AuthenticatedUser.userId()`, throw a new `ConflictException` subclass in `auth` (e.g. `CannotChangeOwnPermissionLevelException`) with a clear message. This is the agreed lockout protection. A club's only ADMIN must never be able to demote themselves and leave the club with no one able to manage users. We deliberately chose this over a "last ADMIN in the club" count check, because that would be a racy read-then-write under concurrency. Document the reasoning in the Javadoc.
- Setting the level the user already has is a successful no-op (200). It is not an error.
- Save through the club-scoped repository (`save()`), which validates the stored clubId, and return **200** with the updated user as `UserResponse`. Don't expose `passwordHash`; check that `UserResponse` still doesn't.
- The target user's `active` flag and `passwordHash == null` (not yet activated) don't block the change. Document that this is intentional.
- **Token staleness is accepted, but must be documented.** The target's already-issued access token keeps its old `permissionLevel` claim until it expires (≤15 min), and the new level applies on their next `/auth/refresh`. This matches the existing documented trade-off for logout and deactivation (spec section 10). Do **not** add refresh-token revocation or a DB lookup per request. Just document the behavior in the Javadoc and the README.

### 3. Default-deny ArchUnit rule

Add a build-time rule, following the `ClubScopedRepositoryRules` pattern: shared rule class + real-codebase test + fixture-based rule test.

- Every request-handling method in a `@RestController`/`@Controller` class in `com.squadpulse` must either:
  - carry `@PreAuthorize` (on the method, or on the class), or
  - be explicitly annotated with a new marker annotation, e.g. `common.PublicEndpoint` (or `auth.PublicEndpoint`; pick where it belongs and justify it).

  "Request-handling method" means any method annotated with `@RequestMapping` or any of its composed variants (`@GetMapping`, `@PostMapping`, `@PatchMapping`, …). Check meta-annotations, not a hardcoded list of the five variants.
- Mark `AuthController`'s `login`, `refresh`, `logout` with the marker. Add a test asserting that the set of `@PublicEndpoint` methods and `SecurityConfig.PUBLIC_ENDPOINTS` agree, so a method can't be marked public in code while still being blocked (or vice versa) by the security chain. Pick a reasonable way to do this, e.g. resolve the mapped paths from the annotations. If fully automatic matching is disproportionately complex, say so in your summary and explain what you did instead. Don't silently drop it.
- Import production classes only (`ImportOption.DoNotIncludeTests`), so test-only probe controllers aren't flagged.
- Fixture test: a controller method with neither annotation → violation; with `@PreAuthorize` → passes; with the marker → passes; with class-level `@PreAuthorize` → passes.
- Write the violation message like the ClubId rule's: say what's wrong, why it matters (reachable by any authenticated user in the club, including `VIEW_ONLY`), and how to fix it.

## Testing

Beyond the ArchUnit tests above:

**Hierarchy matrix** (`@WebMvcTest` + `AuthWebMvcTestConfig`, real signed JWTs, like `SecurityConfigTest`). Use a test-only probe controller with four endpoints, each requiring one level via `@PreAuthorize("hasAuthority('X')")`. Call each endpoint with a token for each of the four levels: 16 cases, and a parameterized test is fine. Expect 200 where caller ≥ required and 403 (JSON `ApiErrorResponse` shape) otherwise. This covers the ticket's two explicit requirements (`VIEW_ONLY` → 403 on a write-level endpoint; `ADMIN` passes everything) and proves the hierarchy is actually wired into method security in this Spring version, not just defined.

**Endpoint, WebMvc level** (extend `UserInvitationControllerTest` or add a sibling):
- `EDIT_FULL`, `EDIT_PARTIAL`, `VIEW_ONLY` callers → 403
- no token → 401
- missing, null or unknown `permissionLevel` → 400
- self-change → 409
- happy path → 200 with the expected body

**Endpoint, integration level (Testcontainers, real Mongo)**:
- ADMIN in club A upgrades a `VIEW_ONLY` user in club A to `EDIT_PARTIAL`, and the change is persisted.
- ADMIN in club A targets a user id from **club B** → 404, and club B's user is **unchanged in the DB**. This is the clubId-isolation check for this endpoint, and it's the most important test in this ticket (CLAUDE.md standing rule 4). Assert on the DB state, not just the status code.
- A body containing `clubId`, `title` or `email` alongside `permissionLevel` changes only the permission level.
- After a successful change, `/auth/refresh` for the target user yields an access token whose `permissionLevel` claim is the new value. This proves the documented "takes effect at next refresh" claim instead of just asserting it in prose.

Run the full backend test suite (`./mvnw verify` or whatever CI runs; check `.github/workflows/ci.yml`) and report the result, including test counts.

## Docs (CLAUDE.md standing rule 7, same commit as the change)

- `README.md` "Auth API" table: add the new endpoint row in the same style as the invite row (access, body, responses 200/400/401/403/404/409, and the "takes effect at next refresh" note).
- `CLAUDE.md`:
  - Update the "Access control" paragraph with the hierarchy, the `@PublicEndpoint` marker, and the rule that every handler must declare one or the other (ArchUnit-enforced).
  - Add the convention that read endpoints require `VIEW_ONLY` explicitly.
  - Say `EDIT_PARTIAL` is currently unused.
  - Update the "Status" line's endpoint list.
- **Do not edit `docs/spec.md`.** Flag these gaps in your summary and in the PR description instead:
  - (a) Section 04's table maps Title → default permission level, not endpoint → permission level, and there is no endpoint-level matrix anywhere.
  - (b) `EDIT_PARTIAL` is never defined.
  - (c) The new endpoint and the self-change rule aren't described in section 04 or 09.

## Conventions (same as KAN-19/22/23; follow exactly)

- English code, comments and commits. Conventional Commits with the ticket key, e.g. `feat(auth): add permission level hierarchy (KAN-20)`, `feat(auth): add endpoint to change a user's permission level (KAN-20)`, `test(auth): enforce @PreAuthorize on every endpoint via ArchUnit (KAN-20)`.
- Branch: `feature/KAN-20-rbac-enforcement`. Split into logical commits (hierarchy → endpoint → ArchUnit rule → docs, with tests alongside what they test), all in one PR into `master`.
- Never accept a clubId from the request (body, path or query). The target's club is always the caller's `ClubContext`, via the club-scoped repository.
- Do not push, open the PR, or touch CI config without first showing the diff and getting explicit confirmation.

## Out of scope

- Deciding what `EDIT_PARTIAL` actually permits, or any per-feature permission matrix. That comes with the feature endpoints.
- Changing a user's `Title`, activating or deactivating users, listing users, or deleting users.
- Revoking tokens on permission change, or a per-request DB lookup of the permission level.
- The forgot/reset password flow (KAN-21).
- Any frontend work.

## Your summary back to me

Include:
- the exact `RoleHierarchy` API you used, and **how you verified** it against the installed Spring Security version (file/class/javadoc you checked)
- how method security picks the hierarchy up
- how the `@PublicEndpoint` ↔ `PUBLIC_ENDPOINTS` consistency check works
- the full test results
- anything you deviated from in this prompt, and why
- the spec gaps above, restated
