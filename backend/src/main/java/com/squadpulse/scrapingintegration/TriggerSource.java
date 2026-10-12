package com.squadpulse.scrapingintegration;

/** Who started a scrape run: kept in the run record and log lines only (KAN-68). */
public enum TriggerSource {
  /** A user's {@code POST /matches/refresh}. */
  USER,
  /** A scheduled job (not built yet; {@link ScrapeTriggerService} is ready for it). */
  SCHEDULED
}
