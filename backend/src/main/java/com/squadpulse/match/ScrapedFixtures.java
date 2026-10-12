package com.squadpulse.match;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One successful scrape of a team's league fixtures, already validated by the caller ({@code
 * scrapingintegration}), handed to {@link ScrapeIngestService}. Home / away throughout; dates and
 * times are Israel local.
 */
public record ScrapedFixtures(Instant fetchedAt, List<Game> games) {

  /**
   * @param gameId {@code null} for an unscheduled game
   * @param kickoffTime {@code null} while not set yet
   * @param venue {@code null} when none
   * @param homeScore both scores {@code null} while not played
   */
  public record Game(
      Integer gameId,
      LocalDate date,
      LocalTime kickoffTime,
      String homeTeamName,
      String awayTeamName,
      String venue,
      Integer homeScore,
      Integer awayScore) {}
}
