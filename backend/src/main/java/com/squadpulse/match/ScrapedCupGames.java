package com.squadpulse.match;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One successful scrape of a team's cup games (KAN-84), already validated by the caller ({@code
 * scrapingintegration}), handed to {@link ScrapeIngestService}: only the competitions the scraper
 * read ({@code OK}); one it reported {@code FAILED} is handled by the caller and not in here. Home
 * / away throughout; dates and times are Israel local.
 *
 * @param competitions each competition at most once, in the request's order
 */
public record ScrapedCupGames(Instant fetchedAt, List<Competition> competitions) {

  /**
   * @param games in the list's order; may be empty (the site's {@code Games: null})
   */
  public record Competition(CupCompetition competition, List<Game> games) {}

  /**
   * @param gameId {@code null} without a link
   * @param round as the cup list shows it
   * @param kickoffTime {@code null} while not set
   * @param homeTeamName full club name, as the list shows it
   * @param ownSide decided by the scraper; the backend does no name matching for cups
   * @param venue {@code null} when none
   * @param homeScore the score after 90 minutes; both {@code null} while not played
   * @param extraTimeHomeScore after extra time, cumulative, home first; both {@code null} without
   * @param penaltiesHomeScore the shootout tally only, home first; both {@code null} without
   */
  public record Game(
      Integer gameId,
      String round,
      LocalDate date,
      LocalTime kickoffTime,
      String homeTeamName,
      String awayTeamName,
      FixturesResponse.Side ownSide,
      String venue,
      Integer homeScore,
      Integer awayScore,
      Integer extraTimeHomeScore,
      Integer extraTimeAwayScore,
      Integer penaltiesHomeScore,
      Integer penaltiesAwayScore) {}
}
