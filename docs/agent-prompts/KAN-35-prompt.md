# KAN-35: Render filter-level and container errors in the ApiErrorResponse shape

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `f0fb031` (the KAN-40 merge). If it doesn't, stop and report. Then create the branch `fix/KAN-35-filter-error-shape`.

## Context

This is Jira KAN-35 (no epic). It blocks KAN-46, the frontend API client, which will parse errors on the assumption that **every** error body has the `ApiErrorResponse` shape. It was found in KAN-31, Step 3.

The problem: `common.GlobalExceptionHandler` is a `@RestControllerAdvice`, so it only sees exceptions raised inside Spring MVC. Two kinds of failure never reach it. Spring Boot's `/error` answers them instead, in Boot's own shape (`{timestamp, status, error, path}`, with no `message` / `details`), or with the Whitelabel HTML page for `Accept: text/html`:

1. **An exception thrown in a servlet filter**, before the `DispatcherServlet`. For example, an unexpected failure in `auth.JwtAuthenticationFilter` / `JwtService.parse`, or anything Spring Security's `ExceptionTranslationFilter` rethrows because it isn't a security exception. Today Tomcat logs it at ERROR with a stack trace, but **without** the method and path.
2. **A `response.sendError(...)` from below Spring MVC.** I checked one in the Spring Security 7.1.1 source: `FilterChainProxy`'s default `requestRejectedHandler` is `HttpStatusRequestRejectedHandler`, which calls `response.sendError(400)` when the `StrictHttpFirewall` rejects a request (e.g. `//`, `;`, an encoded `/` (`%2F`) or `..` in the path). That 400 goes through Tomcat's error-page dispatch to `/error`. **This is the more common case of the two, and the frontend can hit it.** Re-confirm it against the installed jar.

**The Jira ticket's "Proposed" section is a starting point. The design below is decided with Gal. Follow this prompt.**

Read these first:
- `CLAUDE.md`: the access-control and error-handling paragraph (it ends with the "known gap" sentence this ticket removes), and rule 7.
- `docs/spec.md` section 10, "Error responses" (incl. the "Known gap" sub-bullet).
- README "Errors" section (incl. its "Known gap" paragraph).
- `common`: `GlobalExceptionHandler` (especially `handleUnexpected`, `logServerError`, `respond`, and the message constants), `ApiErrorResponse`, `PublicEndpoint`.
- `auth`: `SecurityConfig` (the `DispatcherType.ERROR` `permitAll`, `writeError`), `JwtAuthenticationFilter`.
- Tests: `GlobalExceptionHandlerTest`, `GlobalExceptionHandlerLoggingTest` (how log assertions are done), `EndpointAuthorizationRules` / `EndpointAuthorizationRuleTest` / `EndpointAuthorizationTest`, `PublicEndpointsConsistencyTest`, and the `common/archunitfixture` package.
- `application.yml`.

**Facts I checked (master `f0fb031`, and the Boot `v4.1.1` / Security `7.1.1` sources). Re-confirm every one against what's installed. Don't take them on faith:**
- **`server.error.whitelabel.enabled` is deprecated at level `error` in Boot 4.0.0+.** The replacement is `spring.web.error.whitelabel.enabled` (the error path likewise moved to `spring.web.error.path`). The Jira ticket names the old property. **Don't use it.** This is the same trap as KAN-23. Confirm it in the installed `spring-boot-web-server` jar's configuration metadata, and confirm the property `ErrorMvcAutoConfiguration` actually reads (`@ConditionalOnBooleanProperty(name = "spring.web.error.whitelabel.enabled", ...)`).
- In Boot 4.1.1, `ErrorController` is `org.springframework.boot.webmvc.error.ErrorController`. `BasicErrorController` is registered with `@ConditionalOnMissingBean(value = ErrorController.class, search = SearchStrategy.CURRENT)`, so our own `ErrorController` bean replaces it. `ErrorPageCustomizer` still registers the global error page at the error path.
- `SecurityConfig` permits `DispatcherType.ERROR`. A direct `GET /error` without a token is a `REQUEST` dispatch, so it is a 401 today and must stay one.
- `JwtAuthenticationFilter` is a `OncePerRequestFilter`. Confirm whether it runs on an ERROR dispatch (`shouldNotFilterErrorDispatch()`, default `true`), and so whether an error dispatch has an authenticated principal. The design must not depend on one.
- `EndpointAuthorizationRules` requires every `@Controller` method with `@RequestMapping` to have `@PreAuthorize` or `@PublicEndpoint`. Neither fits `/error`: `@PreAuthorize` would deny the unauthenticated error dispatch, and `@PublicEndpoint` would fail `PublicEndpointsConsistencyTest` (public = POST + listed in `PUBLIC_ENDPOINTS`). See decision 3.
- The last full build (KAN-40) had **992** tests.

