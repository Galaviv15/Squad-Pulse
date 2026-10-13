package com.squadpulse.match;

import com.squadpulse.common.ClubScopedEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The last good scrape of one club's player season statistics on football.org.il, for one team and
 * season (KAN-72): one document per {@code (clubId, teamId, seasonId)}, a normal {@link
 * ClubScopedEntity} read and written only through the club-scoped repository — no exception to the
 * {@code clubId} isolation. Two clubs mapped to the same team each get their own copy, with their
 * own opaque ids.
 *
 * <p>A snapshot, like {@link LeagueFixturesSnapshot}: every successful ingest replaces {@link
 * #players} as a whole, in one single-document write, so a player missing from a later scrape
 * disappears with their stats (their {@link PlayerStatsLink} stays). Written only by {@link
 * ScrapeIngestService}, never older over newer ({@link #fetchedAt}); {@code @Version} makes a
 * concurrent write fail instead of overwriting. Each row keeps its opaque id across replaces for as
 * long as its {@code player_id} stays in the table.
 *
 * <p>No league: the site's table is per team and season, all competitions together. Stays when the
 * club's mapping moves on; reads use the current mapping only.
 */
@Document("player_season_stats")
@CompoundIndex(
    name = PlayerSeasonStatsSnapshot.SNAPSHOT_INDEX,
    def = "{ 'clubId': 1, 'teamId': 1, 'seasonId': 1 }",
    unique = true)
class PlayerSeasonStatsSnapshot extends ClubScopedEntity {

  static final String SNAPSHOT_INDEX = "club_team_season_unique";

  @Id private String id;

  @Version private Long version;

  private Integer teamId;

  private Integer seasonId;

  /** When the scraper fetched the page (its own clock); decides which of two scrapes is newer. */
  private Instant fetchedAt;

  /** When this server stored it. */
  private Instant storedAt;

  /** In the order the scraper sent them (the page's order). */
  private List<StoredPlayerStats> players;

  String getId() {
    return id;
  }

  Long getVersion() {
    return version;
  }

  Integer getTeamId() {
    return teamId;
  }

  void setTeamId(Integer teamId) {
    this.teamId = teamId;
  }

  Integer getSeasonId() {
    return seasonId;
  }

  void setSeasonId(Integer seasonId) {
    this.seasonId = seasonId;
  }

  Instant getFetchedAt() {
    return fetchedAt;
  }

  void setFetchedAt(Instant fetchedAt) {
    this.fetchedAt = fetchedAt;
  }

  Instant getStoredAt() {
    return storedAt;
  }

  void setStoredAt(Instant storedAt) {
    this.storedAt = storedAt;
  }

  List<StoredPlayerStats> getPlayers() {
    return players;
  }

  void setPlayers(List<StoredPlayerStats> players) {
    this.players = players;
  }
}
