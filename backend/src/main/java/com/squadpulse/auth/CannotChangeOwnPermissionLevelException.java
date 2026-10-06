package com.squadpulse.auth;

import com.squadpulse.common.ConflictException;

/**
 * A caller tried to change their own permission level — always refused, so a club's only {@code
 * ADMIN} can't demote themselves and leave nobody able to manage users (see {@link
 * UserPermissionLevelService}).
 */
class CannotChangeOwnPermissionLevelException extends ConflictException {

  static final String CODE = "CANNOT_CHANGE_OWN_PERMISSION_LEVEL";

  CannotChangeOwnPermissionLevelException() {
    super(CODE, "You can't change your own permission level; another ADMIN must do it");
  }
}
