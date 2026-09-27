package com.squadpulse.common;

import java.time.Duration;

/**
 * The caller has hit a rate limit — mapped to 429 by {@link GlobalExceptionHandler}, with a {@code
 * Retry-After} header from {@link #getRetryAfter()}. Modules throw their own subclasses (e.g.
 * {@code auth.LoginThrottledException}).
 *
 * <p>The message is sent to the client as-is, so it must stay generic, like {@link
 * UnauthorizedException}'s.
 */
public class TooManyRequestsException extends RuntimeException {

  private final Duration retryAfter;

  /**
   * @param retryAfter how long until the caller may try again
   */
  public TooManyRequestsException(String message, Duration retryAfter) {
    super(message);
    this.retryAfter = retryAfter;
  }

  public Duration getRetryAfter() {
    return retryAfter;
  }
}
