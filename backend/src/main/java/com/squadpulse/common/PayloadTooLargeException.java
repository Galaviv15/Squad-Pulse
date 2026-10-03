package com.squadpulse.common;

/**
 * The request body, or a file in it, is bigger than allowed — mapped to 413 (Content Too Large) by
 * {@link GlobalExceptionHandler}. The message is sent to the client as-is, so it states the limit
 * and never echoes anything from the request.
 */
public class PayloadTooLargeException extends RuntimeException {

  public PayloadTooLargeException(String message) {
    super(message);
  }
}
