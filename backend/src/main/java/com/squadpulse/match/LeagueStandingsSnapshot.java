package com.squadpulse.match;

import com.squadpulse.common.NotClubScoped;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The last good scrape of one league's table on football.org.il, for one season (KAN-66): one
 * document per {@code (leagueId, seasonId)}, replaced as a whole on every successful ingest.
 *
 * <p><b>Deliberately {@link NotClubScoped}: the first tenant-shared data.</b> A league's table is
 * the same public table for every club in that league, so it's stored once, not per club. That is
 * safe only while all of these hold, each covered by a test:
 *
 * <ul>
 *   <li><b>One write path:</b> {@link ScrapeIngestService}, reachable only from the service-token
 *       ingest endpoint ({@code scrapingintegration}); no user-facing endpoint, and no code
 *       reachable from one, ever writes it ({@code StandingsWritePathTest}). Its repository is
 *       package-private.
 *   <li><b>Only for a league in use:</b> stored only if at least one club is currently mapped to
 *       that league and season, so no caller can fill the collection with arbitrary leagues.
 *   <li><b>Reads by the caller's own mapping only:</b> {@link MatchReadService} takes league and
 *       season from the caller's club ({@code auth.ClubFootballMappings}), never from a request
 *       parameter, body or path; no endpoint takes a league id.
 *   <li><b>Nothing club-owned in it:</b> only what the site publishes; which row is "ours" is
 *       worked out per request from the caller's mapping, never stored here.
 * </ul>
 */
@Document("league_standings")
@CompoundIndex(
    name = LeagueStandingsSnapshot.SNAPSHOT_INDEX,
    def = "{ 'leagueId': 1, 'seasonId': 1 }",
    unique = true)
@NotClubScoped
class LeagueStandingsSnapshot {

  static final String SNAPSHOT_INDEX = "league_season_unique";

  @Id private String id;

  @Version private Long version;

  private Integer leagueId;

  private Integer seasonId;

  /** The round the scraper asked the site for: the table doesn't say which round it shows. */
  private Integer round;

  /** When the scraper fetched the table (its own clock); decides which of two scrapes is newer. */
  private Instant fetchedAt;

  /** When this server stored it. */
  private Instant storedAt;

  /** In the order the scraper sent them. */
  private List<StoredStandingsRow> rows;

  String getId() {
    return id;
  }

  Long getVersion() {
    return version;
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

  Integer getRound() {
    return round;
  }

  void setRound(Integer round) {
    this.round = round;
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

  List<StoredStandingsRow> getRows() {
    return rows;
  }

  void setRows(List<StoredStandingsRow> rows) {
    this.rows = rows;
  }
}
