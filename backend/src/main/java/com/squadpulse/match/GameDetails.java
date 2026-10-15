package com.squadpulse.match;

import com.squadpulse.common.ClubScopedEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One played game's details as football.org.il's game page shows them, <b>our side only</b>
 * (KAN-79): one document per {@code (clubId, teamId, seasonId, gameId)}, a normal {@link
 * ClubScopedEntity} read and written only through the club-scoped repository — no exception to the
 * {@code clubId} isolation. Two clubs mapped to the same team each get their own copy, with their
 * own opaque id.
 *
 * <p><b>Per game, not a snapshot</b>: written only by {@link ScrapeIngestService}, which creates
 * the record or replaces this one record only, and only with a newer {@link #fetchedAt}; other
 * games' records are never touched. {@link #id} is our opaque id (a random UUID), kept across
 * replaces: the only id {@code GET /matches/games/{id}} takes or any response shows. The site's
 * {@code game_id} and {@code player_id} never leave the backend.
 *
 * <p>The game's own facts (date, competition, round, sides, score) are stored here, not taken from
 * the fixtures snapshot, which every scrape replaces (and KAN-76 will re-key). The opponent is a
 * name and a score only: its players are never stored. {@link StoredAppearance#shirtNumber} is the
 * number worn in this game; nothing here ever writes a {@code squad.Player} or its jersey number.
 * No minutes played: the page has none, and nothing is derived from the substitution minutes.
 *
 * <p>{@link #date} and {@link #kickoffTime} are Israel local values stored as ISO strings, like
 * {@link StoredGame}, so the server's zone can't shift them. Stays when the club's mapping moves
 * on; reads use the current mapping only.
 */
@Document("game_details")
@CompoundIndex(
    name = GameDetails.GAME_INDEX,
    def = "{ 'clubId': 1, 'teamId': 1, 'seasonId': 1, 'gameId': 1 }",
    unique = true)
class GameDetails extends ClubScopedEntity {

  static final String GAME_INDEX = "club_team_season_game_unique";

  /** Our opaque id, a random UUID set on create and kept on every replace. */
  @Id private String id;

  @Version private Long version;

  private Integer teamId;

  private Integer seasonId;

  /** football.org.il's {@code game_id}; never exposed. */
  private Integer gameId;

  /** When the scraper fetched the page (its own clock); decides which of two scrapes is newer. */
  private Instant fetchedAt;

  /** When this server stored it. */
  private Instant storedAt;

  /** {@code yyyy-MM-dd}, Israel local. */
  private String date;

  /** {@code HH:mm}, Israel local; {@code null} if the site gave none. */
  private String kickoffTime;

  private String competition;

  private Integer round;

  private FixturesResponse.Side ownSide;

  private String homeTeamName;

  private String awayTeamName;

  private Integer homeScore;

  private Integer awayScore;

  /** Our players, in the order sent; each one's events sorted by minute, unknown last. */
  private List<StoredAppearance> appearances;

  /** A new record with a new opaque id. */
  static GameDetails create() {
    GameDetails details = new GameDetails();
    details.id = UUID.randomUUID().toString();
    return details;
  }

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

  Integer getGameId() {
    return gameId;
  }

  void setGameId(Integer gameId) {
    this.gameId = gameId;
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

  LocalDate localDate() {
    return LocalDate.parse(date);
  }

  LocalTime localKickoffTime() {
    return kickoffTime == null ? null : LocalTime.parse(kickoffTime);
  }

  void setDate(LocalDate date) {
    this.date = date.toString();
  }

  void setKickoffTime(LocalTime kickoffTime) {
    this.kickoffTime = kickoffTime == null ? null : kickoffTime.toString();
  }

  String getCompetition() {
    return competition;
  }

  void setCompetition(String competition) {
    this.competition = competition;
  }

  Integer getRound() {
    return round;
  }

  void setRound(Integer round) {
    this.round = round;
  }

  FixturesResponse.Side getOwnSide() {
    return ownSide;
  }

  void setOwnSide(FixturesResponse.Side ownSide) {
    this.ownSide = ownSide;
  }

  String getHomeTeamName() {
    return homeTeamName;
  }

  void setHomeTeamName(String homeTeamName) {
    this.homeTeamName = homeTeamName;
  }

  String getAwayTeamName() {
    return awayTeamName;
  }

  void setAwayTeamName(String awayTeamName) {
    this.awayTeamName = awayTeamName;
  }

  Integer getHomeScore() {
    return homeScore;
  }

  void setHomeScore(Integer homeScore) {
    this.homeScore = homeScore;
  }

  Integer getAwayScore() {
    return awayScore;
  }

  void setAwayScore(Integer awayScore) {
    this.awayScore = awayScore;
  }

  List<StoredAppearance> getAppearances() {
    return appearances;
  }

  void setAppearances(List<StoredAppearance> appearances) {
    this.appearances = appearances;
  }

  /**
   * One of our players in this game, exactly as sent.
   *
   * @param sourcePlayerId football.org.il's {@code player_id}: the key of the KAN-72 links; never
   *     exposed
   * @param name first name first; {@code null} if the site masked it
   * @param shirtNumber worn in this game; {@code null} if missing
   */
  record StoredAppearance(
      int sourcePlayerId,
      String name,
      Integer shirtNumber,
      ScrapedGameDetails.Role role,
      boolean goalkeeper,
      boolean captain,
      List<StoredEvent> events) {}

  /**
   * @param minute {@code null} if unknown
   */
  record StoredEvent(ScrapedGameDetails.EventKind kind, Integer minute) {}
}
