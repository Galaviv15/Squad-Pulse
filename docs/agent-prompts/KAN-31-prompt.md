# KAN-31 — Error handling: log unexpected 500s, map standard Spring MVC errors instead of 500

## Step 0 — Start from a clean, current master

```
git checkout master && git pull
```

Verify local `master` equals `origin/master` (`git rev-parse master origin/master` must print the same hash; expected to be at or after `403df45`, the KAN-29 merge). If it doesn't, stop and report. Then create the branch `fix/KAN-31-error-handling`.

## Context

Jira KAN-31 (no epic, technical). Two gaps in `common.GlobalExceptionHandler`, found during KAN-21 and KAN-27:
1. `handleUnexpected` returns a generic 500 but **logs nothing**, so a real bug leaves no trace.
2. Standard Spring MVC client errors fall into that catch-all and become **500**. Confirmed in KAN-27: a body with `Content-Type: text/plain` to `POST /squad/players` gives a 500 instead of 415.

The Frontend MVP (Phase 3) starts right after this ticket. Wrong-`Content-Type` requests and unexplained 500s are exactly what frontend development will hit first, which is why this comes first.

Read first: `CLAUDE.md` (the error-handling paragraph), `docs/spec.md` section 10 ("Error responses"), `common/GlobalExceptionHandler`, `common/ApiErrorResponse`, `auth/SecurityConfig` (entry point / access-denied handler, `PUBLIC_ENDPOINTS`), and the tests `GlobalExceptionHandlerTest`, `PlayerControllerTest` and `AuthControllerTest`.

**What I verified on master (`403df45`); don't redo, but keep it covered by regression tests:**
- Already mapped since the ticket was written:
  - `HttpRequestMethodNotSupportedException` → 405 with `Allow` (KAN-23)
  - `NoHandlerFoundException` → 404 (KAN-23; static resource mappings are off)
  - the multipart family: `MaxUploadSizeExceededException` / `PayloadTooLargeException` → 413, `MissingServletRequestPartException` / `MultipartException` → 400 (KAN-29)
- Still logging nothing on a 500:
  - `handleUnexpected`
  - `handleMissingClubContext`, which is always a server bug: a code path ran without a club context
  - `handleMethodValidation` when `isForReturnValue()` (it delegates to `handleUnexpected`)
- A `Logger` already exists in the class (used for the 409 WARN on `OptimisticLockingFailureException`).
- No controller uses `@RequestHeader` or required `@RequestParam` / `@CookieValue` today (the refresh cookie is `required = false`), and none declares `consumes` / `produces`. So some standard exceptions are currently unreachable. Map them anyway (the next controllers will hit them), but say which ones are reachable today.

## Step 1 — Inventory first (report it as a table)

From the **installed** Spring Framework (7.0.9 per KAN-28; confirm with `./mvnw dependency:tree`), read the list of exceptions that `ResponseEntityExceptionHandler` / `DefaultHandlerExceptionResolver` handle, in the source/jar actually on the classpath, not from memory. For each one give:
- the standard status Spring assigns
- what this app returns **today** (prove the important ones with a throwaway test or by reasoning from the handler order, and say which)
- whether it's reachable in this app today
- what you'll do with it

Include at least:
- `HttpMediaTypeNotSupportedException`
- `HttpMediaTypeNotAcceptableException`
- `MissingServletRequestParameterException`
- `MissingRequestHeaderException` / `MissingRequestCookieException` / `ServletRequestBindingException`
- `MissingPathVariableException` (Spring treats it as a **500**: a server-side mapping bug, not a client error; keep it a 500)
- `TypeMismatchException` / `ConversionNotSupportedException`
- `HttpMessageNotWritableException`
- `NoResourceFoundException`
- `AsyncRequestTimeoutException`
- `MethodValidationException`
- `ResponseStatusException` / `ErrorResponseException`

## Step 2 — Agreed design (implement this)

