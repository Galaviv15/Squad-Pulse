package com.squadpulse.common;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

  @ExceptionHandler(MissingClubContextException.class)
  public ResponseEntity<ApiErrorResponse> handleMissingClubContext(MissingClubContextException ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ApiErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    List<String> details =
        ex.getBindingResult().getFieldErrors().stream().map(FieldError::getDefaultMessage).toList();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                "Request validation failed",
                details));
  }

  /** A missing or unparseable JSON body, or a value of the wrong type (e.g. an unknown enum). */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", "Malformed request body"));
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
}
