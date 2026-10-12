package com.squadpulse.auth;

/**
 * Clubs and their football.org.il mappings for tests <b>outside</b> the {@code auth} package, which
 * can't reach the package-private {@link FootballOrgIlMapping}. Changes a mapping through the real
 * KAN-65 repository method. {@code @Import} it into a {@code @SpringBootTest}.
 */
public class TestClubMappings {

  private final ClubRepository clubRepository;

  TestClubMappings(ClubRepository clubRepository) {
    this.clubRepository = clubRepository;
  }

  /** A new club without a mapping; returns its id. */
  public String createClub(String name) {
    Club club = new Club();
    club.setName(name);
    return clubRepository.save(club).getId();
  }

  /** A new club mapped to these ids; returns its id. */
  public String createClub(String name, int teamId, int leagueId, int seasonId) {
    String clubId = createClub(name);
    map(clubId, teamId, leagueId, seasonId);
    return clubId;
  }

  /** Replaces the club's mapping, via {@link ClubRepository#updateFootballOrgIlByClubId}. */
  public void map(String clubId, int teamId, int leagueId, int seasonId) {
    clubRepository.updateFootballOrgIlByClubId(
        clubId, new FootballOrgIlMapping(teamId, leagueId, seasonId));
  }

  public void deleteAllClubs() {
    clubRepository.deleteAll();
  }
}
