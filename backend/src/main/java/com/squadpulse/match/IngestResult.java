package com.squadpulse.match;

import java.util.List;

/**
 * What {@link ScrapeIngestService#ingest} did with each part of one scrape. Never names a club.
 *
 * @param playerStats the player season statistics (KAN-72)
 * @param clubsUpdated how many clubs' fixtures snapshots were written
 */
public record IngestResult(
    PartResult fixtures,
    PartResult standings,
    PartResult playerStats,
    int clubsUpdated,
    List<Warning> warnings) {

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
    /** Written: it replaced the stored snapshot, or was the first one. */
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
    /** The same home / away pairing twice in one fixtures list. */
    DUPLICATE_GAME,
    /** Without standings, our team's name couldn't be told from the games. */
    OWN_TEAM_UNKNOWN,
    /** A game without our team on either side. */
    INVALID
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
