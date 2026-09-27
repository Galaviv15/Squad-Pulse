Implement KAN-22 ("Rate limiting / lockout on POST /auth/login") in the SquadPulse backend (`backend/src/main/java/com/squadpulse/`). This is a subtask of the already-merged KAN-19 (JWT issuing/validation and auth endpoints) — the login endpoint it protects already exists and is fully working; you're adding a guard in front of it, not building login itself.

## Context you need before starting

Read these existing files first — the new code must reuse their patterns, not invent new ones:
- `auth/AuthController.java` and `auth/AuthService.java` — the current `/auth/login` flow. Note `AuthService.login`'s `dummyPasswordHash`: it runs a real Argon2 check even for an unknown email, purely so response timing can't reveal whether the email is registered. Your throttling logic must not reintroduce that kind of leak (see "Where this hooks in" below).
- `auth/RefreshTokenService.java` — this is the house style for anything Redis-backed in this module: a `StringRedisTemplate`, an atomic `RedisScript` (Lua) for any check-and-mutate sequence so concurrent requests can't race each other, a documented key-naming scheme in a class-level Javadoc, and everything keyed with a TTL so nothing needs manual sweeping. Follow the same shape.
- `auth/TokenProperties.java` and `auth/SecurityProperties.java` — the pattern for typed, validated `@ConfigurationProperties` records that fail fast at startup on a bad value, instead of hardcoded constants in code.
- `common/GlobalExceptionHandler.java`, and the KAN-19-introduced `common/UnauthorizedException.java` / `common/ConflictException.java` with their `auth`-specific subclasses (e.g. `auth/InvalidCredentialsException.java`). Follow that exact layering: a generic exception type in `common` mapped to its HTTP status, subclassed in `auth` for this specific case — so `common` still doesn't depend on `auth`.
- `auth/InvalidCredentialsException.java` — the 401 this throttle sits alongside; study how it's thrown and handled so the new 429 path is consistent with it.

## What to build

A guard in front of `POST /auth/login` that limits repeated failed attempts, keyed by the **(normalized email, source IP) pair** — not by email alone, and not by IP alone. Keying by email alone would let anyone who knows a victim's email address lock them out of their own account by deliberately failing their password a few times; keying by IP alone would let an attacker rotate IPs to bypass it entirely, but would also throttle every user behind a shared IP (office/NAT) together. The pair is the right balance.

**Threshold: 5 failed attempts within a 15-minute window**, per (email, IP) pair, matching the numbers already used for the KAN-21 activation-code attempt limit (for consistency, not because the two mechanisms share code).

**Response when throttled: `429 Too Many Requests`**, distinct from the `401` used for wrong credentials. This is fine from an enumeration standpoint: the throttle key is (email, IP) regardless of whether that email is actually registered, so a `429` doesn't tell an attacker anything they don't already know (they're the one making the repeated requests). Include a `Retry-After` header with the remaining seconds in the window if you can derive it from the Redis key's TTL — nice to have, not a hard requirement if it complicates the implementation.

## Where this hooks in

In `AuthService.login`:
1. Before doing anything else (before the password/Argon2 check), check whether the (email, IP) pair is currently over the threshold. If so, throw immediately without touching the password check at all — this is what actually stops the load, not just the response.
2. If not throttled, run the existing login check unchanged.
3. If the login check fails (wrong password, unknown email, no password set, deactivated — all the existing `InvalidCredentialsException` cases), atomically increment the attempt counter for that (email, IP) pair (setting the window TTL only on the first increment — the classic fixed-window Lua counter: `INCR`, and only if the result is `1`, `EXPIRE` it to the window length). Then continue throwing `InvalidCredentialsException` as today — don't change the 401 behavior for attempts under the threshold.
4. If the login check succeeds, clear/delete the counter for that (email, IP) pair, so a legitimate user who mistyped their password a couple of times isn't left with a partially "used up" allowance hanging over their next real session.

The IP is the servlet request's remote address (`HttpServletRequest.getRemoteAddr()`) — **do not** trust `X-Forwarded-For` or similar client-supplied headers for this; a deployment behind a reverse proxy that sets a trustworthy value is a later, deployment-specific decision (the actual hosting target isn't chosen yet — see docs/spec.md section 11), and blindly trusting a client-controllable header would let an attacker spoof a fresh IP on every request and bypass the whole mechanism. Leave a short comment noting this is revisited once a deployment target and its proxy setup are known.

## New code

- `auth/LoginThrottleService` (or a name you prefer that's consistent with `RefreshTokenService`'s naming) — the Redis-backed counter, with a class-level Javadoc documenting the key scheme, e.g. `auth:login-throttle:{normalizedEmail}:{ip}` (a `STRING` counter, `EXPIRE`d to the window on first increment). Unlike refresh tokens, there's no need to hash the email here — it isn't a bearer credential, just a rate-limit key — but keep the key scheme documented and explicit the way `RefreshTokenService` does.
- A `LoginThrottleProperties` record (or fold into a new nested config) bound from `squadpulse.security.login-throttle.*`, validated the same way as `TokenProperties`: `maxAttempts` and `window` (Duration), failing fast at startup if missing/non-positive. Don't hardcode 5 / 15 minutes as constants.
- `common/TooManyRequestsException` (base, mapped to 429 in `GlobalExceptionHandler`) with an `auth`-specific subclass (e.g. `auth/LoginThrottledException`) — following the exact `UnauthorizedException`/`InvalidCredentialsException` pattern from KAN-19.

## Testing

- Unit tests for the throttle service: under threshold allows attempts through, the Nth failure trips it, a successful login resets the counter, two different IPs for the same email don't share a counter, two different emails from the same IP don't share a counter.
- `AuthServiceTest` / `AuthControllerTest` additions: 5 failed logins still return 401 each; the 6th returns 429 (with `Retry-After` if implemented); a successful login after some failures returns 200 normally and clears the block.
- Integration test on real Redis (Testcontainers, same setup KAN-19 already added) covering TTL expiry — after the window passes, the pair is allowed again. You can construct the service with a very short window in this specific test rather than waiting out the real 15 minutes.

## Conventions (same as KAN-19 — follow exactly)

- English code/comments/commits. Conventional Commits with the ticket key, e.g. `feat(auth): add rate limiting on login attempts (KAN-22)`.
- Branch: `feature/KAN-22-login-rate-limiting`. Split into logical commits (config/properties → throttle service → wiring into AuthService + new exception/handler → tests), one PR into `master`.
- If this adds a new `squadpulse.security.login-throttle.*` config block, mention it in `README.md` in the same commit if it's the kind of thing someone running the backend locally would need to know about (it isn't a required env var if you ship sensible defaults, so this may be a small addition or none at all — use your judgment, but don't skip checking).
- Do **not** edit `docs/spec.md` yourself. Instead, note explicitly in the PR description that `docs/spec.md` section 10 currently says "`/auth/login` itself has no rate limiting or lockout yet — tracked as a follow-up (KAN-22)", and that this line is now out of date once this PR lands — flag it for Gal to update, don't change it yourself.
- Do not push commits, open the PR, or modify CI config without stopping to show the diff and get explicit confirmation first.

## Out of scope

- Anything about the KAN-21 activation-code attempts — that's a separate mechanism (OTP-style code validation), not shared code with this login throttle, even though the numbers happen to match.
- Choosing how IP is derived behind a real reverse proxy/load balancer — that's a deployment-time decision, not this ticket's.
- Any change to `/auth/refresh` or `/auth/logout` — this ticket is `/auth/login` only.
