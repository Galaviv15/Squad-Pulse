package com.squadpulse.common;

/**
 * The request clashes with existing state (e.g. a value that must be unique is already taken) —
 * mapped to 409 by {@link GlobalExceptionHandler}. Modules throw their own subclasses.
 */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