## Decisions agreed with Gal (implement exactly these)

### 1. A last-resort filter for exceptions thrown by filters

A new `common` filter (e.g. `UnhandledExceptionFilter`, a `OncePerRequestFilter`) that runs **first**, before Spring Security's filter chain. It wraps `chain.doFilter(...)` and catches any `Exception` that escapes it (`ServletException`, `IOException`, `RuntimeException`). An `Error` (e.g. `OutOfMemoryError`) is not caught.

Its behavior mirrors `GlobalExceptionHandler.handleUnexpected` for a non-`ErrorResponse` exception:
- **Client disconnected** (Spring's `DisconnectedClientHelper.isClientDisconnectedException`): DEBUG only, write nothing.
- **Response already committed:** log at ERROR (stack trace + method + path), write nothing.
- **Otherwise:** log at ERROR **once**, with the stack trace and the request's method and path (never the query string, headers, body or cookies). Reset the buffer and answer `500` with `ApiErrorResponse(status 500, "Internal Server Error", "An unexpected error occurred", [])` as `application/json`, regardless of `Accept`. Use the same constants as `GlobalExceptionHandler`, not copies.

Because the exception never leaves the filter, Tomcat doesn't log it a second time and no error dispatch happens. **Share the logging and the disconnect check with `GlobalExceptionHandler`** (e.g. a small package-private helper in `common`) so the two can't drift. Same log format, and the same logger or a clearly named one. Say which you chose.

- **Ordering:** register it so it wraps Spring Security's `DelegatingFilterProxy` (Boot registers that at `SecurityProperties.DEFAULT_FILTER_ORDER`, -100; verify). Choose an order (e.g. `Ordered.HIGHEST_PRECEDENCE + 1`, or via a `FilterRegistrationBean`) and **prove the real order in a test** against the running app's registered filters, not by reading annotations. Keep it out of the bootstrap profile's way: `web-application-type: none` means no filters, but confirm it doesn't break startup.
- **Scope:** exceptions raised inside Spring MVC are already resolved by `GlobalExceptionHandler` and must never reach this filter. Confirm that `handleUnexpected` returning `null` (disconnect / committed) counts as resolved, so there's no double log. If something does escape the `DispatcherServlet` (e.g. a failure while writing an error body), this filter is the net. That's fine.
- **What it must not change:** 401/403 from the security chain (`SecurityConfig.writeError`) and every response `GlobalExceptionHandler` produces stay byte-for-byte the same.

### 2. A custom `ErrorController` for everything that still reaches `/error`

A new `common.ApiErrorController` implementing Boot's `ErrorController`, mapped at the error path for **all** HTTP methods. The error dispatch keeps the original method, e.g. POST. It replaces `BasicErrorController`. Rules:

- **Status:** from `RequestDispatcher.ERROR_STATUS_CODE`. A missing or invalid value means `500`.
- **Body:** always `ApiErrorResponse` as `application/json`, whatever `Accept` says (`text/html`, `application/xml`, unparseable). Build it through the same `respond(...)` path, or an extracted shared equivalent, with the explicit `Content-Type`, so content negotiation can't fail. `error` is the status's reason phrase, as in `GlobalExceptionHandler.reasonPhrase`. `message` is fixed: `"An unexpected error occurred"` for 5xx, `"The request could not be processed"` for 4xx. `details` is `[]`. **Never** use `ERROR_MESSAGE`, the exception's message, or the request path in the body: Tomcat's and the firewall's texts can quote the request.
- **Logging:**
  - 4xx: never at ERROR. DEBUG is fine, method + original path (`RequestDispatcher.ERROR_REQUEST_URI`).
  - 5xx with no exception attribute (a bare `sendError(5xx)`): ERROR with method + original path, no stack trace.
  - Exception attribute present (`RequestDispatcher.ERROR_EXCEPTION`): with decision 1 in place this shouldn't happen. If it does, log it at ERROR with the stack trace. Tomcat will have logged it too. Document that as accepted in the Javadoc rather than suppressing Tomcat's logger.
- **Not an error dispatch:** an authenticated client calling `GET /error` directly (a `REQUEST` dispatch, `request.getDispatcherType() != ERROR`) gets the same `404` as any unknown path, `"No endpoint GET /error"`, matching `handleNoHandlerFound`'s text. It never gets a fake 500. Without a token it is still the security chain's `401`.
- **Disable Whitelabel** with `spring.web.error.whitelabel.enabled: false` in `application.yml` (see Facts, not the `server.error.*` name), with a comment explaining why. Our controller already answers every error dispatch, so this is a second safeguard. Keep the default error path, `/error`.

### 3. ArchUnit: exempt exactly this one controller

Change `EndpointAuthorizationRules` so `ApiErrorController`'s handler methods are exempt, **by exact class**, and nothing else. **Not** "any class implementing `ErrorController`": that would let any future controller escape the rule just by implementing the interface. The exemption's Javadoc says why: the error dispatch has no authenticated caller, and it renders an error that already happened, exposing nothing.

Extend `EndpointAuthorizationRuleTest` with a fixture: a different `@RestController` implementing `ErrorController`, with a `@RequestMapping` and no annotations, **still fails the rule**. **Careful:** the existing fixtures in `common/archunitfixture` are component-scanned into every `@SpringBootTest` context (a known issue from KAN-20). If this new fixture is scanned too, the real app would have a second `ErrorController` bean and possibly a second `/error` mapping, and every integration test would break or silently use the wrong controller. Make sure it is never a bean in any app context, e.g. a nested class of the rule test, or explicitly kept out of the scan, and use a mapping path other than `/error`. Prove it: a test asserts that `ApiErrorController` is the only `ErrorController` bean in the full context. `EndpointAuthorizationTest` (the real codebase) and `PublicEndpointsConsistencyTest` pass. `ApiErrorController` has no `@PublicEndpoint` and is not added to `PUBLIC_ENDPOINTS`.

### 4. Nothing else changes

- No change to `GlobalExceptionHandler`'s responses, the security rules, or the JWT filter's logic.
- No new dependency.
- Moving `SecurityConfig.writeError` onto the new shared builder is optional. Only do it if it's a pure refactor with identical bytes, and say so.
- Tomcat's own responses to protocol-level garbage (a malformed request line or headers, rejected in the connector before the servlet context) are **out of scope**. List them as a known limit.

## Verify against what's installed

Don't rely on general knowledge. Check against `pom.xml` / `./mvnw dependency:tree` (Boot 4.1.1, Spring Framework 7.0.9, Spring Security 7.1.1, embedded Tomcat: report its version):
- the whitelabel property name and its deprecation (from the installed jar's metadata), and that the old name is not used anywhere.
- that `BasicErrorController` is gone from the running context and `ApiErrorController` is the only `ErrorController` bean (test).
- the firewall's default rejection path (`HttpStatusRequestRejectedHandler` → `sendError(400)`) and which request actually triggers it through embedded Tomcat. Tomcat itself may reject or normalize some paths before Spring sees them, so find one that really reaches the firewall, e.g. `/squad//players` or a `;jsessionid=` path parameter. Report which.
- whether Tomcat's `StandardWrapperValve` logs a filter exception at ERROR today (it's the double log decision 1 avoids). Show that with the new filter it no longer does.
- the actual filter order (decision 1).

## Tests

**MockMvc does not perform Tomcat's error-page dispatch**, so the `/error` behavior and the filter-exception path must be tested against a **real embedded server** (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + an HTTP client, with the usual Mongo/Redis Testcontainers setup). Unit-level tests of the controller logic on top of that are welcome. To make a filter throw, register a **test-only** filter (test sources only, active only in that test's context) that throws on a dedicated test path or header. Never add a throwing hook to production code.

