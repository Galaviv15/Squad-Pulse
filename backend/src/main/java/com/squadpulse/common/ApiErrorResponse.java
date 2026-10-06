package com.squadpulse.common;

import java.time.Instant;
import java.util.List;

/**
 * Consistent JSON error shape returned by {@link GlobalExceptionHandler}. {@code code} is a
 * machine-readable reason (today only on a 409, see {@link ConflictException}); it's always
 * written, as {@code null} when the error has none.
 */
public record ApiErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String code,
    String message,
    List<String> details) {

  public static ApiErrorResponse of(int status, String error, String message) {
    return of(status, error, null, message, List.of());
  }

  public static ApiErrorResponse of(
      int status, String error, String message, List<String> details) {
    return of(status, error, null, message, details);
  }

  public static ApiErrorResponse of(int status, String error, String code, String message) {
    return of(status, error, code, message, List.of());
  }

  public static ApiErrorResponse of(
      int status, String error, String code, String message, List<String> details) {
    return new ApiErrorResponse(Instant.now(), status, error, code, message, details);
  }
}
