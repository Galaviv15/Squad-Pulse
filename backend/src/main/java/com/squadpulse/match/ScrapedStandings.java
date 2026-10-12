package com.squadpulse.match;

import java.time.Instant;
import java.util.List;

/**
 * One successful scrape of a league's table, already validated by the caller ({@code
 * scrapingintegration}), handed to {@link ScrapeIngestService}. Goals are for / against; points as
 * published.
 *
 * @param round the round the scraper asked the site for
 */
public record ScrapedStandings(Instant fetchedAt, int round, List<Row> rows) {

  public record Row(
      int position,
      int teamId,
      String teamName,
      int played,
      int won,
      int drawn,
      int lost,
      int goalsFor,
      int goalsAgainst,
      int points) {}
}
