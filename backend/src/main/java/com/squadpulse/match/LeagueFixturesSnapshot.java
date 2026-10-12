package com.squadpulse.match;

import com.squadpulse.common.ClubScopedEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The last good scrape of one club's league fixtures on football.org.il, for one team and season
 * (KAN-66): one document per {@code (clubId, teamId, seasonId)}, a normal {@link ClubScopedEntity}
 * read and written only through the club-scoped repository.
 *
 * <p>A snapshot, not a set of game records: every successful ingest replaces {@link #games} as a
 * whole, in one single-document write, so a game missing from a later scrape simply disappears, and
 * a failed or rejected scrape writes nothing, leaving the last good snapshot. Written only by
 * {@link ScrapeIngestService}, never older over newer ({@link #fetchedAt}); {@code @Version} makes
 * a concurrent write fail instead of overwriting.
 *
 * <p>Stays when the club's mapping moves on (a new season, team or league): reads use the current
 * mapping only ({@link MatchReadService}), so an old snapshot is kept but never shown.
 */
@Document("league_fixtures")
@CompoundIndex(
    name = LeagueFixturesSnapshot.SNAPSHOT_INDEX,
    def = "{ 'clubId': 1, 'teamId': 1, 'seasonId': 1 }",
    unique = true)
class LeagueFixturesSnapshot extends ClubScopedEntity {

  static final String SNAPSHOT_INDEX = "club_team_season_unique";

  @Id private String id;

  @Version private Long version;

  private Integer teamId;

  /** The league it was scraped for; a read under another league doesn't show it. */
  private Integer leagueId;

  private Integer seasonId;

  /** Our team's name as the site shows it, from the standings or, without them, the fixtures. */
  private String teamName;

  /** When the scraper fetched the page (its own clock); decides which of two scrapes is newer. */
  private Instant fetchedAt;

  /** When this server stored it. */
  private Instant storedAt;

  /** In the order the scraper sent them (the page's order). */
  private List<StoredGame> games;

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

  Integer getLeagueId() {
    return leagueId;
  }

  void setLeagueId(Integer leagueId) {
    this.leagueId = leagueId;
  }

  Integer getSeasonId() {
    return seasonId;
  }

  void setSeasonId(Integer seasonId) {
    this.seasonId = seasonId;
  }

  String getTeamName() {
    return teamName;
  }

  void setTeamName(String teamName) {
    this.teamName = teamName;
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

  List<StoredGame> getGames() {
    return games;
  }

  void setGames(List<StoredGame> games) {
    this.games = games;
  }
}
