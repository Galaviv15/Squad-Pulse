package com.squadpulse.scrapingintegration;

import com.squadpulse.match.CupCompetition;
import com.squadpulse.match.IngestResult;
import java.util.List;

/**
 * The answer to {@code POST /internal/scrape-results}, part of the scraper ↔ backend contract
 * (docs/scraper-backend-contract.md). Always a 200 once the request is valid, even if every part
 * was rejected: these are data outcomes the scraper should log, not client errors. Never names a
 * club.
 *
 * @param playerStats the player season statistics (KAN-72); {@code NOT_SENT} when absent
 * @param gameDetails the per-game details (KAN-79); {@code NOT_SENT} when absent, never {@code
 *     STALE} (staleness is per game, silently)
 * @param cupGames the cup games (KAN-84), with an outcome per competition
 * @param clubsUpdated how many clubs' fixtures were written (normally 0 or 1)
 */
public record ScrapeResultResponse(
    PartOutcome fixtures,
    PartOutcome standings,
    PartOutcome playerStats,
    PartOutcome gameDetails,
    CupGamesOutcome cupGames,
    int clubsUpdated,
    List<Warning> warnings) {

  /**
   * The cup games part (KAN-84). {@code outcome}: {@code STORED} once processed, whatever each
   * competition's own outcome; {@code REJECTED NOT_MAPPED} when no club is mapped; {@code
   * FAILURE_RECORDED} when the scraper reported the whole part {@code FAILED} (then {@code
   * competitions} is empty); {@code NOT_SENT} when absent (likewise).
   *
   * @param reason set only for {@link Outcome#REJECTED}
   * @param competitions every competition of the request, in its order
   */
  public record CupGamesOutcome(
      Outcome outcome, IngestResult.RejectionReason reason, List<CompetitionOutcome> competitions) {

    static final CupGamesOutcome NOT_SENT = new CupGamesOutcome(Outcome.NOT_SENT, null, List.of());
  }

  /**
   * One cup competition: {@code STORED}, {@code STALE}, {@code REJECTED} (with {@code NOT_MAPPED},
   * {@code DUPLICATE_GAME} or {@code EMPTY_REPLACES_GAMES}) or {@code FAILURE_RECORDED} (the
   * scraper reported it {@code FAILED}; its stored games stay).
   *
   * @param reason set only for {@link Outcome#REJECTED}
   */
  public record CompetitionOutcome(
      CupCompetition competition, Outcome outcome, IngestResult.RejectionReason reason) {}

  /**
   * @param reason set only for {@link Outcome#REJECTED}
   */
  public record PartOutcome(Outcome outcome, IngestResult.RejectionReason reason) {}

  public enum Outcome {
    STORED,
    REJECTED,
    STALE,
    /** The scraper reported the part as {@code FAILED}; nothing written, the old data stays. */
    FAILURE_RECORDED,
    NOT_SENT
  }

  /**
   * @param count for {@code UNRESOLVED_TEAM_NAMES}, how many distinct names; otherwise {@code null}
   */
  public record Warning(IngestResult.WarningCode code, Integer count) {}
}
