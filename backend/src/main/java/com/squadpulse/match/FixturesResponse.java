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
   */
  public record Game(
      LocalDate date,
      @JsonFormat(pattern = "HH:mm") LocalTime kickoffTime,
      Side ownSide,
      String opponentName,
      String venue,
      Integer ownScore,
      Integer opponentScore,
      Result result) {}

  public enum Side {
    HOME,
    AWAY
  }

  public enum Result {
    WIN,
    DRAW,
    LOSS
  }
}
