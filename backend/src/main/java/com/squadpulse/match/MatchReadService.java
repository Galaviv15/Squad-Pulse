package com.squadpulse.match;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.common.ClubContext;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The club's scraped fixtures and league table, read-only (KAN-66). Which team, league and season
 * to show comes only from the caller's own club mapping ({@link
 * ClubFootballMappings#currentClubMapping()}, by the clubId in {@link ClubContext}) — never from
 * the request. Snapshots stored for an earlier mapping (last season, another team or league) stay
 * in the database but are never shown.
 *
 * <p>Each played game of the fixtures carries whether its details (KAN-79) are there: joined by
 * {@code game_id} against the club's {@link GameDetails} and given-up {@link GameDetailAttempts}
 * for the same team and season, one query per collection per read. The {@code game_id} itself is
 * never shown; the details' opaque id is.
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
  private final KnownGames knownGames;
  private final ClubContext clubContext;

  MatchReadService(
      ClubFootballMappings mappings,
      LeagueFixturesSnapshotRepository fixturesRepository,
      LeagueStandingsSnapshotRepository standingsRepository,
      KnownGames knownGames,
      ClubContext clubContext) {
    this.mappings = mappings;
    this.fixturesRepository = fixturesRepository;
    this.standingsRepository = standingsRepository;
    this.knownGames = knownGames;
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
    // One query per collection, joined by game_id in memory (KAN-79).
    Map<Integer, GameDetails> details = knownGames.stored(ids.teamId(), ids.seasonId());
    Set<Integer> givenUp = knownGames.givenUp(ids.teamId(), ids.seasonId());
    List<FixturesResponse.Game> games =
        snapshot.get().getGames().stream()
            .map(game -> fromOurSide(game, ownName, details, givenUp))
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

  private static FixturesResponse.Game fromOurSide(
      StoredGame game, String ownName, Map<Integer, GameDetails> details, Set<Integer> givenUp) {
    boolean home = game.homeTeamName().equals(ownName);
    Integer ownScore = home ? game.homeScore() : game.awayScore();
    Integer opponentScore = home ? game.awayScore() : game.homeScore();
    FixturesResponse.Result result = FixturesResponse.Result.of(ownScore, opponentScore);
    GameDetails stored = game.gameId() == null ? null : details.get(game.gameId());
    FixturesResponse.DetailsState detailsState;
    if (stored != null) {
      detailsState = FixturesResponse.DetailsState.AVAILABLE;
    } else if (result == null) {
      detailsState = null; // not played yet
    } else if (game.gameId() != null && givenUp.contains(game.gameId())) {
      detailsState = FixturesResponse.DetailsState.UNAVAILABLE;
    } else {
      detailsState = FixturesResponse.DetailsState.PENDING;
    }
    return new FixturesResponse.Game(
        game.localDate(),
        game.localKickoffTime(),
        home ? FixturesResponse.Side.HOME : FixturesResponse.Side.AWAY,
        home ? game.awayTeamName() : game.homeTeamName(),
        game.venue(),
        ownScore,
        opponentScore,
        result,
        stored == null ? null : stored.getId(),
        detailsState);
  }
}
