package com.squadpulse.squad;

import com.squadpulse.common.ConflictException;

/** A re-activation of a player who is already active. */
class PlayerAlreadyActiveException extends ConflictException {

  PlayerAlreadyActiveException() {
    super("This player is already active");
  }
}
