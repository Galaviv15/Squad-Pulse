package com.squadpulse.match;

import com.squadpulse.common.ConflictException;

/** The {@code Player} is already linked to another scraped player (KAN-72). */
class PlayerAlreadyLinkedException extends ConflictException {

  static final String CODE = "PLAYER_ALREADY_LINKED";

  PlayerAlreadyLinkedException() {
    super(CODE, "This player is already linked to another scraped player");
  }
}
