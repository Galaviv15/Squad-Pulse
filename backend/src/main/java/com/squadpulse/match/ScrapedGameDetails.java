package com.squadpulse.match;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One successful game-details part of a scrape (KAN-79), already validated by the caller ({@code
 * scrapingintegration}), handed to {@link ScrapeIngestService}: the details of the games the
 * scraper fetched, and the games it tried and couldn't get. <b>Our side only</b>: the opponent is a
 * name and a score, its players are never sent.
 *
 * @param games at most 10; a {@code gameId} at most once across {@code games} and {@code failures}
 * @param failures at most 10, only games the scraper actually requested
 */
public record ScrapedGameDetails(Instant fetchedAt, List<Game> games, List<Failure> failures) {

  /**
   * One played game's details.
   *
   * @param gameId the site's {@code game_id}; never leaves the backend
   * @param date Israel local date, from the team-games row
   * @param kickoffTime Israel local time, {@code null} if the row has none
   * @param competition the page's {@code h1} before {@code " מחזור N"}, trimmed
   * @param ownSide our team's side on the page
   * @param homeTeamName as the page shows it, trimmed
   * @param players our side's players, as sent; {@code playerId} unique
   */
  public record Game(
      int gameId,
      LocalDate date,
      LocalTime kickoffTime,
      String competition,
      int round,
      FixturesResponse.Side ownSide,
      String homeTeamName,
      String awayTeamName,
      int homeScore,
      int awayScore,
      List<Appearance> players) {}

  /**
   * One of our players in one game.
   *
   * @param playerId the site's {@code player_id} (the KAN-72 stats link key); never leaves the
   *     backend
   * @param name first name first, trimmed; {@code null} if the site masks it. Never logged.
   * @param shirtNumber the number worn in this game, {@code null} if missing. Never {@code
   *     Player.jerseyNumber}.
   * @param events in the order sent
   */
  public record Appearance(
      int playerId,
      String name,
      Integer shirtNumber,
      Role role,
      boolean goalkeeper,
      boolean captain,
      List<Event> events) {}

  /**
   * @param minute as the site shows it (a plain number, 93 rather than 90+3); {@code null} if
   *     unknown (the site's {@code -1})
   */
  public record Event(EventKind kind, Integer minute) {}

  /**
   * A game the scraper requested and couldn't read.
   *
   * @param detail free text for the operator; never logged above DEBUG
   */
  public record Failure(int gameId, FailureReason reason, String detail) {}

  /** A player's part in the game. */
  public enum Role {
    STARTER,
    /** Came on. */
    SUBSTITUTE,
    /** On the bench, never came on. */
    UNUSED
  }

  /** What happened to a player, at a minute. Own goals and penalties aren't in the contract yet. */
  public enum EventKind {
    GOAL,
    YELLOW,
    SECOND_YELLOW,
    RED,
    SUB_ON,
    SUB_OFF
  }

  /**
   * Why one game couldn't be read; the same values as the contract's {@code FailureReason}. Only a
   * broken page counts as a failed attempt ({@link #countsAsAttempt()}).
   */
  public enum FailureReason {
    SOURCE_BLOCKED,
    TIMEOUT,
    SOURCE_ERROR,
    PARSE_ERROR;

    /**
     * Whether this failure counts towards giving the game up: only {@code PARSE_ERROR} and {@code
     * SOURCE_ERROR} (the page itself is broken), not {@code TIMEOUT} or {@code SOURCE_BLOCKED} (the
     * site or the network), so an outage can't burn healthy games.
     */
    public boolean countsAsAttempt() {
      return this == PARSE_ERROR || this == SOURCE_ERROR;
    }
  }
}
