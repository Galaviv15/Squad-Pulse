package com.squadpulse.auth;

import com.squadpulse.common.ConflictException;

/**
 * A caller tried to change their own permission level — always refused, so a club's only {@code
 * ADMIN} can't demote themselves and leave nobody able to manage users (see {@link
 * UserPermissionLevelService}).
 */
class CannotChangeOwnPermissionLevelException extends ConflictException {

  CannotChangeOwnPermissionLevelException() {
    super("You can't change your own permission level; another ADMIN must do it");
  }
}
