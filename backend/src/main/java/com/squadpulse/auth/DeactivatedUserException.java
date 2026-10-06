package com.squadpulse.auth;

import com.squadpulse.common.ConflictException;

/**
 * A change to a deactivated user's ({@code active == false}) profile, e.g. their photo — the same
 * rule as for a released player: readable, but not editable.
 */
class DeactivatedUserException extends ConflictException {

  static final String CODE = "USER_DEACTIVATED";

  DeactivatedUserException() {
    super(CODE, "This user has been deactivated; their profile can't be changed");
  }
}
