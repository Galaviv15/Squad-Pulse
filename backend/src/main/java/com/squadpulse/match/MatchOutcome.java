package com.squadpulse.match;

import com.squadpulse.match.FixturesResponse.DecidedBy;
import com.squadpulse.match.FixturesResponse.Result;
import com.squadpulse.match.FixturesResponse.Score;

/**
 * How a game ended from our side (KAN-84): the one place both reads ({@code GET /matches/fixtures}
 * and {@code GET /matches/games/{id}}) take {@code result}, {@code extraTime}, {@code penalties}
 * and {@code decidedBy} from.
 *
 * <p>{@code result} is <b>who went through</b>, from the last stage present: the shootout ({@code
 * WIN} / {@code LOSS}; a level shootout can't happen, so it's bad data and {@code null}), else the
 * score after extra time ({@code WIN} / {@code DRAW} / {@code LOSS}), else the 90-minute score, as
 * for a league game. {@code decidedBy} is {@code PENALTIES} with a shootout, else {@code
 * EXTRA_TIME} with extra time, else {@code null}.
 *
 * @param extraTime the score after extra time (cumulative, incl. the 90 minutes), from our side;
 *     {@code null} without extra time
 * @param penalties the shootout tally only, from our side; {@code null} without a shootout
 */
record MatchOutcome(Score extraTime, Score penalties, DecidedBy decidedBy, Result result) {

  /** A game with no stage after the 90 minutes (every league game): {@code result} as before. */
  static MatchOutcome ofScore(Integer ownScore, Integer opponentScore) {
    return of(ownScore, opponentScore, null, null);
  }

  /** A cup game, turned to our side by its stored {@code ownSide}. */
  static MatchOutcome ofCup(StoredCupGame game) {
    boolean home = game.ownSide() == FixturesResponse.Side.HOME;
    return of(
        home ? game.homeScore() : game.awayScore(),
        home ? game.awayScore() : game.homeScore(),
        ourSide(home, game.extraTimeHomeScore(), game.extraTimeAwayScore()),
        ourSide(home, game.penaltiesHomeScore(), game.penaltiesAwayScore()));
  }

  /** All from our side; {@code extraTime} / {@code penalties} {@code null} when absent. */
  static MatchOutcome of(
      Integer ownScore, Integer opponentScore, Score extraTime, Score penalties) {
    if (penalties != null) {
      int compared = Integer.compare(penalties.ownScore(), penalties.opponentScore());
      Result result = compared > 0 ? Result.WIN : compared < 0 ? Result.LOSS : null;
      return new MatchOutcome(extraTime, penalties, DecidedBy.PENALTIES, result);
    }
    if (extraTime != null) {
      return new MatchOutcome(
          extraTime,
          null,
          DecidedBy.EXTRA_TIME,
          Result.of(extraTime.ownScore(), extraTime.opponentScore()));
    }
    return new MatchOutcome(null, null, null, Result.of(ownScore, opponentScore));
  }

  /** A home-first pair from our side; {@code null} unless both are set. */
  private static Score ourSide(boolean home, Integer homeScore, Integer awayScore) {
    if (homeScore == null || awayScore == null) {
      return null;
    }
    return home ? new Score(homeScore, awayScore) : new Score(awayScore, homeScore);
  }
}
