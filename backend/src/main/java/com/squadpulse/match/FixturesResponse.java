package com.squadpulse.match;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * {@code GET /matches/fixtures}: the club's league games for its current mapping, from our team's
 * point of view. Never carries a team, league, season or game id (docs/spec.md section 09).
 *
 * @param lastUpdatedAt when the shown snapshot was scraped; {@code null} without one
 * @param teamName our team's name as the site shows it; {@code null} without data
 * @param mappingProblem set when the current league's standings don't contain the configured team
 * @param games by date, then kickoff time, an unset time last within its day
 */
public record FixturesResponse(
    MatchDataState state,
    Instant lastUpdatedAt,
    String teamName,
    MappingProblem mappingProblem,
    List<Game> games) {

  /**
   * @param kickoffTime {@code HH:mm}, {@code null} while not set yet
   * @param venue {@code null} when the site shows none
   * @param ownScore both scores and {@code result} {@code null} while not played
   * @param detailsId our opaque id of the game's details (KAN-79), what {@code GET
   *     /matches/games/{id}} takes; {@code null} unless {@code detailsState} is {@code AVAILABLE}
   * @param detailsState whether the game's details are there; {@code null} for a game not played
   *     yet
   */
  public record Game(
      LocalDate date,
      @JsonFormat(pattern = "HH:mm") LocalTime kickoffTime,
      Side ownSide,
      String opponentName,
      String venue,
      Integer ownScore,
      Integer opponentScore,
      Result result,
      String detailsId,
      DetailsState detailsState) {}

  /** Whether a played game's details (KAN-79) can be shown. */
  public enum DetailsState {
    /** Stored: {@code detailsId} is set. */
    AVAILABLE,
    /** The scraper failed on the page too often; it won't be fetched again. */
    UNAVAILABLE,
    /** Played, not fetched yet. */
    PENDING
  }

  public enum Side {
    HOME,
    AWAY
  }

  public enum Result {
    WIN,
    DRAW,
    LOSS;

    /** From our side's point of view; {@code null} unless both scores are set. */
    static Result of(Integer ownScore, Integer opponentScore) {
      if (ownScore == null || opponentScore == null) {
        return null;
      }
      int compared = Integer.compare(ownScore, opponentScore);
      return compared > 0 ? WIN : compared == 0 ? DRAW : LOSS;
    }
  }
}
