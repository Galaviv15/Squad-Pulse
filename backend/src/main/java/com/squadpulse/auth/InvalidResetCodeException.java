package com.squadpulse.auth;

import com.squadpulse.common.UnauthorizedException;

/**
 * A password reset / activation was refused. Deliberately one message for every cause — no code for
 * that email (never requested, expired, used, or burned by too many wrong guesses), a wrong code,
 * or a code whose user no longer exists or has been deactivated — so the response never reveals
 * which it was.
 */
class InvalidResetCodeException extends UnauthorizedException {

  InvalidResetCodeException() {
    super("Invalid or expired code");
  }
}
