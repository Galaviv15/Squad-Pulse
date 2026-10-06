package com.squadpulse.squad;

import com.squadpulse.common.ConflictException;

/** A re-activation of a player who is already active. */
class PlayerAlreadyActiveException extends ConflictException {

  static final String CODE = "PLAYER_ALREADY_ACTIVE";

  PlayerAlreadyActiveException() {
    super(CODE, "This player is already active");
  }
}
