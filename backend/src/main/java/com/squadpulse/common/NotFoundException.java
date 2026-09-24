package com.squadpulse.common;

/** Thrown by service code when a requested resource doesn't exist (or isn't in this club). */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
