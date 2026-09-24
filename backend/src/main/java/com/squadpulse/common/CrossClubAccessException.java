package com.squadpulse.common;

/** Thrown when code attempts to read or write another club's data through the scoped layer. */
public class CrossClubAccessException extends RuntimeException {

  public CrossClubAccessException(String message) {
    super(message);
  }
}
