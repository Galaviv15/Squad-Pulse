package com.squadpulse.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * The log lines for a failed request, shared by every place that turns a failure into an error
 * response — {@link GlobalExceptionHandler} (inside Spring MVC), {@link UnhandledExceptionFilter}
 * (exceptions escaping a servlet filter) and {@link ApiErrorController} (the container's {@code
 * /error} dispatch) — so their format and their client-disconnect check can't drift apart. Each
 * caller passes its own logger, so a log line still says which of them wrote it.
 *
 * <p>Only the request's method and path are ever logged: never its query string, headers, body or
 * cookies.
 */
final class ErrorLogging {

  private ErrorLogging() {}

  /**
   * A client that went away mid-response (Tomcat's {@code ClientAbortException}, a broken pipe), as
   * Spring's {@link DisconnectedClientHelper} recognises it: nothing to answer, nothing to log
   * above DEBUG.
   */
  static boolean isClientDisconnected(Throwable ex) {
    return DisconnectedClientHelper.isClientDisconnectedException(ex);
  }

  static void logClientDisconnected(Logger log, Throwable ex, String method, String path) {
    log.debug("Client disconnected during {} {}: {}", method, path, ex);
  }

  /** At ERROR, with the stack trace. */
  static void logServerError(Logger log, Throwable ex, String method, String path) {
    log.error("Unexpected error handling {} {}", method, path, ex);
  }

  static void logServerError(Logger log, Throwable ex, HttpServletRequest request) {
    logServerError(log, ex, request.getMethod(), request.getRequestURI());
  }
}
