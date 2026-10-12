package com.squadpulse.scrapingintegration;

import java.time.Instant;

/**
 * The answer to both {@code POST} and {@code GET /matches/refresh} (KAN-68): what a refresh request
 * did, and the state of the caller's club's latest scrape run. Never carries a football.org.il id,
 * a runId, the scraper's URL, a Redis key or the scraper's {@code detail} text, and never tells a
 * club whether another club in its league refreshed.
 *
 * @param outcome set only by {@code POST}; always {@code null} on {@code GET}
 * @param state the club's latest run, after this request
 * @param failureReason set only with {@link State#FAILED}
 * @param startedAt when the latest run was started; {@code null} without one
 * @param finishedAt when the backend recorded its end (result received, or timed out)
 * @param nextRefreshAt when a {@code POST} may next start a scrape; {@code null} if it may now
 */
public record RefreshStatusResponse(
    Outcome outcome,
    State state,
    FailureReason failureReason,
    Instant startedAt,
    Instant finishedAt,
    Instant nextRefreshAt) {

  /** What a {@code POST} did. States to show, not errors: only {@link #STARTED} is a 202. */
  public enum Outcome {
    /** The scraper accepted the trigger; a result follows. */
    STARTED,
    /** A run of this club is already queued or running. */
    ALREADY_RUNNING,
    /** The club's team was scraped too recently; see {@code nextRefreshAt}. */
    COOLDOWN,
    /** The club has no football.org.il mapping; nothing happened. */
    NOT_MAPPED,
    /** The scraper isn't configured, unreachable, or refused the trigger; try again. */
    SCRAPER_UNAVAILABLE
  }

  public enum State {
    NOT_MAPPED,
    /** No run yet, its record expired, or the mapping changed since. */
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  public enum FailureReason {
    SOURCE_BLOCKED,
    SOURCE_TIMEOUT,
    SOURCE_ERROR,
    PARSE_ERROR,
    /** The backend refused what the scraper sent. */
    REJECTED,
    /** No result arrived in time, or a requested part was missing from it. */
    NO_RESULT
  }

  static RefreshStatusResponse notMapped(Outcome outcome) {
    return new RefreshStatusResponse(outcome, State.NOT_MAPPED, null, null, null, null);
  }
}
