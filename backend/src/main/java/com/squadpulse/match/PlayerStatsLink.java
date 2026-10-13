package com.squadpulse.match;

import com.squadpulse.common.ClubScopedEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A club's link between a scraped player (football.org.il's {@code player_id}) and one of its own
 * {@code squad.Player}s (KAN-72), set by a user, never by the scraper. A normal {@link
 * ClubScopedEntity}: links are per club, so two clubs mapped to the same team link independently.
 *
 * <p>At most one link per scraped player and per {@code Player} in a club, guaranteed by the two
 * unique indexes, not by a check. Keyed by the stable {@code player_id}, not by season and not by
 * the snapshot row's opaque id: a player who drops out of a scrape keeps their link, which shows
 * again when they reappear, and it survives a season change.
 *
 * <p>Deliberately not a field on {@code Player}: a player {@code PUT} is a full replacement (it
 * would wipe the link) and bumps the player's {@code version}. See {@link PlayerStatsLinkService}
 * for the rules, including a link whose {@code Player} no longer exists.
 */
@Document("player_stats_links")
@CompoundIndex(
    name = PlayerStatsLink.SOURCE_PLAYER_INDEX,
    def = "{ 'clubId': 1, 'sourcePlayerId': 1 }",
    unique = true)
@CompoundIndex(
    name = PlayerStatsLink.PLAYER_INDEX,
    def = "{ 'clubId': 1, 'playerId': 1 }",
    unique = true)
class PlayerStatsLink extends ClubScopedEntity {

  static final String SOURCE_PLAYER_INDEX = "club_source_player_unique";
  static final String PLAYER_INDEX = "club_player_unique";

  @Id private String id;

  /** football.org.il's {@code player_id}; never exposed. */
  private Integer sourcePlayerId;

  /** Our {@code squad.Player}'s id. */
  private String playerId;

  private Instant linkedAt;

  String getId() {
    return id;
  }

  Integer getSourcePlayerId() {
    return sourcePlayerId;
  }

  void setSourcePlayerId(Integer sourcePlayerId) {
    this.sourcePlayerId = sourcePlayerId;
  }

  String getPlayerId() {
    return playerId;
  }

  void setPlayerId(String playerId) {
    this.playerId = playerId;
  }

  Instant getLinkedAt() {
    return linkedAt;
  }

  void setLinkedAt(Instant linkedAt) {
    this.linkedAt = linkedAt;
  }
}
