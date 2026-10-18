package com.squadpulse.match;

import java.util.List;

/**
 * What {@link ScrapeIngestService#ingest} did with each part of one scrape. Never names a club.
 *
 * @param playerStats the player season statistics (KAN-72)
 * @param gameDetails the per-game details (KAN-79): never {@link Outcome#STALE}, staleness is per
 *     game there
 * @param cupGames the cup games (KAN-84), with an outcome per competition
 * @param clubsUpdated how many clubs' fixtures snapshots were written
 */
public record IngestResult(
    PartResult fixtures,
    PartResult standings,
    PartResult playerStats,
    PartResult gameDetails,
    CupGamesResult cupGames,
    int clubsUpdated,
    List<Warning> warnings) {

  /**
   * The cup games part (KAN-84). {@code part} is {@link Outcome#STORED} once processed, whatever
   * each competition's own outcome (like the game details), {@code REJECTED NOT_MAPPED} when no
   * club is mapped (then every competition is too), {@code NOT_SENT} when absent.
   *
   * @param competitions one per competition handed to the ingest, in that order; empty when not
   *     sent
   */
  public record CupGamesResult(PartResult part, List<CompetitionResult> competitions) {

    static final CupGamesResult NOT_SENT = new CupGamesResult(PartResult.NOT_SENT, List.of());
  }

  /**
   * One cup competition's outcome: {@code STORED} (written for at least one club), {@code STALE}
   * (not newer for any club), or {@code REJECTED} with {@code NOT_MAPPED}, {@code DUPLICATE_GAME}
   * or {@code EMPTY_REPLACES_GAMES}.
   */
  public record CompetitionResult(CupCompetition competition, PartResult result) {}

  /** The outcome of one part; {@code reason} is set only for {@link Outcome#REJECTED}. */
  public record PartResult(Outcome outcome, RejectionReason reason) {

    static final PartResult STORED = new PartResult(Outcome.STORED, null);
    static final PartResult STALE = new PartResult(Outcome.STALE, null);
    static final PartResult NOT_SENT = new PartResult(Outcome.NOT_SENT, null);

    static PartResult rejected(RejectionReason reason) {
      return new PartResult(Outcome.REJECTED, reason);
    }
  }

  public enum Outcome {
    /**
     * Written: it replaced the stored snapshot, or was the first one. For the game details: the
     * part was processed, whatever each game's own outcome (incl. none, or all not newer).
     */
    STORED,
    /** Refused, nothing written; see {@link RejectionReason}. */
    REJECTED,
    /** Not newer than the stored snapshot (an older scrape arriving late, or a replay): ignored. */
    STALE,
    /** The scrape didn't include this part. */
    NOT_SENT
  }

  public enum RejectionReason {
    /** No club is currently mapped to the scrape's ids (any part). */
    NOT_MAPPED,
    /**
     * The same home / away pairing twice in one fixtures list; for a cup competition (KAN-84), the
     * same round, home and away twice in its list (that competition only).
     */
    DUPLICATE_GAME,
    /** Without standings, our team's name couldn't be told from the games. */
    OWN_TEAM_UNKNOWN,
    /** A game without our team on either side. */
    INVALID,
    /**
     * A cup competition's list came empty while the club's stored snapshot of it (same team, season
     * and competition) has games (KAN-84). Within a season cup games never leave the list — played
     * games stay listed after an elimination — so an empty list over stored games is a site glitch,
     * not news: the stored games stay, and the refresh fails visibly.
     */
    EMPTY_REPLACES_GAMES
  }

  /** Something the scraper (and whoever runs it) should know, though the data was handled. */
  public record Warning(WarningCode code, Integer count) {}

  public enum WarningCode {
    /** The league's standings don't contain the configured team: the mapping is probably wrong. */
    TEAM_NOT_IN_STANDINGS,
    /** {@code count} distinct fixture team names had no exact match in the standings. */
    UNRESOLVED_TEAM_NAMES
  }
}
