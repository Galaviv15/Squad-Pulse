package com.squadpulse.match;

/** Whether a match read has data to show (KAN-66). */
public enum MatchDataState {
  /** The club has no football.org.il mapping: nothing can be scraped for it. Not an error. */
  NOT_MAPPED,
  /** Mapped, but nothing has been stored yet for the current mapping. */
  NO_DATA,
  OK
}
