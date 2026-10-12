package com.squadpulse.match;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One game of a {@link LeagueFixturesSnapshot}, always home / away as the scraper sends it.
 *
 * <p>{@link #date} and {@link #kickoffTime} are Israel local values ({@code Asia/Jerusalem}, as the
 * site shows them) stored as ISO strings, {@code "2026-09-03"} and {@code "21:15"}: Spring Data
 * MongoDB would store a {@link LocalDate} as a BSON date at midnight in the JVM's default zone,
 * which shifts the day with the server's zone. A string can't shift.
 *
 * @param key the game's identity within its season: home and away names, see {@link #keyOf}. Not
 *     {@link #gameId}, which is absent for an unscheduled game and may change on a reschedule.
 * @param gameId the site's game id, {@code null} while the game is unscheduled (the site's {@code
 *     -1}); an attribute only
 * @param kickoffTime {@code null} while not set yet (the site's {@code 00:00})
 * @param homeTeamId resolved by exact name from the same league's standings; {@code null} if not
 *     found
 * @param venue {@code null} when the site shows none
 * @param homeScore both scores {@code null} while not played
 */
record StoredGame(
    String key,
    Integer gameId,
    String date,
    String kickoffTime,
    String homeTeamName,
    String awayTeamName,
    Integer homeTeamId,
    Integer awayTeamId,
    String venue,
    Integer homeScore,
    Integer awayScore) {

  /**
   * The key of a home / away pairing. Names can contain almost any punctuation, so they're joined
   * with a control character (U+001F, unit separator) no name can contain.
   */
  static String keyOf(String homeTeamName, String awayTeamName) {
    return homeTeamName + '\u001F' + awayTeamName;
  }

  LocalDate localDate() {
    return LocalDate.parse(date);
  }

  LocalTime localKickoffTime() {
    return kickoffTime == null ? null : LocalTime.parse(kickoffTime);
  }
}
