package com.squadpulse.match;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One game of a {@link CupFixturesSnapshot} (KAN-84), home / away as the scraper sends it, with
 * <b>no team ids</b>: a cup row has none, and its names are full club names, which can't be matched
 * against the league's standings. Our side comes from the scraper ({@link #ownSide}); the backend
 * does no name matching for cups.
 *
 * <p>{@link #date} and {@link #kickoffTime} are ISO strings, as in {@link StoredGame} and for the
 * same reason (a {@link LocalDate} would be stored as a zone-shifted BSON date).
 *
 * @param key the game's identity within its competition: round, home and away, see {@link #keyOf}.
 *     Not {@link #gameId} (an attribute only).
 * @param gameId the site's game id, {@code null} without a link; never leaves the backend
 * @param round as the cup list shows it (free text, e.g. {@code ו}); never the game page's {@code
 *     מחזור N}
 * @param kickoffTime {@code null} while not set
 * @param homeTeamName full club name, as the cup list shows it
 * @param venue {@code null} when the site shows none
 * @param homeScore both scores {@code null} while not played
 * @param extraTime the extra-time cell as sent, never interpreted; {@code null} when empty
 * @param penalties the penalties cell as sent, never interpreted; {@code null} when empty
 */
record StoredCupGame(
    String key,
    Integer gameId,
    String round,
    String date,
    String kickoffTime,
    String homeTeamName,
    String awayTeamName,
    FixturesResponse.Side ownSide,
    String venue,
    Integer homeScore,
    Integer awayScore,
    String extraTime,
    String penalties) {

  /**
   * The key of a cup game within its competition (the competition is the snapshot's own key):
   * round, home and away joined with U+001F, a control character no value can contain, as in {@link
   * StoredGame#keyOf}. The round keeps two games of the same pairing in different rounds apart.
   */
  static String keyOf(String round, String homeTeamName, String awayTeamName) {
    return round + '\u001F' + homeTeamName + '\u001F' + awayTeamName;
  }

  LocalDate localDate() {
    return LocalDate.parse(date);
  }

  LocalTime localKickoffTime() {
    return kickoffTime == null ? null : LocalTime.parse(kickoffTime);
  }

  /** Whether the result can't be read as a plain win, draw or loss (KAN-76 decision 6). */
  boolean decidedBeyondTheScore() {
    return extraTime != null || penalties != null;
  }
}
