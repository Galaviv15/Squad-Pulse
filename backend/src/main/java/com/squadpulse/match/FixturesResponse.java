package com.squadpulse.match;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * {@code GET /matches/fixtures}: the club's league games and, since KAN-84, its cup games for its
 * current mapping, merged, from our team's point of view. Never carries a team, league, season or
 * game id (docs/spec.md section 09), league or cup.
 *
 * @param state {@code OK} if the league snapshot (of the current league) or at least one cup
 *     snapshot (of the current team and season) exists; {@code NO_DATA} if neither
 * @param lastUpdatedAt the <b>oldest</b> scrape time among the snapshots shown (league and each cup
 *     competition): the list is at least that old; {@code null} without data
 * @param teamName our team's name as the league pages show it (the short name); {@code null}
 *     without a league snapshot, even if cup games are shown
 * @param mappingProblem set when the current league's standings don't contain the configured team
 * @param games by date, then kickoff time (an unset time last within its day), then league before
 *     cup (cups in {@link Competition}'s order), then each snapshot's own order
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
   * @param ownScore both scores {@code null} while not played
   * @param result {@code null} while not played, and for a cup game with {@code extraTime} or
   *     {@code penalties} (KAN-76 decision 6: a tie decided on penalties must never show as a draw,
   *     nor a result computed from a score of unknown meaning); both scores are still shown
   * @param detailsId our opaque id of the game's details (KAN-79), what {@code GET
   *     /matches/games/{id}} takes; {@code null} unless {@code detailsState} is {@code AVAILABLE}
   * @param detailsState whether the game's details are there; {@code null} for a game not played
   *     yet (played = both scores set, league or cup)
   * @param competition {@code LEAGUE} or the cup (KAN-84)
   * @param round a cup game's round as the cup list shows it (free text); {@code null} for a league
   *     game
   * @param extraTime a cup game's extra-time cell, raw, never interpreted; {@code null} for a
   *     league game or an empty cell
   * @param penalties a cup game's penalties cell, raw, never interpreted; {@code null} likewise
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
      DetailsState detailsState,
      Competition competition,
      String round,
      String extraTime,
      String penalties) {}

  /** Which competition a game is in (KAN-84). Declaration order is the order on the same slot. */
  public enum Competition {
    LEAGUE,
    STATE_CUP;

    /** Exhaustive, no {@code default}: a new cup competition doesn't compile until mapped here. */
    static Competition of(CupCompetition competition) {
      return switch (competition) {
        case STATE_CUP -> STATE_CUP;
      };
    }
  }

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
