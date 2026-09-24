package com.squadpulse.common;

import java.time.Instant;
import java.util.List;

/** Consistent JSON error shape returned by {@link GlobalExceptionHandler}. */
public record ApiErrorResponse(
    Instant timestamp, int status, String error, String message, List<String> details) {

  public static ApiErrorResponse of(int status, String error, String message) {
    return new ApiErrorResponse(Instant.now(), status, error, message, List.of());
  }

  public static ApiErrorResponse of(
      int status, String error, String message, List<String> details) {
    return new ApiErrorResponse(Instant.now(), status, error, message, details);
  }
}
