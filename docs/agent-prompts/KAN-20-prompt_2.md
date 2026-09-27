KAN-20 follow-up. Don't push or open the PR yet. Your summary is solid, but it's missing two things the original prompt asked for explicitly. I also need a few small changes and some files for review.

## 1. Test evidence (report back, no code change unless something fails)

- Run the full backend suite exactly as CI does (check `.github/workflows/ci.yml`; normally `./mvnw verify` from `backend/`). Paste the Surefire/Failsafe summary lines: tests run, failures, errors, skipped, per plugin. Also paste the final BUILD SUCCESS/FAILURE line.
- List every integration test (Testcontainers, real Mongo) for `PATCH /auth/users/{id}/permission-level` by **class and method name**, with one line each on what it asserts. The original prompt required these four. For each one, confirm it exists and point to it, or write it now if it's missing:
  1. **Club A upgrades a user in club A:** ADMIN in club A upgrades a `VIEW_ONLY` user in club A to `EDIT_PARTIAL`, and the new level is **persisted** (re-read from the DB, not just the response body).
  2. **Cross-club lookup:** ADMIN in club A targets a user id from **club B** → 404. Club B's user must be **unchanged in the DB** (re-read it and assert `permissionLevel`, and `updatedAt` too if practical). The 404 body/message must be identical to the one for a nonexistent id; assert that equality explicitly. This is the most important test in the ticket (CLAUDE.md standing rule 4).
  3. **Extra fields in the body:** a body containing `clubId`, `title` and `email` alongside `permissionLevel` changes only the permission level. Re-read from the DB and assert that `clubId`, `title` and `email` are unchanged.
  4. **Level after refresh:** after a successful change, the target user's `/auth/refresh` yields an access token whose `permissionLevel` claim is the new value. Parse the returned token; don't infer the claim from the status.
- For the no-op path (same level → 200 without saving): confirm the club-scoped lookup still runs **before** the no-op short-circuit. A cross-club id with a matching level must still be 404, never 200. Add a test for exactly that case if there isn't one.

## 2. Small changes

- `PUBLIC_ENDPOINTS` is POST-only. Add a short comment stating that assumption in `SecurityConfig` (next to `PUBLIC_ENDPOINTS`) and in `PublicEndpointsConsistencyTest`. The comment should say that adding a public non-POST endpoint means changing both the `requestMatchers(HttpMethod.POST, ...)` rule and the test.
- Commit the prompt files in a separate docs commit, matching how KAN-22/23 were done: `docs/agent-prompts/KAN-20-prompt.md`, plus the text of this follow-up prompt saved as `docs/agent-prompts/KAN-20-prompt_2.md`. Use a message like `docs: add agent prompts for KAN-20 (KAN-20)`.
- The separate hierarchy-docs commit (`2643d0d`) is fine as-is. Don't rewrite history.
- Leave the `archunitfixture` controllers being picked up by `@SpringBootTest` as-is for now. Just mention in your summary whether any `@SpringBootTest` context actually maps their `/archunit-fixture/...` paths, so we know it's real rather than theoretical.

## 3. Files for code review

Print the **full current contents** of these files in your reply. Don't summarize them; I'll review the code itself:
- `backend/src/main/java/com/squadpulse/auth/UserPermissionLevelService.java`
- the user-management controller (after the rename)
- `backend/src/main/java/com/squadpulse/auth/SecurityConfig.java`
- the integration test class(es) covering the new endpoint

## Conventions (unchanged)

English code/comments/commits, Conventional Commits with `(KAN-20)`, same branch `feature/KAN-20-rbac-enforcement`. Don't edit `docs/spec.md`. **Do not push or open the PR.** Stop after this and report back.

## Your summary back to me

Include:
- the test-run summary lines
- the integration-test list (class#method + what it asserts), marking which tests are new in this round
- whether the no-op path is covered for the cross-club case
- whether the fixture paths are actually mapped in any `@SpringBootTest` context
- the commits added in this round
- the four files in full
