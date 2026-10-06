package com.squadpulse.common;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The request clashes with existing state (e.g. a value that must be unique is already taken) —
 * mapped to 409 by {@link GlobalExceptionHandler}. Modules throw their own subclasses.
 *
 * <p>Every subclass declares a {@code code}: a machine-readable reason sent as the error body's
 * {@code code}, so a client can tell one 409 from another without reading the English {@code
 * message}. Each subclass keeps its code as a {@code static final String CODE} in its own class, so
 * codes stay owned by their module. Codes are part of the API and stable: a new one may be added,
 * but once shipped a code is never renamed or reused for another meaning.
 */
public class ConflictException extends RuntimeException {

  private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]*$");

  private final String code;

  /**
   * @throws IllegalArgumentException if {@code code} isn't UPPER_SNAKE_CASE, so a typo fails at
   *     once
   */
  public ConflictException(String code, String message) {
    super(message);
    if (!CODE_FORMAT.matcher(Objects.requireNonNull(code, "code")).matches()) {
      throw new IllegalArgumentException("Malformed conflict code: " + code);
    }
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
