Implement KAN-21 ("Forgot / reset password flow") in the SquadPulse backend (`backend/src/main/java/com/squadpulse/`). It's the last open ticket in the KAN-10 "Auth & Roles" epic, and it depends on KAN-19 (already merged). It adds two public endpoints, `POST /auth/forgot-password` and `POST /auth/reset-password`, and wires the existing `POST /auth/users/invite` into the same mechanism. Today an invited user is created with `passwordHash == null` and has no way to ever log in. After this ticket, the invite sends them an activation code, and they use it to set their first password.

## Step 0: sync and branch

Before anything else:
1. `git checkout master && git pull`
2. Verify local `master` is identical to `origin/master` (`git status` clean, `git rev-parse master` == `git rev-parse origin/master`). If they differ, stop and tell me. Don't try to fix it yourself.
3. `git checkout -b feature/KAN-21-password-reset`

## Verify against what's actually installed

The project is on Spring Boot **4.1.1** (Spring Security 7, Spring Data MongoDB/Redis from that BOM, Jackson 3 via `tools.jackson`). Before relying on any framework or library behavior, check it against the versions resolved from `backend/pom.xml`: property names, annotations, validator behavior, `RedisScript` return-type mapping, and so on. General knowledge may be stale or specific to another version. KAN-23 hit exactly this with a deprecated property. If you depend on something non-obvious, say in your summary how you verified it (source/Javadoc of the resolved jar, or a test).

## Read these first. Reuse their patterns and don't invent new ones.

- `auth/AuthService.java` and `auth/AuthController.java`: the public-endpoint flow, `User.normalizeEmail`, the `@GloballyScoped` `UserRepository.findByEmail` lookup, and **`AuthService.findUser`**, which sets `ClubContext` from a known clubId and restores the previous value in `finally`. You need the same thing here (see "Club context" below).
- `auth/RefreshTokenService.java`: house style for Redis state. It uses `StringRedisTemplate`, an atomic Lua `RedisScript` for every check-and-mutate, a documented key scheme in the class Javadoc, a TTL on every key, and hashed secrets in Redis. **You will also modify this class** (see "Revoking all sessions").
- `auth/LoginThrottleService.java` + `auth/LoginThrottleProperties.java`: the fixed-window Lua counter and the validated `@ConfigurationProperties` record pattern. Reuse both shapes. Don't hardcode limits as constants.
- `auth/PepperedPasswordEncoder.java`: how the pepper is used as an HMAC key. `auth/SecurityProperties.java`: where the pepper comes from.
- `auth/UserInvitationService.java`, `auth/UserManagementController.java`, `auth/User.java`.
- `auth/SecurityConfig.java` (`PUBLIC_ENDPOINTS`, which is POST-only), `common/PublicEndpoint.java`, and the tests that enforce them: `PublicEndpointsConsistencyTest`, `common/EndpointAuthorizationRuleTest` / `EndpointAuthorizationTest`.
- `common/GlobalExceptionHandler.java`, `common/UnauthorizedException.java` and its `auth` subclasses (`InvalidCredentialsException`, `InvalidRefreshTokenException`).
- `docs/spec.md` sections 09 and 10 (the "Account activation / password reset" bullet). Don't edit it (see "Docs").

## Behavior

### `POST /auth/forgot-password` (public)

Body: `ForgotPasswordRequest(@NotBlank @Size(max = 254) String email)`. Normalize the email with `User.normalizeEmail`.

