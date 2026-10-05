package com.squadpulse.common;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Renders whatever still reaches the servlet container's error page, {@code /error}, in the {@link
 * ApiErrorResponse} shape (KAN-35), replacing Spring Boot's {@code BasicErrorController} (and its
 * Whitelabel HTML page, also switched off in application.yml). That's a {@code
 * response.sendError(...)} from below Spring MVC — most commonly a 400 from Spring Security's
 * firewall for a path it rejects, such as one containing {@code //} — and, as a fallback, an
 * exception {@link UnhandledExceptionFilter} didn't catch.
 *
 * <ul>
 *   <li>Status: the error dispatch's {@link RequestDispatcher#ERROR_STATUS_CODE}; a missing value,
 *       or one that isn't a 4xx/5xx, is answered as 500.
 *   <li>Body: always JSON, whatever the {@code Accept} header says (built by {@link
 *       GlobalExceptionHandler#respond}), with a fixed message: never the container's error
 *       message, an exception's message or the request path, any of which can quote the request.
 *   <li>Logging: a 4xx at DEBUG only. A 5xx at ERROR with the method and original path — with the
 *       stack trace if the dispatch carries an exception. With {@link UnhandledExceptionFilter} in
 *       place that shouldn't happen; if it does, Tomcat will have logged the exception too, which
 *       is accepted rather than silencing Tomcat's own logger.
 *   <li>A direct request for {@code /error} — not an error dispatch — is answered like any unknown
 *       path, a 404 (without an access token, the security chain's 401 comes first).
 * </ul>
 *
 * <p>Deliberately without {@code @PreAuthorize} or {@code @PublicEndpoint}: the error dispatch has
 * no authenticated caller ({@code auth.JwtAuthenticationFilter} skips it, and {@code
 * auth.SecurityConfig} permits it), and it only renders an error that already happened, exposing
 * nothing. The one class the endpoint-authorization ArchUnit rule exempts.
 */
@RestController
class ApiErrorController implements ErrorController {

  private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

  @RequestMapping("${spring.web.error.path:/error}")
  ResponseEntity<ApiErrorResponse> error(HttpServletRequest request)
      throws NoHandlerFoundException {
    if (request.getDispatcherType() != DispatcherType.ERROR) {
      // Exactly what the DispatcherServlet throws for an unmapped path.
      throw new NoHandlerFoundException(
          request.getMethod(),
          request.getRequestURI(),
          new ServletServerHttpRequest(request).getHeaders());
    }
    HttpStatusCode status = status(request);
    log(status, request);
    return GlobalExceptionHandler.withReasonPhrase(
        status,
        HttpHeaders.EMPTY,
        status.is5xxServerError()
            ? GlobalExceptionHandler.UNEXPECTED_ERROR_MESSAGE
            : GlobalExceptionHandler.CLIENT_ERROR_MESSAGE,
        List.of());
  }

  private static HttpStatusCode status(HttpServletRequest request) {
    if (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
        && code >= 400
        && code <= 599) {
      return HttpStatusCode.valueOf(code);
    }
    return HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private static void log(HttpStatusCode status, HttpServletRequest request) {
    String method = request.getMethod();
    String path = String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
    Throwable exception =
        request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable t ? t : null;
    if (exception != null && ErrorLogging.isClientDisconnected(exception)) {
      ErrorLogging.logClientDisconnected(log, exception, method, path);
    } else if (!status.is5xxServerError()) {
      log.debug("Answered {} {} with {}", method, path, status.value());
    } else if (exception != null) {
      ErrorLogging.logServerError(log, exception, method, path);
    } else {
      log.error("Answered {} {} with {}", method, path, status.value());
    }
  }
}
