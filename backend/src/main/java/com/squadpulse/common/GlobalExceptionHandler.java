package com.squadpulse.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.util.DisconnectedClientHelper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

/**
 * Turns exceptions raised anywhere in the request-handling path into a consistent JSON error shape,
 * so individual controllers don't need their own error handling.
 *
 * <p>{@link CrossClubAccessException} is handled here specifically because it's the most
 * safety-critical failure mode in this codebase — see docs/spec.md section 03 and CLAUDE.md
 * standing rule 4.
 *
 * <p>Messages never echo client input (a rejected value, a {@code Content-Type}): Spring's own
 * messages often quote it, so they're replaced with fixed texts. Every 500 is logged at ERROR with
 * its stack trace and the request's method and path — never its query string, headers or body; a
 * client error (4xx) never is.
 *
 * <p>Deliberately not a {@code ResponseEntityExceptionHandler} subclass: that renders RFC 9457
 * {@code ProblemDetail} bodies, and declares handlers for exceptions mapped here, which Spring
 * refuses at startup as ambiguous. The framework exceptions it would cover that aren't mapped
 * explicitly are caught by {@link #handleUnexpected}, which answers an {@link ErrorResponse} with
 * its own status.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  static final String CONCURRENT_MODIFICATION_MESSAGE =
      "The resource was modified concurrently, please retry";

  static final String UNEXPECTED_ERROR_MESSAGE = "An unexpected error occurred";

  static final String CLIENT_ERROR_MESSAGE = "The request could not be processed";

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex) {
    return respond(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }

  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<ApiErrorResponse> handleUnauthorized(UnauthorizedException ex) {
    return respond(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage());
  }

  /**
   * An authenticated caller lacking the required authority — e.g. a {@code @PreAuthorize} check on
   * a controller method. Handled here explicitly, or the catch-all below would turn it into a 500.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    return respond(HttpStatus.FORBIDDEN, "Forbidden", "Access denied");
  }

  @ExceptionHandler(CrossClubAccessException.class)
  public ResponseEntity<ApiErrorResponse> handleCrossClubAccess(CrossClubAccessException ex) {
    return respond(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex) {
    return respond(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
  }

  /**
   * A write lost an optimistic-locking race (a {@code @Version} mismatch, e.g. on {@code
   * auth.User}, KAN-24) that its writer didn't resolve by retrying — the client may simply retry.
   * The body is generic: the exception's own message names the entity id and collection, so it's
   * only logged, at WARN and without a stack trace, since it's an expected outcome, not a bug.
   *
   * <p>Only this exact branch of Spring's {@code DataAccessException} hierarchy — not its parent
   * {@code ConcurrencyFailureException}, and not {@code DuplicateKeyException}, which is a {@code
   * DataIntegrityViolationException}.
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ApiErrorResponse> handleOptimisticLockingFailure(
      OptimisticLockingFailureException ex) {
    log.warn("Concurrent modification not resolved by retry, answering 409: {}", ex.getMessage());
    return respond(HttpStatus.CONFLICT, "Conflict", CONCURRENT_MODIFICATION_MESSAGE);
  }

  /** {@code Retry-After} in whole seconds, rounded up so a client never retries too early. */
  @ExceptionHandler(TooManyRequestsException.class)
  public ResponseEntity<ApiErrorResponse> handleTooManyRequests(TooManyRequestsException ex) {
    Duration retryAfter = ex.getRetryAfter();
    long retryAfterSeconds =
        Math.max(1, retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0));
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
    return respond(
        HttpStatus.TOO_MANY_REQUESTS, headers, "Too Many Requests", ex.getMessage(), List.of());
  }

  /** Always a server bug: a code path that reads tenant data ran without a club context. */
  @ExceptionHandler(MissingClubContextException.class)
  public ResponseEntity<ApiErrorResponse> handleMissingClubContext(
      MissingClubContextException ex, HttpServletRequest request) {
    logServerError(ex, request);
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", ex.getMessage());
  }

  /**
   * With a field, reported like any validation error (its message already reads {@code "field:
   * problem"}); without one, a plain 400 carrying the message.
   */
  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<ApiErrorResponse> handleBadRequest(BadRequestException ex) {
    if (ex.getField().isPresent()) {
      return validationFailed(List.of(ex.getMessage()));
    }
    return respond(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
  }

  /**
   * An invalid {@code @Valid @RequestBody}: one {@code "field: message"} detail per field error.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    List<String> details =
        ex.getBindingResult().getFieldErrors().stream()
            .map(error -> detail(error.getField(), error))
            .toList();
    return validationFailed(details);
  }

  /**
   * A constraint on a handler parameter itself (e.g. {@code @Min} on a {@code @RequestParam})
   * failed. Since Spring 6.1 the framework validates these on its own, for any controller
   * <b>not</b> annotated with {@code @Validated}, and throws this rather than a {@code
   * ConstraintViolationException}. One {@code "param: message"} detail per violation.
   *
   * <p>A failed <i>return-value</i> constraint is the server's fault, not the client's, so it stays
   * a 500.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest request) {
    if (ex.isForReturnValue()) {
      logServerError(ex, request);
      return serverError();
    }
    List<String> details = new ArrayList<>();
    for (ParameterValidationResult result : ex.getParameterValidationResults()) {
      String name = requestName(result.getMethodParameter());
      result.getResolvableErrors().forEach(error -> details.add(detail(name, error)));
    }
    ex.getCrossParameterValidationResults()
        .forEach(error -> details.add(error.getDefaultMessage()));
    return validationFailed(details);
  }

  /**
   * A path or query parameter that can't be converted to its declared type — e.g. an unknown enum
   * constant or a non-numeric age. The rejected value isn't echoed back; for an enum, the detail
   * lists the accepted values (each constant's {@code toString()}).
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiErrorResponse> handleArgumentTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    Class<?> requiredType = ex.getRequiredType();
    String problem =
        requiredType != null && requiredType.isEnum()
            ? "must be one of " + Arrays.toString(requiredType.getEnumConstants())
            : "has an invalid format";
    return validationFailed(List.of(ex.getName() + ": " + problem));
  }

  /**
   * A missing or unparseable JSON body, or a value that can't be bound (an unknown enum constant, a
   * date in the wrong format). When Jackson knows which property the value was for — a {@link
   * DatabindException} with a path — {@code details} names it ({@code "dateOfBirth: invalid
   * value"}); broken JSON has no path, so {@code details} stays empty. The rejected value is never
   * echoed back.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
    List<String> details =
        ex.getCause() instanceof DatabindException mappingException
            ? propertyPath(mappingException)
                .map(path -> List.of(path + ": invalid value"))
                .orElse(List.of())
            : List.of();
    return respond(
        HttpStatus.BAD_REQUEST,
        HttpHeaders.EMPTY,
        "Bad Request",
        "Malformed request body",
        details);
  }

  /**
   * A file bigger than the application's own limit for it (e.g. {@link ImageValidator}). 413 under
   * its RFC 9110 name, "Content Too Large" ({@code PAYLOAD_TOO_LARGE} is deprecated in Spring 7).
   */
  @ExceptionHandler(PayloadTooLargeException.class)
  public ResponseEntity<ApiErrorResponse> handlePayloadTooLarge(PayloadTooLargeException ex) {
    return payloadTooLarge(ex.getMessage());
  }

  /**
   * The servlet container refused a multipart body over {@code
   * spring.servlet.multipart.max-file-size} or {@code max-request-size} — the outer guard; the
   * limits the API promises are checked in code ({@link PayloadTooLargeException}). Raised while
   * the multipart body is parsed, before any handler runs. The container's own message, which
   * quotes the sizes, isn't passed on.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceeded(
      MaxUploadSizeExceededException ex) {
    return payloadTooLarge("Upload exceeds the maximum allowed size");
  }

  /**
   * A multipart request without the part a handler requires — reported like a missing field: {@code
   * "file: is required"}.
   */
  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ApiErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
    return validationFailed(List.of(ex.getRequestPartName() + ": is required"));
  }

  /**
   * Any other multipart failure: a body that can't be parsed as multipart, or a request that isn't
   * multipart at all to a handler expecting a file. {@link MaxUploadSizeExceededException} is a
   * subclass but has its own handler above, which Spring prefers as the closer match.
   */
  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ApiErrorResponse> handleMultipart(MultipartException ex) {
    return respond(HttpStatus.BAD_REQUEST, "Bad Request", "Malformed multipart request");
  }

  /**
   * No controller mapped to the path. Only reachable for authenticated requests — without a token,
   * the security chain answers 401 before routing runs — and only because static-resource mappings
   * are off in application.yml; otherwise the {@code /**} resource handler would claim the path.
   */
  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNoHandlerFound(NoHandlerFoundException ex) {
    return respond(
        HttpStatus.NOT_FOUND,
        "Not Found",
        "No endpoint " + ex.getHttpMethod() + " " + ex.getRequestURL());
  }

  /**
   * The path exists but not for this method. Carries the {@code Allow} header RFC 9110 requires.
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex) {
    String[] supported = ex.getSupportedMethods();
    List<String> details = supported == null ? List.of() : List.of(supported);
    return respond(
        HttpStatus.METHOD_NOT_ALLOWED,
        ex.getHeaders(),
        "Method Not Allowed",
        "Method " + ex.getMethod() + " is not supported for this endpoint",
        details);
  }

  /**
   * The request's {@code Content-Type} isn't one the endpoint reads — or can't be parsed at all.
   * {@code details} and the {@code Accept} header (plus {@code Accept-Patch} for a PATCH) list the
   * types it does read; the type the client sent is never echoed.
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex) {
    return withReasonPhrase(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        ex.getHeaders(),
        "Unsupported Content-Type",
        mediaTypes(ex.getSupportedMediaTypes()));
  }

  /**
   * The endpoint can't produce anything the request's {@code Accept} header allows, or the header
   * can't be parsed. {@code details} lists what it can produce. The body is JSON regardless of
   * {@code Accept} (see {@link #respond}).
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ApiErrorResponse> handleMediaTypeNotAcceptable(
      HttpMediaTypeNotAcceptableException ex) {
    return withReasonPhrase(
        HttpStatus.NOT_ACCEPTABLE,
        ex.getHeaders(),
        "None of the accepted media types can be produced",
        mediaTypes(ex.getSupportedMediaTypes()));
  }

  /** A required query parameter is absent — reported like a missing field. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiErrorResponse> handleMissingParameter(
      MissingServletRequestParameterException ex) {
    return validationFailed(List.of(ex.getParameterName() + ": is required"));
  }

  /** A required {@code @RequestHeader} is absent — reported like a missing field. */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
    return validationFailed(List.of(ex.getHeaderName() + ": is required"));
  }

  /** A required {@code @CookieValue} is absent — reported like a missing field. */
  @ExceptionHandler(MissingRequestCookieException.class)
  public ResponseEntity<ApiErrorResponse> handleMissingCookie(MissingRequestCookieException ex) {
    return validationFailed(List.of(ex.getCookieName() + ": is required"));
  }

  /**
   * Everything not mapped above.
   *
   * <ul>
   *   <li>A client that went away mid-response (Tomcat's {@code ClientAbortException}, a broken
   *       pipe — as Spring's {@link DisconnectedClientHelper} recognises them): logged at DEBUG
   *       only, and nothing is written, as there's no one to read it.
   *   <li>A response already committed (e.g. a photo whose stream failed halfway): logged, but its
   *       status can't change, and a JSON body would be appended to what was sent.
   *   <li>A Spring framework exception that knows its status — an {@link ErrorResponse}, e.g. a
   *       {@code ResponseStatusException} or an unmapped {@code ServletRequestBindingException} —
   *       answered with that status and its headers, under a fixed message, since its own detail
   *       can quote the request. Logged at ERROR only if it's a 5xx.
   *   <li>Anything else: a bug, logged at ERROR, answered with a generic 500.
   * </ul>
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(
      Exception ex, HttpServletRequest request, HttpServletResponse response) {
    if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
      log.debug(
          "Client disconnected during {} {}: {}", request.getMethod(), request.getRequestURI(), ex);
      return null;
    }
    HttpStatusCode status =
        ex instanceof ErrorResponse errorResponse
            ? errorResponse.getStatusCode()
            : HttpStatus.INTERNAL_SERVER_ERROR;
    if (status.is5xxServerError()) {
      logServerError(ex, request);
    } else {
      log.debug(
          "Answered {} {} with {}: {}",
          request.getMethod(),
          request.getRequestURI(),
          status.value(),
          ex.getClass().getName());
    }
    if (response.isCommitted()) {
      return null;
    }
    if (!(ex instanceof ErrorResponse errorResponse)) {
      return serverError();
    }
    return withReasonPhrase(
        status,
        errorResponse.getHeaders(),
        status.is5xxServerError() ? UNEXPECTED_ERROR_MESSAGE : CLIENT_ERROR_MESSAGE,
        List.of());
  }

  private static void logServerError(Exception ex, HttpServletRequest request) {
    log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
  }

  private static ResponseEntity<ApiErrorResponse> serverError() {
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", UNEXPECTED_ERROR_MESSAGE);
  }

  /**
   * Every response this class returns is built here. Its {@code Content-Type} is set explicitly, so
   * Spring writes the JSON without negotiating it against the request's {@code Accept} header.
   * Otherwise, for a client accepting only, say, XML, that negotiation would fail while the error
   * was being written: a framework error then lost its body, and any other exception went
   * unresolved — a 500 from the servlet container, whatever its real status.
   */
  private static ResponseEntity<ApiErrorResponse> respond(
      HttpStatusCode status,
      HttpHeaders headers,
      String error,
      String message,
      List<String> details) {
    return ResponseEntity.status(status)
        .headers(headers)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiErrorResponse.of(status.value(), error, message, details));
  }

  private static ResponseEntity<ApiErrorResponse> respond(
      HttpStatusCode status, String error, String message) {
    return respond(status, HttpHeaders.EMPTY, error, message, List.of());
  }

  /** {@link #respond} under {@code status}'s standard reason phrase. */
  private static ResponseEntity<ApiErrorResponse> withReasonPhrase(
      HttpStatusCode status, HttpHeaders headers, String message, List<String> details) {
    return respond(status, headers, reasonPhrase(status), message, details);
  }

  private static String reasonPhrase(HttpStatusCode status) {
    HttpStatus known = HttpStatus.resolve(status.value());
    if (known != null) {
      return known.getReasonPhrase();
    }
    return status.is5xxServerError() ? "Server Error" : "Client Error";
  }

  private static List<String> mediaTypes(List<MediaType> mediaTypes) {
    return mediaTypes.stream().map(MediaType::toString).toList();
  }

  private static ResponseEntity<ApiErrorResponse> payloadTooLarge(String message) {
    return respond(HttpStatus.CONTENT_TOO_LARGE, "Content Too Large", message);
  }

  private static ResponseEntity<ApiErrorResponse> validationFailed(List<String> details) {
    return respond(
        HttpStatus.BAD_REQUEST,
        HttpHeaders.EMPTY,
        "Validation Failed",
        "Request validation failed",
        details);
  }

  private static String detail(String name, MessageSourceResolvable error) {
    return name + ": " + error.getDefaultMessage();
  }

  /**
   * The JSON path of the property Jackson failed on, from its structured references rather than
   * {@code getPathReference()}, which spells out Java class names: {@code "players[2].position"}.
   */
  private static Optional<String> propertyPath(JacksonException ex) {
    StringBuilder path = new StringBuilder();
    for (JacksonException.Reference reference : ex.getPath()) {
      if (reference.getPropertyName() != null) {
        if (!path.isEmpty()) {
          path.append('.');
        }
        path.append(reference.getPropertyName());
      } else if (reference.getIndex() >= 0) {
        path.append('[').append(reference.getIndex()).append(']');
      }
    }
    return path.isEmpty() ? Optional.empty() : Optional.of(path.toString());
  }

  /** The name the client used: the {@code @RequestParam} name if given, else the Java name. */
  private static String requestName(MethodParameter parameter) {
    // Read raw, so the name/value alias isn't resolved for us.
    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
    if (requestParam != null) {
      String name = requestParam.name().isEmpty() ? requestParam.value() : requestParam.name();
      if (!name.isEmpty()) {
        return name;
      }
    }
    return parameter.getParameterName();
  }
}
