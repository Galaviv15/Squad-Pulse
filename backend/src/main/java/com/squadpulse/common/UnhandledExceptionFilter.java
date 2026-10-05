package com.squadpulse.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The last-resort net for an exception that escapes a servlet filter (KAN-35) — e.g. an unexpected
 * failure in {@code auth.JwtAuthenticationFilter}, or anything Spring Security's {@code
 * ExceptionTranslationFilter} rethrows because it isn't a security exception. {@link
 * GlobalExceptionHandler} only sees exceptions raised inside Spring MVC; without this, such an
 * exception would reach Tomcat, which logs it without the request's method and path, and Spring
 * Boot's {@code /error} would render it in a different shape.
 *
 * <p>Registered first, wrapping Spring Security's filter chain and everything after it (see {@link
 * UnhandledExceptionFilterConfig}). What it catches, it handles like {@link
 * GlobalExceptionHandler#handleUnexpected} handles a non-{@code ErrorResponse} exception:
 *
 * <ul>
 *   <li>A client that went away: logged at DEBUG only, nothing written.
 *   <li>Otherwise logged once at ERROR, with the stack trace and the request's method and path
 *       ({@link ErrorLogging}). If the response is already committed, nothing more can be done;
 *       else its buffer is reset and it's answered with the generic JSON 500, whatever the {@code
 *       Accept} header says.
 * </ul>
 *
 * <p>Since the exception never leaves this filter, Tomcat neither logs it a second time nor
 * dispatches to {@code /error}. An {@link Error} (e.g. {@code OutOfMemoryError}) isn't caught.
 *
 * <p>Only the buffer is reset, not the headers: those a filter already set (e.g. Spring Security's
 * {@code Cache-Control} and {@code X-Content-Type-Options}) are kept, and {@code Content-Type} is
 * overwritten.
 *
 * <p>A new filter must not write its own error format: it either answers through the security
 * chain's handlers or lets the exception propagate to here.
 */
final class UnhandledExceptionFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(UnhandledExceptionFilter.class);

  private final JsonMapper jsonMapper;

  UnhandledExceptionFilter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
    try {
      chain.doFilter(request, response);
    } catch (Exception ex) {
      handle(ex, request, response);
    }
  }

  private void handle(Exception ex, HttpServletRequest request, HttpServletResponse response) {
    if (ErrorLogging.isClientDisconnected(ex)) {
      ErrorLogging.logClientDisconnected(log, ex, request.getMethod(), request.getRequestURI());
      return;
    }
    ErrorLogging.logServerError(log, ex, request);
    if (response.isCommitted()) {
      return;
    }
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    try {
      response.resetBuffer();
      response.setStatus(status.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      jsonMapper.writeValue(
          response.getOutputStream(),
          ApiErrorResponse.of(
              status.value(),
              status.getReasonPhrase(),
              GlobalExceptionHandler.UNEXPECTED_ERROR_MESSAGE));
    } catch (IOException | RuntimeException writeFailure) {
      // The failure itself is already logged above; there's nothing left to tell the client.
      log.debug(
          "Could not write the error response for {} {}",
          request.getMethod(),
          request.getRequestURI(),
          writeFailure);
    }
  }
}
