package com.squadpulse.auth;

import com.squadpulse.common.ConflictException;

/**
 * A caller tried to deactivate or re-activate themselves — always refused, so a club's only {@code
 * ADMIN} can't lock themselves out and leave nobody able to manage users (see {@link
 * UserActivationService}).
 */
class CannotChangeOwnActiveStatusException extends ConflictException {

  CannotChangeOwnActiveStatusException() {
    super("You can't deactivate or reactivate yourself; another ADMIN must do it");
  }
}