**Do not extend `ResponseEntityExceptionHandler`.** It renders RFC 9457 `ProblemDetail` bodies, not our `ApiErrorResponse` shape. Making it fit means overriding its rendering for everything, and it declares handlers for exceptions we already map ourselves (`MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `HandlerMethodValidationException`, `NoHandlerFoundException`, 405, `MissingServletRequestPartException`...). Check what Spring does with two `@ExceptionHandler`s for the same exception (in one class, or a subclass overriding them) and say it in one line in the summary as part of the justification.

Instead:

1. **Explicit handlers** in the existing style, same `ApiErrorResponse` format, for the client errors that matter:
   - `HttpMediaTypeNotSupportedException` → **415**. Pass on the headers Spring puts on the exception (`ex.getHeaders()`, e.g. `Accept` / `Accept-Patch`), like the 405 handler does with `Allow`.
   - `HttpMediaTypeNotAcceptableException` → **406**.
   - `MissingServletRequestParameterException` → **400** in the validation format: `"<name>: is required"`.
   - Missing header / cookie (`MissingRequestHeaderException`, `MissingRequestCookieException`) → **400** `"<name>: is required"`.
   - Anything else that Step 1 shows is a reachable client error and currently a 500.
   - **No message may echo client input.** Spring's own messages quote it (e.g. `Content-Type 'text/plain;charset=UTF-8' is not supported`), and the existing rule is "rejected values are never echoed". Write fixed messages, e.g. `"Unsupported Content-Type; expected application/json"` is fine only if the expected value comes from the server (the exception's supported types), never from the request.

2. **Safety net in `handleUnexpected`** for any framework exception we don't map explicitly: if the exception implements `org.springframework.web.ErrorResponse` (verify which of the above implement it in 7.0.9), answer with **its** status code and headers instead of 500:
   - body: our format, `error` = the status's standard reason phrase, `message` = a fixed generic text (not the exception's message or `ProblemDetail` detail, which can quote input)
   - 4xx: no ERROR log (DEBUG at most, no stack trace)
   - 5xx: ERROR with stack trace, like any unexpected exception

   Any other exception: 500 with the existing generic body.

3. **Logging rules:**
   - Every 500 from this class logs at **ERROR with the stack trace**, plus the HTTP method and request path. Take them from an `HttpServletRequest` handler parameter. Path only: **no query string, no headers, no body, no cookie / token values**. This covers:
     - `handleUnexpected` (including the return-value validation path)
     - `handleMissingClubContext`
     - an `ErrorResponse` with a 5xx status
   - 4xx client errors are **never** logged at ERROR. Leave the existing 409 WARN as is.
   - **A client that disconnects mid-response** (e.g. leaving the page while a player photo downloads) must not produce an ERROR log, and the handler must not try to write a body to a dead connection. Find out how that surfaces in this setup (Spring 6.1+ has `AsyncRequestNotUsableException` and `DisconnectedClientHelper`; check what's in 7.0.9) and handle it accordingly. Report what you found, with evidence.

4. Keep every existing handler and its exact behaviour (status, `error`, `message`, `details`, headers). This ticket adds; it doesn't change existing responses.

## Step 3 — Investigate only, don't change: errors outside the DispatcherServlet

Exceptions thrown in a servlet filter (e.g. inside `auth.JwtAuthenticationFilter`, or an unexpected failure in the security chain) never reach `@RestControllerAdvice`. They end up in Spring Boot's `/error` handling, possibly with a different JSON shape, and are logged (or not) by the container. Find out with a throwaway test:
- What does the client get, and is `/error` itself behind the security chain (could the response become a 401 instead)?
- Is it logged?

Report it with a recommendation. **Don't fix it in this ticket** unless it's a one-line config change with no side effects, and even then report before doing it.

## Verify against what's installed, don't assume

Spring Framework / Boot / Security versions from `pom.xml` / `dependency:tree`. Evidence (where you looked, what you found) for:
1. The exception list and statuses from the installed `ResponseEntityExceptionHandler` / `DefaultHandlerExceptionResolver`.
2. Which exceptions implement `ErrorResponse`, and what `getHeaders()` carries for 415 / 406.
3. Two handlers for one exception: what Spring does.
4. How a client disconnect surfaces.
5. Auth ordering (see the tests).

If anything differs from this prompt, stop and report rather than improvising.

## Tests (required)

Follow the existing style (`GlobalExceptionHandlerTest` for unit-level cases; WebMvc with the real security chain, `AuthWebMvcTestConfig` + `TestAccessTokens`, for end-to-end ones).

1. **One test per newly mapped exception:** status, `ApiErrorResponse` shape (`status`, `error`, `message`, `details`), headers (e.g. `Accept` on 415). Assert that client input is **not** echoed: send e.g. `Content-Type: text/x-marker-12345` and assert `x-marker-12345` appears nowhere in the body.
2. **Real endpoints:**
   - `POST /squad/players` with `text/plain` and a valid token → 415
   - `POST /auth/login` (public) with `text/plain`, no token → 415
   - `GET /squad/players` with `Accept: application/xml` → 406
   - `GET /squad/players/{id}/photo` with `Accept: application/json` → report what it returns and assert it (not a 500)
3. **Auth ordering:**
   - `POST /squad/players` with `text/plain` and **no token** → still 401
   - with a `VIEW_ONLY` token → report and assert the actual result. Content negotiation runs before `@PreAuthorize`, so expect 415 rather than 403, the same known ordering as "invalid body → 400 before 403" (KAN-26 open item; no data leaks). Say what you observe.
4. **Safety net:** a `ResponseStatusException(HttpStatus.I_AM_A_TEAPOT)` (or any 4xx) and a 5xx `ErrorResponseException` thrown from a test-only handler. Assert their status, our body format, the generic message, and that the 4xx isn't logged at ERROR while the 5xx is. Don't add test controllers that get component-scanned into other `@SpringBootTest` contexts (known issue with `common/archunitfixture`); use a standalone / nested setup.
5. **Logging** (`OutputCaptureExtension` or a log appender):
   - an unexpected `RuntimeException` → generic 500 body unchanged; one ERROR line with the stack trace, method and path
   - send a request with a query string and a body containing a marker (e.g. `secret-marker-678`) and an `Authorization` header: none of the three appears in the log
   - `MissingClubContextException` → ERROR logged, response unchanged
   - a 4xx (e.g. 415, 400 validation) → no ERROR line
6. **Client disconnect:** a test if it's feasible to simulate; otherwise explain why not and what you verified instead.
7. **Regression:** every existing mapping (400 validation / malformed body / type mismatch / field-scoped BadRequest, 401, 403, 404 not-found and no-handler, 405 with `Allow`, 409 conflict and optimistic-lock, 413 ×2, 429 with `Retry-After`, multipart 400s, 500 missing club context) still returns exactly what it did. Existing tests should pass **unchanged**; list which ones prove it, and add any missing case.

Run `./mvnw verify` with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25). Everything green, Spotless clean. Report the total test count (it was 701 after KAN-29).

## Docs

- **CLAUDE.md** (rule 7): update the error-handling paragraph:
  - the new mappings
  - the `ErrorResponse` safety net
  - the logging rule (500 → ERROR with stack trace, method + path only; 4xx never ERROR)
  - "messages never echo client input"
- **README**: the error section (or a short new one), with the status list a client can get.
- **`docs/spec.md`: do NOT edit it.** List in your summary exactly what needs updating (expected: section 10 "Error responses", for the new statuses and the logging rule), with the exact facts.

## Commits

Small, focused commits, e.g.:
- `fix(common): log unexpected server errors with method and path (KAN-31)`
- `fix(common): map unsupported / unacceptable media type and missing parameters (KAN-31)`
- `test(common): ... (KAN-31)`

Docs go in the same commit as the change they describe (rule 7). **Do not push and do not open a PR.**

## Summary to return

1. Files changed / added, one line each.
2. The Step 1 inventory table (exception, Spring's status, before, after, reachable today).
3. The final status list a client can receive, with the exact `error` / `message` for each new mapping.
4. Evidence for each item in "Verify against what's installed".
5. Step 3 findings and recommendation.
6. Test list with counts, and the full build result (command + outcome + total count).
7. Deviations from this prompt, and why.
8. The spec update list.
9. Open ends / risks.
10. Print the full final `GlobalExceptionHandler.java`, so it can be reviewed directly.
