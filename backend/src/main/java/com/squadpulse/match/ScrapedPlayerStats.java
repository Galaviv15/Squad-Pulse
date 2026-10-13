package com.squadpulse.match;

import java.time.Instant;
import java.util.List;

/**
 * One successful scrape of a team's player season statistics (KAN-72), already validated by the
 * caller ({@code scrapingintegration}), handed to {@link ScrapeIngestService}. All competitions
 * together; rows in the page's order.
 */
public record ScrapedPlayerStats(Instant fetchedAt, List<Row> players) {

  /**
   * @param playerId the site's {@code player_id}, unique in the list
   * @param name as the site shows it (surname first), trimmed
   */
  public record Row(
      int playerId,
      String name,
      int games,
      int goals,
      int yellowCardsLeagueAndCup,
      int yellowCardsToto,
      int redCards,
      int starts,
      int cameOnAsSub,
      int subbedOff,
      int minutes) {}
}
