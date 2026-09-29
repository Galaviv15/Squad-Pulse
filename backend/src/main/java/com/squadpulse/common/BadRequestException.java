package com.squadpulse.common;

import java.util.Optional;

/**
 * The request is well-formed and each value is valid on its own, but together they make no sense
 * (e.g. a minimum above the maximum) — mapped to 400 by {@link GlobalExceptionHandler}. Checks a
 * single field or parameter can express belong in Bean Validation instead. Modules throw their own
 * subclasses.
 *
 * <p>With a field, it's reported exactly like a validation error — one {@code "field: problem"}
 * entry in {@code details} — so a client handles both the same way. Without one, it's a plain
 * {@code "Bad Request"} carrying the message.
 */
public class BadRequestException extends RuntimeException {

  private final String field;

  /** Not tied to one field or parameter. */
  public BadRequestException(String message) {
    super(message);
    this.field = null;
  }

  /**
   * @param field the body field or query parameter to blame, as the client named it
   * @param problem what's wrong with it, e.g. {@code "must be less than or equal to maxAge"}
   */
  public BadRequestException(String field, String problem) {
    super(field + ": " + problem);
    this.field = field;
  }

  public Optional<String> getField() {
    return Optional.ofNullable(field);
  }
}
