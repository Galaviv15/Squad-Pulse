package com.squadpulse.squad;

import com.squadpulse.common.ConflictException;

/**
 * A permanent delete of a player who is linked to a scraped player's statistics (KAN-72). Remove
 * the link first ({@code DELETE /squad/players/{id}/stats-link}).
 */
class PlayerStatsLinkedException extends ConflictException {

  static final String CODE = "PLAYER_STATS_LINKED";

  PlayerStatsLinkedException() {
    super(CODE, "This player is linked to scraped statistics; remove the link before deleting");
  }
}
