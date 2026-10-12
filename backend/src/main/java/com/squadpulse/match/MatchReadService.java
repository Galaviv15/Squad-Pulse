package com.squadpulse.match;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.common.ClubContext;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The club's scraped fixtures and league table, read-only (KAN-66). Which team, league and season
 * to show comes only from the caller's own club mapping ({@link
 * ClubFootballMappings#currentClubMapping()}, by the clubId in {@link ClubContext}) — never from
 * the request. Snapshots stored for an earlier mapping (last season, another team or league) stay
 * in the database but are never shown.
 */
@Service
class MatchReadService {

  private static final Comparator<FixturesResponse.Game> GAME_ORDER =
      Comparator.comparing(FixturesResponse.Game::date)
          .thenComparing(
              FixturesResponse.Game::kickoffTime,
              Comparator.nullsLast(Comparator.<LocalTime>naturalOrder()));

  private final ClubFootballMappings mappings;
  private final LeagueFixturesSnapshotRepository fixturesRepository;
  private final LeagueStandingsSnapshotRepository standingsRepository;
  private final ClubContext clubContext;

  MatchReadService(
      ClubFootballMappings mappings,
      LeagueFixturesSnapshotRepository fixturesRepository,
      LeagueStandingsSnapshotRepository standingsRepository,
      ClubContext clubContext) {
    this.mappings = mappings;
    this.fixturesRepository = fixturesRepository;
    this.standingsRepository = standingsRepository;
    this.clubContext = clubContext;
  }

  FixturesResponse fixtures() {
    Optional<FootballOrgIlIds> mapping = mappings.currentClubMapping();
    if (mapping.isEmpty()) {
      return new FixturesResponse(MatchDataState.NOT_MAPPED, null, null, null, List.of());
    }
    FootballOrgIlIds ids = mapping.get();
    MappingProblem problem = mappingProblem(ids, standings(ids));
    Optional<LeagueFixturesSnapshot> snapshot =
        fixturesRepository
            .findByClubIdAndTeamIdAndSeasonId(
                clubContext.requireClubId(), ids.teamId(), ids.seasonId())
            .filter(found -> Objects.equals(found.getLeagueId(), ids.leagueId()));
    if (snapshot.isEmpty()) {
      return new FixturesResponse(MatchDataState.NO_DATA, null, null, problem, List.of());
    }
    String ownName = snapshot.get().getTeamName();
    List<FixturesResponse.Game> games =
        snapshot.get().getGames().stream()
            .map(game -> fromOurSide(game, ownName))
            .sorted(GAME_ORDER)
            .toList();
    return new FixturesResponse(
        MatchDataState.OK, snapshot.get().getFetchedAt(), ownName, problem, games);
  }

  LeagueTableResponse leagueTable() {
    Optional<FootballOrgIlIds> mapping = mappings.currentClubMapping();
    if (mapping.isEmpty()) {
      return new LeagueTableResponse(MatchDataState.NOT_MAPPED, null, null, null, List.of());
    }
    FootballOrgIlIds ids = mapping.get();
    Optional<LeagueStandingsSnapshot> standings = standings(ids);
    if (standings.isEmpty()) {
      return new LeagueTableResponse(MatchDataState.NO_DATA, null, null, null, List.of());
    }
    List<LeagueTableResponse.Row> rows =
        standings.get().getRows().stream()
            .sorted(Comparator.comparingInt(StoredStandingsRow::position))
            .map(
                row ->
                    new LeagueTableResponse.Row(
                        row.position(),
                        row.teamName(),
                        row.played(),
                        row.won(),
                        row.drawn(),
                        row.lost(),
                        row.goalsFor(),
                        row.goalsAgainst(),
                        row.goalsFor() - row.goalsAgainst(),
                        row.points(),
                        Objects.equals(row.teamId(), ids.teamId())))
            .toList();
    return new LeagueTableResponse(
        MatchDataState.OK,
        standings.get().getFetchedAt(),
        standings.get().getRound(),
        mappingProblem(ids, standings),
        rows);
  }

  private Optional<LeagueStandingsSnapshot> standings(FootballOrgIlIds ids) {
    return standingsRepository.findByLeagueIdAndSeasonId(ids.leagueId(), ids.seasonId());
  }

  private static MappingProblem mappingProblem(
      FootballOrgIlIds ids, Optional<LeagueStandingsSnapshot> standings) {
    return standings
        .filter(
            found ->
                found.getRows().stream()
                    .noneMatch(row -> Objects.equals(row.teamId(), ids.teamId())))
        .map(found -> MappingProblem.TEAM_NOT_IN_STANDINGS)
        .orElse(null);
  }

  private static FixturesResponse.Game fromOurSide(StoredGame game, String ownName) {
    boolean home = game.homeTeamName().equals(ownName);
    Integer ownScore = home ? game.homeScore() : game.awayScore();
    Integer opponentScore = home ? game.awayScore() : game.homeScore();
    FixturesResponse.Result result = null;
    if (ownScore != null && opponentScore != null) {
      int compared = Integer.compare(ownScore, opponentScore);
      result =
          compared > 0
              ? FixturesResponse.Result.WIN
              : compared == 0 ? FixturesResponse.Result.DRAW : FixturesResponse.Result.LOSS;
    }
    return new FixturesResponse.Game(
        game.localDate(),
        game.localKickoffTime(),
        home ? FixturesResponse.Side.HOME : FixturesResponse.Side.AWAY,
        home ? game.awayTeamName() : game.homeTeamName(),
        game.venue(),
        ownScore,
        opponentScore,
        result);
  }
}
