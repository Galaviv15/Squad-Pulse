package com.squadpulse.scrapingintegration;

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
 * @param clubsUpdated how many clubs' fixtures were written (normally 0 or 1)
 */
public record ScrapeResultResponse(
    PartOutcome fixtures,
    PartOutcome standings,
    PartOutcome playerStats,
    PartOutcome gameDetails,
    int clubsUpdated,
    List<Warning> warnings) {

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
