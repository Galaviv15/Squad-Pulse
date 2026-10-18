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
   * @param ownScore the score after 90 minutes; both {@code null} while not played
   * @param result who went through, from our side, by the last stage present ({@link
   *     MatchOutcome}): the shootout, else extra time, else the 90 minutes; {@code null} while not
   *     played (and for a level shootout, which is bad data)
   * @param detailsId our opaque id of the game's details (KAN-79), what {@code GET
   *     /matches/games/{id}} takes; {@code null} unless {@code detailsState} is {@code AVAILABLE}
   * @param detailsState whether the game's details are there; {@code null} for a game not played
   *     yet (played = both scores set, league or cup)
   * @param competition {@code LEAGUE} or the cup (KAN-84)
   * @param round a cup game's round as the cup list shows it (free text); {@code null} for a league
   *     game
   * @param extraTime a cup game's score after extra time (cumulative, incl. the 90 minutes), from
   *     our side; {@code null} for a league game or without extra time
   * @param penalties a cup game's shootout tally only, from our side; {@code null} likewise
   * @param decidedBy {@code PENALTIES} with a shootout, else {@code EXTRA_TIME} with extra time,
   *     else {@code null} (decided in 90 minutes, not played, or a league game)
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
      Score extraTime,
      Score penalties,
      DecidedBy decidedBy) {}

  /** A score from our side (KAN-84: after extra time, or a shootout's tally). */
  public record Score(int ownScore, int opponentScore) {}

  /** The stage after the 90 minutes that decided a cup game (KAN-84). */
  public enum DecidedBy {
    EXTRA_TIME,
    PENALTIES
  }

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
