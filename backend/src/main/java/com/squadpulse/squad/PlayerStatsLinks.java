package com.squadpulse.squad;

/**
 * The links between the club's players and the players in its scraped season statistics (KAN-72),
 * as far as the roster needs to know about them. Implemented in {@code match}, which owns the
 * links: {@code match} depends on {@code squad}, never the other way round, so this interface is
 * the only thing {@code squad} sees of them.
 *
 * <p>Both methods act on the current club ({@code ClubContext}) only; {@code playerId} is a {@link
 * Player} id the caller has already loaded through the club-scoped repository.
 */
public interface PlayerStatsLinks {

  /** Whether this player is linked to a scraped player. */
  boolean isLinked(String playerId);

  /** Removes this player's link, if any. Idempotent. */
  void removeLinkOf(String playerId);
}