1. **Rate limit per email** (Redis fixed-window counter, same Lua shape as `LoginThrottleService`), counted **before** the user lookup and counted for unknown emails exactly like registered ones. Key: `auth:reset-request:{normalizedEmail}`. Defaults: **5 requests per 24 hours** per email, set in config (see "Configuration"). The long window matters. Every new code gets 5 guesses at a 1-in-1,000,000 space, so the request limit is what bounds a persistent brute-force attempt. With 5 codes a day that's 25 guesses a day (about 1% over a year). A 15-minute window would allow roughly 480 a day. Put this reasoning in the Javadoc.
2. **Over the limit: silently do nothing.** No code is generated or sent, and the response is the same `202` as always. Do **not** return 429 here. The response must never differ in a way that depends on the email or the counter.
3. Look the user up with `userRepository.findByEmail(normalizedEmail)` (the existing `@GloballyScoped` method; **don't add a new finder**, the ArchUnit naming rule forbids it). If there's no user, or `active == false`: do nothing. A deactivated user gets no code (see `User.active`'s meaning, from KAN-17: a Club Manager cut their access, and this flow must not undo that).
4. Otherwise issue a code (see "Code issuing") and send it via `EmailSender`.
5. Always respond **`202 Accepted` with an empty body**.

The only difference between a registered and an unregistered email is a few ms of HMAC + Redis + log work. That's acceptable for now because the log-only sender is synchronous and fast. Document in `EmailSender`'s Javadoc that a real provider (Phase 6+) **must** send asynchronously, or its latency would reveal which emails are registered.

### `POST /auth/reset-password` (public)

Body: `ResetPasswordRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Pattern(regexp = "\\d{6}") String code, @NotBlank @Size(min = 8, max = 128) String newPassword)`. The password policy is length-only, following NIST/OWASP: no composition rules. Bean validation runs before the service, so a too-short password returns 400 **without** consuming an attempt or the code. Verify that with a test.

1. Normalize the email. Verify the code atomically in Redis (see "Code verification").
   - No code for this email (never requested, expired, already used, or burned by too many attempts), or a wrong code: throw `InvalidResetCodeException`. It's a new `auth` subclass of `common.UnauthorizedException`, so it maps to **401**, with one generic message such as "Invalid or expired code" for every failure case. Its layering is the same as `InvalidCredentialsException`.
   - Correct code: the script has already deleted it (single use).
2. Look the user up by email. If there's no user or `active == false`, throw the same `InvalidResetCodeException`, indistinguishable from a wrong code. The code is already consumed at this point, which is fine.
3. Set `passwordHash = passwordEncoder.encode(newPassword)` (the existing `PepperedPasswordEncoder` bean, injected as `PasswordEncoder`) and `sessionsInvalidatedAt = now` (see "Revoking all sessions"), then save.
4. Respond **`204 No Content`**. Don't log the user in. The client goes to the normal login next.

This same endpoint is the **first-activation** step for an invited user (`passwordHash == null` before, set after). No special casing is needed. Test it explicitly.

### Club context (important: the endpoint fails with 500 without this)

Both endpoints are public, so there's no `ClubContext`. `findByEmail` is global and works without one, but **`userRepository.save(...)` goes through `ClubScopedRepositoryImpl.stampOrValidateClubId`, which calls `clubContext.requireClubId()`**. In reset-password, set the context to the found user's `clubId` around the save, and restore the previous value (or clear it) in `finally`. That's exactly the pattern of `AuthService.findUser`, so extract a small shared helper if it's clean to do so. Never take a clubId from the request. Add an integration test through the real HTTP path that proves the save succeeds and writes to the right club's user.

### Invite → activation code

In `UserInvitationService.invite`, after the successful `insert`, issue a code for the new user and send it via `EmailSender`. It uses the same code-issuing path, with the same **15-minute TTL** per spec section 10. If the invited user misses that window, they use forgot-password to get a new one. The invite path does **not** count against the per-email request limit: it's an authenticated `ADMIN` action, not a public request. The Mongo insert and the Redis write aren't transactional together. If issuing or sending fails after the insert, the user still exists and can recover with forgot-password. Document that in the Javadoc and don't try to make it transactional. Update the class Javadoc, which currently says "setting the password through an activation code is KAN-21".

## Code issuing and verification (new Redis-backed service, e.g. `auth/PasswordResetCodeService`)

- **Generation:** `SecureRandom.nextInt(1_000_000)`, zero-padded to 6 digits.
- **Storage:** never store the code in plaintext or as a bare SHA-256. With only 10^6 possible codes, a plain hash is reversed instantly by anyone with a Redis dump. Store **HMAC-SHA256 keyed with the pepper**, with a domain-separation prefix (e.g. HMAC over `"reset-code:" + code`), so it can never collide with password peppering. Base64url-encode it. Compare in the Lua script by exact string equality. That's fine here because the attempt limit, not constant-time comparison, is what protects the code.
- **Key scheme** (document it in the class Javadoc like `RefreshTokenService` does):
  ```
  auth:reset-code:{normalizedEmail}   HASH   codeHash  - HMAC of the current code
                                             attempts  - wrong guesses so far
                                             TTL: code TTL (15m), set on issue
  ```
  Issuing overwrites any previous code for that email and resets `attempts`, atomically (`DEL` + `HSET` + `EXPIRE` in one script, or equivalent).
- **Verification: one Lua script, atomic.** If the key is missing, return INVALID. If `codeHash` matches, `DEL` the key and return OK. Otherwise `HINCRBY attempts 1`. If that reaches `maxAttempts` (5), `DEL` the key. Return INVALID. The goal: two concurrent requests can't both exceed the attempt limit, and two concurrent correct submissions can't both succeed. Only one gets OK. Test both, including a real concurrency test on Testcontainers Redis.
- Like the refresh-token scripts, this assumes a single Redis node. Note that in the Javadoc.

## Revoking all sessions on reset

Today `RefreshTokenService` can't revoke all of a user's sessions: families are keyed by `familyId` only, and there's no user→families index. **The chosen design:**

- Add `Instant sessionsInvalidatedAt` to `User` (nullable, meaning never). Document it: any refresh-token family issued before this instant is rejected.
- Store the issue time in the family hash: a new `issuedAt` field (epoch millis), set by `ISSUE_SCRIPT` and left untouched by `ROTATE_SCRIPT`. `ROTATE_SCRIPT` must also return it, so `RefreshSession` (or `Rotation`) carries it. Give `RefreshTokenService` an injectable `java.time.Clock`, following `JwtService`'s constructor pattern, so tests can control time.
- In `AuthService.refresh`, which already loads the user from Mongo after rotation, add one more rejection condition next to the existing `!isActive()` / `passwordHash == null` checks: `sessionsInvalidatedAt != null && issuedAt < sessionsInvalidatedAt.toEpochMilli()`. Take the same path as those checks: revoke the rotated family and throw `InvalidRefreshTokenException`.
- A family hash without `issuedAt` (created before this change) must be treated as issued at epoch 0, so it's rejected once the user has a `sessionsInvalidatedAt`. Don't let a missing field turn into a crash or a pass. Test it.
- Update `RefreshTokenService`'s key-scheme Javadoc.
- Access tokens (JWT, 15 minutes) stay valid until they expire. That's the same accepted trade-off the README already states for logout and revocation. Don't try to fix it.

## Email

- `EmailSender` interface plus `LoggingEmailSender` implementation (the only bean for now). Put them in `common` (e.g. a `common.email` package, or directly in `common` if that matches the existing layout better; check how `common` is organized). Email isn't auth-specific, and `common` must not depend on `auth`. Keep the interface minimal, e.g. `send(String to, String subject, String body)`, or a small message record.
- The logging implementation logs recipient, subject and body at INFO. The body contains the code in plaintext. That's intended for local development, but add a clear Javadoc warning that this implementation must never be active in production, and replacing it is a Phase 6+ task. Don't add profile gating now.
- The email text is English for now (a Hebrew template belongs with the real provider and i18n later). Mention that in the Javadoc.

## Configuration

A new validated `@ConfigurationProperties` record (e.g. `auth/PasswordResetProperties`, prefix `squadpulse.security.password-reset`), same pattern as `LoginThrottleProperties` (fails fast on missing or non-positive values):

```yaml
    password-reset:
      code-ttl: 15m
      max-attempts: 5
      max-requests: 5
      request-window: 24h
```

Add this to `application.yml` with a short comment, next to `login-throttle`.

## Wiring

- A new controller, or add the endpoints to `AuthController`, whichever keeps things cleaner. Both endpoints need `@PublicEndpoint` **and** entries in `SecurityConfig.PUBLIC_ENDPOINTS`. Both are POST, so the existing POST-only rule fits. Update `PUBLIC_ENDPOINTS`' Javadoc (it currently explains the reason for each public endpoint). `PublicEndpointsConsistencyTest` and the ArchUnit authorization rule must pass unchanged. If either needs changing, stop and explain why.
- CSRF is off. These endpoints don't use cookies, so nothing changes there, but confirm it in your summary.

## Tests

- **Code service (Testcontainers Redis, reuse the existing setup):** issue → verify OK once, then INVALID (single use). Wrong code increments attempts, and the 5th wrong guess deletes the key, after which the correct code fails too. Re-issuing resets attempts and invalidates the old code. The TTL expires (use a short TTL in the test). The stored value is not the plaintext code and not a plain SHA-256 of it. Concurrency: N parallel correct verifications → exactly one OK. Parallel wrong guesses → the key is gone after exactly `maxAttempts`.
- **Request rate limit:** the 6th request in the window issues and sends nothing but still returns 202. Unknown emails are counted too. Invite doesn't count.
- **forgot-password (WebMvc + integration):** identical status and body for an unknown email, an inactive user, an active user and a throttled request. `EmailSender` is invoked (use a mock or capturing sender in tests) only for an active, registered, unthrottled email.
- **reset-password:** success sets a new hash (login with the new password works, the old one fails) and returns 204. Wrong, expired or used code → 401 with the generic message. Inactive user with a valid code → the same 401. A validation failure (short password, malformed code) → 400 and the code is **not** consumed. The save lands in the correct club (see "Club context").
- **Session revocation:** log in twice (two families), reset the password, and both refresh tokens then get 401. A login **after** the reset works and can refresh. A legacy family without `issuedAt` is rejected when `sessionsInvalidatedAt` is set.
- **Invite → activation end to end:** an ADMIN invites a user, which captures a code from `EmailSender`. The invited user can't log in (401). reset-password with that code returns 204. They can now log in.
- **Properties test** for the new record (missing and non-positive values fail).
- Keep all existing tests green.

## Docs (same commit as the change, per CLAUDE.md rule 7)

- `README.md` "Auth API" table: add both endpoints (status codes and behavior as above), and update the `/auth/users/invite` row, which currently says the user "can't log in until they set one (activation — KAN-21)". Note that a password reset ends all refresh sessions.
- `CLAUDE.md`: update the "Status" line's endpoint list.
- **Do not edit `docs/spec.md`.** In your summary and in the PR description, flag these for me to decide on:
  1. Section 09 says "(Tracked separately as KAN-21; the KAN-19 invite endpoint today only creates the user with no password — it doesn't send anything yet.)", which is out of date after this PR.
  2. The spec doesn't define a password policy (now 8–128 characters, length only).
  3. The spec says "rate-limited per email" without numbers (now 5 requests per 24h, silent beyond that).
  4. Section 10 doesn't mention that a password reset revokes all refresh-token sessions.
  5. `LoggingEmailSender` logs activation/reset codes in plaintext and must be replaced before any deployment.

## Conventions

- English code, comments and commits. Conventional Commits with the ticket key. Split into logical commits, e.g. `feat(common): add EmailSender with a logging implementation (KAN-21)`, then the code service + properties, then session revocation in `RefreshTokenService`/`AuthService`, then the endpoints + `SecurityConfig`, then the invite wiring, then docs. Tests go with the code they cover.
- Save this prompt as `docs/agent-prompts/KAN-21-prompt.md` in a `docs:` commit, like the previous tickets.
- **Don't push, open a PR, or touch CI config.** When you're done, stop and give me a summary: what you built, every design decision you made that this prompt didn't dictate, anything you verified against the installed versions (and how), the full test list with results, and any open ends or risks you noticed.

## Out of scope

- A real email provider, async sending, HTML or Hebrew email templates (Phase 6+).
- Clearing `LoginThrottleService` counters on a successful reset (they're per (email, IP) and expire within 15 minutes by themselves).
- Re-sending an invite code from the admin side, or an endpoint to change a password while logged in.
- Revoking access tokens before they expire.
- The KAN-20 open items (ArchUnit fixture scanning, `EDIT_PARTIAL`, and so on).
