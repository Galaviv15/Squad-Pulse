package com.squadpulse.auth;

/**
 * A read-only copy of a club's football.org.il mapping (KAN-66), for the modules that need it
 * outside {@code auth}: which team, league and season the club is. Handed out only by {@link
 * ClubFootballMappings}, to server-side code; never put it in an API response (docs/spec.md section
 * 09).
 */
public record FootballOrgIlIds(int teamId, int leagueId, int seasonId) {

  static FootballOrgIlIds of(FootballOrgIlMapping mapping) {
    return new FootballOrgIlIds(mapping.teamId(), mapping.leagueId(), mapping.seasonId());
  }
}
