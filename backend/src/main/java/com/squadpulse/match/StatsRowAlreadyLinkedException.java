package com.squadpulse.match;

import com.squadpulse.common.ConflictException;

/**
 * The scraped player is already linked to an existing {@code Player}, so no player is created for
 * it (KAN-77, {@link StatsPlayerCreationService}). Unlike {@code PUT
 * /matches/player-stats/{id}/link}, which replaces a link, creating a player from a row never takes
 * a link away from someone.
 */
class StatsRowAlreadyLinkedException extends ConflictException {

  static final String CODE = "STATS_ROW_ALREADY_LINKED";

  StatsRowAlreadyLinkedException() {
    super(CODE, "This scraped player is already linked to a player");
  }
}
