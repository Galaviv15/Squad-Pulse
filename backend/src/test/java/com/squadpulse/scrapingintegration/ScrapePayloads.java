package com.squadpulse.scrapingintegration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scrape-result request bodies in the shape of the KAN-64 sample (Sderot's league, season 28, cut
 * down to five teams), as mutable maps so a test can break one field. Home / away and for / against
 * as the contract says (the scraper has already fixed the site's order).
 */
final class ScrapePayloads {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static final int LEAGUE = 1010;
  static final int SEASON = 28;

  static final int SDEROT = 7617;
  static final int TIRA = 7001;
  static final int KIRYAT_MALACHI = 7002;
  static final int NIR_RAM = 7003;
  static final int HADERA = 7004;

  static final String SDEROT_NAME = "מ.כ. שדרות";
  static final String TIRA_NAME = "מ.כ. צעירי טירה";
  static final String KIRYAT_MALACHI_NAME = "מכבי קרית מלאכי עירוני";
  static final String NIR_RAM_NAME = "הפועל ניר רמה\"ש";
  static final String HADERA_NAME = "הפ' חדרה ש. שוורץ";

  /** A day ago, so every later scrape a test builds (hours on) is still in the past. */
  static final Instant FETCHED_AT =
      Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

  private ScrapePayloads() {}

  /** Both parts OK for Sderot. */
  static Map<String, Object> full() {
    return request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), standings(FETCHED_AT));
  }

  static Map<String, Object> request(
      int teamId,
      int leagueId,
      int seasonId,
      Map<String, Object> fixtures,
      Map<String, Object> standings) {
    return request(teamId, leagueId, seasonId, fixtures, standings, null);
  }

  static Map<String, Object> request(
      int teamId,
      int leagueId,
      int seasonId,
      Map<String, Object> fixtures,
      Map<String, Object> standings,
      Map<String, Object> playerStats) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("runId", "run-1");
    request.put("teamId", teamId);
    request.put("leagueId", leagueId);
    request.put("seasonId", seasonId);
    if (fixtures != null) {
      request.put("fixtures", fixtures);
    }
    if (standings != null) {
      request.put("standings", standings);
    }
    if (playerStats != null) {
      request.put("playerStats", playerStats);
    }
    return request;
  }

  // --- player statistics (KAN-72): made-up names and ids, surname first like the site ----------

  static final int COHEN_ID = 900001;
  static final int LEVI_ID = 900002;
  static final int MIZRAHI_ID = 900003;
  static final int PERETZ_ID = 900004;

  /** Surname first, as the site's table shows names. */
  static final String COHEN_NAME = "כהן דניאל";

  /** Three words: surname and two given names. */
  static final String LEVI_NAME = "לוי יוסף משה";

  static final String MIZRAHI_NAME = "מזרחי אבירם";
  static final String PERETZ_NAME = "פרץ עומר";

  /** Only the player statistics, OK, for Sderot. */
  static Map<String, Object> playerStatsOnly(Instant fetchedAt) {
    return request(SDEROT, LEAGUE, SEASON, null, null, playerStats(fetchedAt));
  }

  /** Three players; Mizrahi is registered but hasn't played (every row is kept). */
  static Map<String, Object> playerStats(Instant fetchedAt) {
    return playerStatsWith(
        fetchedAt,
        statsRow(COHEN_ID, COHEN_NAME, 10, 2, 1, 0, 0, 8, 2, 3, 712),
        statsRow(LEVI_ID, LEVI_NAME, 9, 0, 3, 1, 1, 9, 0, 4, 801),
        statsRow(MIZRAHI_ID, MIZRAHI_NAME, 0, 0, 0, 0, 0, 0, 0, 0, 0));
  }

  @SafeVarargs
  static Map<String, Object> playerStatsWith(Instant fetchedAt, Map<String, Object>... rows) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("status", "OK");
    part.put("fetchedAt", fetchedAt.toString());
    part.put("players", new ArrayList<>(Arrays.asList(rows)));
    return part;
  }

  /** A player with 3 games (2 starts, 1 as a sub). */
  static Map<String, Object> statsRow(int playerId, String name) {
    return statsRow(playerId, name, 3, 1, 0, 0, 0, 2, 1, 1, 200);
  }

  static Map<String, Object> statsRow(
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
      int minutes) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("playerId", playerId);
    row.put("name", name);
    row.put("games", games);
    row.put("goals", goals);
    row.put("yellowCardsLeagueAndCup", yellowCardsLeagueAndCup);
    row.put("yellowCardsToto", yellowCardsToto);
    row.put("redCards", redCards);
    row.put("starts", starts);
    row.put("cameOnAsSub", cameOnAsSub);
    row.put("subbedOff", subbedOff);
    row.put("minutes", minutes);
    return row;
  }

  /**
   * Sderot's games, in page order. The unscheduled game (no id, no time) comes before a timed game
   * on the same day, so sorting shows. Sderot: lost away 1-4, won home 2-1, drew away 1-1.
   */
  static Map<String, Object> fixtures(Instant fetchedAt) {
    return fixturesWith(
        fetchedAt,
        game(1122601, "2026-09-05", "20:00", TIRA_NAME, SDEROT_NAME, "טירה", 4, 1),
        game(1122602, "2026-09-12", "19:30", SDEROT_NAME, KIRYAT_MALACHI_NAME, "שדרות", 2, 1),
        game(1122603, "2026-09-19", "18:00", NIR_RAM_NAME, SDEROT_NAME, "דימונה", 1, 1),
        game(null, "2026-10-03", null, SDEROT_NAME, HADERA_NAME, null, null, null),
        game(1143937, "2026-10-03", "16:00", SDEROT_NAME, TIRA_NAME, "שדרות", null, null));
  }

  @SafeVarargs
  static Map<String, Object> fixturesWith(Instant fetchedAt, Map<String, Object>... games) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("status", "OK");
    part.put("fetchedAt", fetchedAt.toString());
    part.put("games", new ArrayList<>(Arrays.asList(games)));
    return part;
  }

  static Map<String, Object> game(
      Integer gameId,
      String date,
      String kickoffTime,
      String home,
      String away,
      String venue,
      Integer homeScore,
      Integer awayScore) {
    Map<String, Object> game = new LinkedHashMap<>();
    game.put("gameId", gameId);
    game.put("date", date);
    game.put("kickoffTime", kickoffTime);
    game.put("homeTeamName", home);
    game.put("awayTeamName", away);
    game.put("venue", venue);
    game.put("homeScore", homeScore);
    game.put("awayScore", awayScore);
    return game;
  }

  /** Five rows; Hadera has a points deduction (-1). */
  static Map<String, Object> standings(Instant fetchedAt) {
    return standingsWith(
        fetchedAt,
        row(1, TIRA, TIRA_NAME, 3, 2, 1, 0, 7, 2, 7),
        row(2, KIRYAT_MALACHI, KIRYAT_MALACHI_NAME, 3, 2, 0, 1, 6, 3, 6),
        row(3, SDEROT, SDEROT_NAME, 3, 1, 1, 1, 4, 6, 4),
        row(4, NIR_RAM, NIR_RAM_NAME, 3, 0, 2, 1, 3, 4, 2),
        row(5, HADERA, HADERA_NAME, 3, 0, 0, 3, 1, 9, -1));
  }

  @SafeVarargs
  static Map<String, Object> standingsWith(Instant fetchedAt, Map<String, Object>... rows) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("status", "OK");
    part.put("fetchedAt", fetchedAt.toString());
    part.put("round", 3);
    part.put("rows", new ArrayList<>(Arrays.asList(rows)));
    return part;
  }

  static Map<String, Object> row(
      int position,
      int teamId,
      String teamName,
      int played,
      int won,
      int drawn,
      int lost,
      int goalsFor,
      int goalsAgainst,
      int points) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("position", position);
    row.put("teamId", teamId);
    row.put("teamName", teamName);
    row.put("played", played);
    row.put("won", won);
    row.put("drawn", drawn);
    row.put("lost", lost);
    row.put("goalsFor", goalsFor);
    row.put("goalsAgainst", goalsAgainst);
    row.put("points", points);
    return row;
  }

  static Map<String, Object> failed(String reason, String detail) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("status", "FAILED");
    part.put("reason", reason);
    part.put("detail", detail);
    return part;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> games(Map<String, Object> request) {
    return (List<Map<String, Object>>) ((Map<String, Object>) request.get("fixtures")).get("games");
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> rows(Map<String, Object> request) {
    return (List<Map<String, Object>>) ((Map<String, Object>) request.get("standings")).get("rows");
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> statsRows(Map<String, Object> request) {
    return (List<Map<String, Object>>)
        ((Map<String, Object>) request.get("playerStats")).get("players");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> part(Map<String, Object> request, String name) {
    return (Map<String, Object>) request.get(name);
  }

  static String json(Object body) {
    return JSON.writeValueAsString(body);
  }
}
