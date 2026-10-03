package com.squadpulse.squad;

import com.squadpulse.common.ConflictException;

/**
 * A release of a player who has already been released. A 409 rather than an idempotent success: the
 * first release already moved the player's version, so the second request is based on an
 * out-of-date copy anyway.
 */
class PlayerAlreadyReleasedException extends ConflictException {

  PlayerAlreadyReleasedException() {
    super("This player has already been released");
  }
}
