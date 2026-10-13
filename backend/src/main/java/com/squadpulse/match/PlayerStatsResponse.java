package com.squadpulse.match;

import com.squadpulse.squad.Player;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /matches/player-stats} (KAN-72): the club's scraped player season statistics for its
 * current mapping, all competitions together. Never carries a football.org.il id of any kind — no
 * {@code player_id}, team, league or season (docs/spec.md section 09): a row's {@code id} is our
 * own opaque one.
 *
 * @param lastUpdatedAt when the shown snapshot was scraped; {@code null} without one
 * @param players in the site's order (as the scraper sent them)
 */
public record PlayerStatsResponse(MatchDataState state, Instant lastUpdatedAt, List<Row> players) {

  /**
   * One scraped player.
   *
   * @param id our opaque id: what {@code PUT/DELETE /matches/player-stats/{id}/link} take
   * @param name as the site shows it (surname first)
   * @param yellowCardsLeagueAndCup league and State Cup
   * @param yellowCardsToto Toto Cup
   * @param linkedPlayer the club's player it's linked to, or {@code null}
   * @param suggestedPlayer a likely match by name, only while not linked; {@code null} if none or
   *     ambiguous
   */
  public record Row(
      String id,
      String name,
      int games,
      int goals,
      int yellowCardsLeagueAndCup,
      int yellowCardsToto,
      int redCards,
      int starts,
      int cameOnAsSub,
      int subbedOff,
      int minutes,
      PlayerRef linkedPlayer,
      PlayerRef suggestedPlayer) {}

  /** One of the club's own players. */
  public record PlayerRef(String id, String fullName, boolean active) {

    static PlayerRef of(Player player) {
      return new PlayerRef(player.getId(), player.getFullName(), player.isActive());
    }
  }
}