Required:
1. **Filter exception → our 500.** A test filter ordered after the new last-resort filter (e.g. right after Spring Security's chain, where a real filter failure would happen) throws a `RuntimeException` with a secret-looking message (e.g. `"secret-db-password"`):
   - The response is `500`, `Content-Type: application/json`, and the exact `ApiErrorResponse` shape (`timestamp`, `status`, `error`, `message`, `details`).
   - The body doesn't contain the exception message or the path.
   - Exactly **one** ERROR log event for it in the whole app: ours, with method + path and the stack trace, and none from Tomcat's container logger. Capture both loggers and assert the total.
   - Repeat with `Accept: text/html` and `Accept: application/xml`: still JSON, no HTML.
2. **Firewall rejection → our 400.** The request found in "Verify" gets `400`, `ApiErrorResponse` JSON, message `"The request could not be processed"`, no echo of the path. No ERROR log. The same with `Accept: text/html`.
3. **Bare `sendError(5xx)`** (test filter calls `response.sendError(503)`) → `503` in our shape with the generic 5xx message, one ERROR log without a stack trace.
4. **Direct `/error`:** without a token → `401` (the security chain's body, unchanged). With a valid token → `404` `"No endpoint GET /error"`. Neither is logged at ERROR.
5. **Committed response** (a test filter writes and flushes part of a body, then throws): ERROR logged once, status unchanged, no JSON appended.
6. **Client disconnect** (a test filter throws an exception `DisconnectedClientHelper` recognizes): DEBUG only, nothing at ERROR.
7. **ArchUnit** fixture test from decision 3.
8. **Regression:** `GlobalExceptionHandlerTest`, `GlobalExceptionHandlerLoggingTest`, the security / 401 / 403 tests and everything else pass unchanged.

Run `./mvnw verify` with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25). Everything green, Spotless clean. Report the total test count (992 after KAN-40).

## Docs

- **README "Errors":** remove the "Known gap" paragraph. Say that the shape now also covers errors raised before Spring MVC: a failure in a filter → `500`, a request the security firewall rejects (e.g. a `//` in the path) → `400`, all in the same JSON with generic messages. Name the protocol-level limit in one sentence.
- **CLAUDE.md:** replace the "known gap" sentence at the end of the error-handling paragraph. Say: filter exceptions are caught by the new filter (logged once, generic 500), anything reaching `/error` is rendered by `ApiErrorController` in the same shape, a new filter must not write its own error format, and `ApiErrorController` is the one class exempt from the endpoint-authorization ArchUnit rule. Change anything else only if it becomes inaccurate, and say what you changed.
- **`docs/spec.md`: do NOT edit it.** In your summary, list exactly what needs updating in section 10, "Error responses", with the exact facts. I'll prepare the spec change myself. Expected at least:
  - remove the "Known gap" sub-bullet and state the new behavior (filter exceptions, firewall rejections, direct `/error`).
  - the logging sub-bullet: a filter exception is logged once, by us, with method + path.
  - anything else in the spec your change makes inaccurate.

## Commits

Small, focused commits, for example:
- `refactor(common): share error logging and JSON rendering (KAN-35)`
- `fix(common): catch exceptions escaping servlet filters as a JSON 500 (KAN-35)`
- `fix(common): render /error in the ApiErrorResponse shape and disable whitelabel (KAN-35)`
- `test(common): ... (KAN-35)`

Docs go in the same commit as the change they describe (rule 7). **Do not push and do not open a PR.**

## Summary to return

1. Files changed or added, one line each.
2. Sample responses (status, `Content-Type`, body) for: filter exception (JSON and `Accept: text/html`), firewall rejection, `sendError(503)`, direct `/error` with and without a token. The same filter exception's response **before** your change, for comparison.
3. Evidence for each item in "Verify against what's installed" (file / jar / class / line, or test output).
4. The log output of test 1, showing exactly one ERROR event and its text.
5. The test list with counts, and the full build result (command, outcome, total count).
6. Deviations from this prompt, and why.
7. The spec update list.
8. Open ends and risks.
9. Print the full final filter, `ApiErrorController`, any shared helper, the diff of `GlobalExceptionHandler`, `EndpointAuthorizationRules`, and the `application.yml` diff, so they can be reviewed directly.
