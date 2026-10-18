package com.squadpulse.match;

import com.squadpulse.common.ClubScopedEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The last good scrape of one club's games in one cup competition on football.org.il, for one team
 * and season (KAN-84): one document per {@code (clubId, teamId, seasonId, competition)}, a normal
 * {@link ClubScopedEntity} read and written only through the club-scoped repository — no new
 * exception to the {@code clubId} isolation.
 *
 * <p>Like {@link LeagueFixturesSnapshot}, a snapshot: every successful ingest of the competition
 * replaces {@link #games} as a whole, so a game missing from a later scrape disappears; a failed or
 * rejected competition writes nothing, leaving the last good snapshot. Written only by {@link
 * ScrapeIngestService}, never older over newer ({@link #fetchedAt}); {@code @Version} makes a
 * concurrent write fail instead of overwriting. Each competition is its own document, so one
 * competition's failure never touches another's games, nor the league snapshot.
 *
 * <p>No {@code leagueId}: a cup belongs to the team and season, not to the league, so reads filter
 * by the current mapping's team and season only. A snapshot of an earlier season or team stays but
 * is never shown.
 */
@Document("cup_fixtures")
@CompoundIndex(
    name = CupFixturesSnapshot.SNAPSHOT_INDEX,
    def = "{ 'clubId': 1, 'teamId': 1, 'seasonId': 1, 'competition': 1 }",
    unique = true)
class CupFixturesSnapshot extends ClubScopedEntity {

  static final String SNAPSHOT_INDEX = "club_team_season_competition_unique";

  @Id private String id;

  @Version private Long version;

  private Integer teamId;

  private Integer seasonId;

  /** Stored as its name. */
  private CupCompetition competition;

  /** When the scraper fetched the list (its own clock); decides which of two scrapes is newer. */
  private Instant fetchedAt;

  /** When this server stored it. */
  private Instant storedAt;

  /** In the order the scraper sent them (the list's order); may be empty. */
  private List<StoredCupGame> games;

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

  CupCompetition getCompetition() {
    return competition;
  }

  void setCompetition(CupCompetition competition) {
    this.competition = competition;
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

  List<StoredCupGame> getGames() {
    return games;
  }

  void setGames(List<StoredCupGame> games) {
    this.games = games;
  }
}
