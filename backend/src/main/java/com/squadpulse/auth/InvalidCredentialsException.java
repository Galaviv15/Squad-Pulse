package com.squadpulse.auth;

import com.squadpulse.common.UnauthorizedException;

/**
 * Login failed. Deliberately one message for every cause — unknown email, wrong password, no
 * password set yet, or a deactivated account — so the response never reveals whether an email is
 * registered.
 */
class InvalidCredentialsException extends UnauthorizedException {

  InvalidCredentialsException() {
    super("Invalid email or password");
  }
}
