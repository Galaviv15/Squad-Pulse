package com.squadpulse.auth;

import com.squadpulse.common.TooManyRequestsException;
import java.time.Duration;

/**
 * Too many failed logins for this (email, IP) pair — see {@link LoginThrottleService}. Doesn't
 * reveal whether the email is registered: unknown emails are throttled exactly like real ones.
 */
class LoginThrottledException extends TooManyRequestsException {

  LoginThrottledException(Duration retryAfter) {
    super("Too many failed login attempts, try again later", retryAfter);
  }
}
