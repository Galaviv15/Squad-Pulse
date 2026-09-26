package com.squadpulse.common;

/**
 * The caller couldn't be authenticated (bad credentials, an invalid or expired token, ...) — mapped
 * to 401 by {@link GlobalExceptionHandler}. Modules throw their own subclasses (e.g. {@code
 * auth.InvalidCredentialsException}).
 *
 * <p>The message is sent to the client as-is, so it must stay generic: never say which part of a
 * credential was wrong (e.g. whether an email exists), and never include a secret or token.
 */
public class UnauthorizedException extends RuntimeException {

  public UnauthorizedException(String message) {
    super(message);
  }
}
