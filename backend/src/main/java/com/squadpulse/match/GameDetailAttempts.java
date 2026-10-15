package com.squadpulse.match;

import com.squadpulse.common.ClubScopedEntity;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The scraper's failed attempts at one game's details page (KAN-79): one document per {@code
 * (clubId, teamId, seasonId, gameId)}, a normal {@link ClubScopedEntity}, written only by {@link
 * ScrapeIngestService} and deleted once the game's {@link GameDetails} are stored.
 *
 * <p>Only a broken page counts ({@link ScrapedGameDetails.FailureReason#countsAsAttempt()}: {@code
 * PARSE_ERROR}, {@code SOURCE_ERROR}); a {@code TIMEOUT} or {@code SOURCE_BLOCKED} updates {@link
 * #lastReason} and {@link #lastAttemptAt} only. After {@value #GIVE_UP_AFTER} counted failures the
 * game is given up ({@link #givenUp}): the trigger lists it as known, so the scraper doesn't fetch
 * it, and the fixtures show its details as unavailable. Giving up isn't permanent: {@link
 * #RETRY_GIVEN_UP_AFTER} after its last <b>counted</b> failure ({@link #lastCountedAttemptAt}) the
 * game is a candidate again (a markup the parser didn't know may have been fixed, a site error may
 * have passed). A retry that fails with a counting reason gives it up for another period; one that
 * succeeds stores the details and deletes this record. {@link #failedAttempts} is never reset.
 *
 * <p>A record without {@link #lastCountedAttemptAt} (written before this rule, on an unshipped
 * branch; no migration) is never given up.
 *
 * <p>An attempt is recorded at most once per scrape: a failure whose {@code fetchedAt} isn't newer
 * than {@link #lastAttemptAt} (a replayed request) is ignored.
 */
@Document("game_detail_attempts")
@CompoundIndex(
    name = GameDetailAttempts.GAME_INDEX,
    def = "{ 'clubId': 1, 'teamId': 1, 'seasonId': 1, 'gameId': 1 }",
    unique = true)
class GameDetailAttempts extends ClubScopedEntity {

  static final String GAME_INDEX = "club_team_season_game_unique";

  /** Counted failures after which a game is given up. */
  static final int GIVE_UP_AFTER = 3;

  /** How long after its last counted failure a given-up game stays given up. */
  static final Duration RETRY_GIVEN_UP_AFTER = Duration.ofDays(3);

  @Id private String id;

  @Version private Long version;

  private Integer teamId;

  private Integer seasonId;

  /** football.org.il's {@code game_id}; never exposed. */
  private Integer gameId;

  /** Failures that count ({@code PARSE_ERROR}, {@code SOURCE_ERROR}). */
  private int failedAttempts;

  private ScrapedGameDetails.FailureReason lastReason;

  /**
   * The {@code fetchedAt} of the scrape that last failed on this game, counted or not: replay
   * protection and {@link #lastReason}.
   */
  private Instant lastAttemptAt;

  /**
   * The {@code fetchedAt} of the last failure that counted (incremented {@link #failedAttempts});
   * what the give-up period runs from, so a {@code TIMEOUT} on a retry can't extend it.
   */
  private Instant lastCountedAttemptAt;

  /**
   * The one rule for "given up" (the trigger's known games and the fixtures' {@code UNAVAILABLE}):
   * at least {@value #GIVE_UP_AFTER} counted failures, the last of them less than {@link
   * #RETRY_GIVEN_UP_AFTER} before {@code now}. Without {@link #lastCountedAttemptAt}: not given up.
   */
  boolean givenUp(Instant now) {
    return failedAttempts >= GIVE_UP_AFTER
        && lastCountedAttemptAt != null
        && lastCountedAttemptAt.isAfter(now.minus(RETRY_GIVEN_UP_AFTER));
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

  int getFailedAttempts() {
    return failedAttempts;
  }

  void setFailedAttempts(int failedAttempts) {
    this.failedAttempts = failedAttempts;
  }

  ScrapedGameDetails.FailureReason getLastReason() {
    return lastReason;
  }

  void setLastReason(ScrapedGameDetails.FailureReason lastReason) {
    this.lastReason = lastReason;
  }

  Instant getLastAttemptAt() {
    return lastAttemptAt;
  }

  void setLastAttemptAt(Instant lastAttemptAt) {
    this.lastAttemptAt = lastAttemptAt;
  }

  Instant getLastCountedAttemptAt() {
    return lastCountedAttemptAt;
  }

  void setLastCountedAttemptAt(Instant lastCountedAttemptAt) {
    this.lastCountedAttemptAt = lastCountedAttemptAt;
  }
}
