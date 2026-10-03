package com.squadpulse.common;

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
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

/**
 * Turns exceptions raised anywhere in the request-handling path into a consistent JSON error shape,
 * so individual controllers don't need their own error handling.
 *
 * <p>{@link CrossClubAccessException} is handled here specifically because it's the most
 * safety-critical failure mode in this codebase — see docs/spec.md section 03 and CLAUDE.md
 * standing rule 4.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  static final String CONCURRENT_MODIFICATION_MESSAGE =
      "The resource was modified concurrently, please retry";

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage()));
  }

  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<ApiErrorResponse> handleUnauthorized(UnauthorizedException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            ApiErrorResponse.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized", ex.getMessage()));
  }

  /**
   * An authenticated caller lacking the required authority — e.g. a {@code @PreAuthorize} check on
   * a controller method. Handled here explicitly, or the catch-all below would turn it into a 500.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiErrorResponse.of(HttpStatus.FORBIDDEN.value(), "Forbidden", "Access denied"));
  }

  @ExceptionHandler(CrossClubAccessException.class)
  public ResponseEntity<ApiErrorResponse> handleCrossClubAccess(CrossClubAccessException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiErrorResponse.of(HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage()));
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage()));
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
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(), "Conflict", CONCURRENT_MODIFICATION_MESSAGE));
  }

  /** {@code Retry-After} in whole seconds, rounded up so a client never retries too early. */
  @ExceptionHandler(TooManyRequestsException.class)
  public ResponseEntity<ApiErrorResponse> handleTooManyRequests(TooManyRequestsException ex) {
    Duration retryAfter = ex.getRetryAfter();
    long retryAfterSeconds =
        Math.max(1, retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0));
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
        .body(
            ApiErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", ex.getMessage()));
  }

  @ExceptionHandler(MissingClubContextException.class)
  public ResponseEntity<ApiErrorResponse> handleMissingClubContext(MissingClubContextException ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ApiErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                ex.getMessage()));
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
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage()));
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
      HandlerMethodValidationException ex) {
    if (ex.isForReturnValue()) {
      return handleUnexpected(ex);
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
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", "Malformed request body", details));
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
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", "Malformed multipart request"));
  }

  /**
   * No controller mapped to the path. Only reachable for authenticated requests — without a token,
   * the security chain answers 401 before routing runs — and only because static-resource mappings
   * are off in application.yml; otherwise the {@code /**} resource handler would claim the path.
   */
  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNoHandlerFound(NoHandlerFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            ApiErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                "No endpoint " + ex.getHttpMethod() + " " + ex.getRequestURL()));
  }

  /**
   * The path exists but not for this method. Carries the {@code Allow} header RFC 9110 requires.
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex) {
    String[] supported = ex.getSupportedMethods();
    List<String> details = supported == null ? List.of() : List.of(supported);
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .headers(ex.getHeaders())
        .body(
            ApiErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "Method Not Allowed",
                "Method " + ex.getMethod() + " is not supported for this endpoint",
                details));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ApiErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected error occurred"));
  }

  private static ResponseEntity<ApiErrorResponse> payloadTooLarge(String message) {
    return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
        .body(
            ApiErrorResponse.of(
                HttpStatus.CONTENT_TOO_LARGE.value(), "Content Too Large", message));
  }

  private static ResponseEntity<ApiErrorResponse> validationFailed(List<String> details) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                "Request validation failed",
                details));
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
