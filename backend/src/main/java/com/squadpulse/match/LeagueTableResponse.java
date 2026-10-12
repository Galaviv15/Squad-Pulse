package com.squadpulse.match;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /matches/league-table}: the standings of the club's current league and season. Never
 * carries a team, league or season id (docs/spec.md section 09): our row is marked by {@code
 * ownTeam}.
 *
 * @param lastUpdatedAt when the shown table was scraped; {@code null} without one
 * @param round the round the table was requested for; {@code null} without data
 * @param rows by position
 */
public record LeagueTableResponse(
    MatchDataState state,
    Instant lastUpdatedAt,
    Integer round,
    MappingProblem mappingProblem,
    List<Row> rows) {

  /** {@code goalDifference} is derived: goals for minus against. */
  public record Row(
      int position,
      String teamName,
      int played,
      int won,
      int drawn,
      int lost,
      int goalsFor,
      int goalsAgainst,
      int goalDifference,
      int points,
      boolean ownTeam) {}
}
