package com.squadpulse.match;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.common.ClubContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
 *
 * <p>Cup games (KAN-84) are merged into the fixtures: every competition's {@link
 * CupFixturesSnapshot} of the club for the current team and season (one query, no league filter: a
 * cup belongs to the team and season). Our side is the stored {@code ownSide} (no name matching for
 * cups), and their details are joined by {@code game_id} exactly like the league games'.
 */
@Service
class MatchReadService {

  /** Date, kickoff (unset last), league before cup; stable, so then each snapshot's order. */
  private static final Comparator<FixturesResponse.Game> GAME_ORDER =
      Comparator.comparing(FixturesResponse.Game::date)
          .thenComparing(
              FixturesResponse.Game::kickoffTime,
              Comparator.nullsLast(Comparator.<LocalTime>naturalOrder()))
          .thenComparing(FixturesResponse.Game::competition);

  private final ClubFootballMappings mappings;
  private final LeagueFixturesSnapshotRepository fixturesRepository;
  private final CupFixturesSnapshotRepository cupRepository;
  private final LeagueStandingsSnapshotRepository standingsRepository;
  private final KnownGames knownGames;
  private final ClubContext clubContext;

  MatchReadService(
      ClubFootballMappings mappings,
      LeagueFixturesSnapshotRepository fixturesRepository,
      CupFixturesSnapshotRepository cupRepository,
      LeagueStandingsSnapshotRepository standingsRepository,
      KnownGames knownGames,
      ClubContext clubContext) {
    this.mappings = mappings;
    this.fixturesRepository = fixturesRepository;
    this.cupRepository = cupRepository;
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
    String clubId = clubContext.requireClubId();
    MappingProblem problem = mappingProblem(ids, standings(ids));
    Optional<LeagueFixturesSnapshot> league =
        fixturesRepository
            .findByClubIdAndTeamIdAndSeasonId(clubId, ids.teamId(), ids.seasonId())
            .filter(found -> Objects.equals(found.getLeagueId(), ids.leagueId()));
    List<CupFixturesSnapshot> cups =
        cupRepository.findByClubIdAndTeamIdAndSeasonId(clubId, ids.teamId(), ids.seasonId());
    if (league.isEmpty() && cups.isEmpty()) {
      return new FixturesResponse(MatchDataState.NO_DATA, null, null, problem, List.of());
    }
    // One query per collection, joined by game_id in memory (KAN-79).
    Map<Integer, GameDetails> details = knownGames.stored(ids.teamId(), ids.seasonId());
    Set<Integer> givenUp = knownGames.givenUp(ids.teamId(), ids.seasonId());
    List<FixturesResponse.Game> games = new ArrayList<>();
    List<Instant> fetched = new ArrayList<>();
    league.ifPresent(
        snapshot -> {
          String ownName = snapshot.getTeamName();
          snapshot
              .getGames()
              .forEach(game -> games.add(fromLeague(game, ownName, details, givenUp)));
          fetched.add(snapshot.getFetchedAt());
        });
    cups.stream()
        .sorted(Comparator.comparing(CupFixturesSnapshot::getCompetition))
        .forEach(
            snapshot -> {
              FixturesResponse.Competition competition =
                  FixturesResponse.Competition.of(snapshot.getCompetition());
              snapshot
                  .getGames()
                  .forEach(game -> games.add(fromCup(game, competition, details, givenUp)));
              fetched.add(snapshot.getFetchedAt());
            });
    games.sort(GAME_ORDER); // List.sort is stable
    return new FixturesResponse(
        MatchDataState.OK,
        fetched.stream().min(Comparator.naturalOrder()).orElseThrow(),
        league.map(LeagueFixturesSnapshot::getTeamName).orElse(null),
        problem,
        List.copyOf(games));
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

  /** A league game: our side by our team's name, as before KAN-84. */
  private static FixturesResponse.Game fromLeague(
      StoredGame game, String ownName, Map<Integer, GameDetails> details, Set<Integer> givenUp) {
    boolean home = game.homeTeamName().equals(ownName);
    return fromOurSide(
        new OurSide(
            game.localDate(),
            game.localKickoffTime(),
            home,
            home ? game.awayTeamName() : game.homeTeamName(),
            game.venue(),
            home ? game.homeScore() : game.awayScore(),
            home ? game.awayScore() : game.homeScore(),
            game.gameId(),
            FixturesResponse.Competition.LEAGUE,
            null,
            MatchOutcome.ofScore(
                home ? game.homeScore() : game.awayScore(),
                home ? game.awayScore() : game.homeScore())),
        details,
        givenUp);
  }

  /** A cup game (KAN-84): our side as the scraper decided it; no name matching. */
  private static FixturesResponse.Game fromCup(
      StoredCupGame game,
      FixturesResponse.Competition competition,
      Map<Integer, GameDetails> details,
      Set<Integer> givenUp) {
    boolean home = game.ownSide() == FixturesResponse.Side.HOME;
    return fromOurSide(
        new OurSide(
            game.localDate(),
            game.localKickoffTime(),
            home,
            home ? game.awayTeamName() : game.homeTeamName(),
            game.venue(),
            home ? game.homeScore() : game.awayScore(),
            home ? game.awayScore() : game.homeScore(),
            game.gameId(),
            competition,
            game.round(),
            MatchOutcome.ofCup(game)),
        details,
        givenUp);
  }

  /** One game, league or cup, already turned to our side. */
  private record OurSide(
      LocalDate date,
      LocalTime kickoffTime,
      boolean home,
      String opponentName,
      String venue,
      Integer ownScore,
      Integer opponentScore,
      Integer gameId,
      FixturesResponse.Competition competition,
      String round,
      MatchOutcome outcome) {}

  /**
   * Played = <b>both 90-minute scores set</b>, never "has a result": a level shootout has scores
   * but no {@code result}, and is still played (its details state set).
   */
  private static FixturesResponse.Game fromOurSide(
      OurSide game, Map<Integer, GameDetails> details, Set<Integer> givenUp) {
    boolean played = game.ownScore() != null && game.opponentScore() != null;
    MatchOutcome outcome = game.outcome();
    GameDetails stored = game.gameId() == null ? null : details.get(game.gameId());
    FixturesResponse.DetailsState detailsState;
    if (stored != null) {
      detailsState = FixturesResponse.DetailsState.AVAILABLE;
    } else if (!played) {
      detailsState = null; // not played yet
    } else if (game.gameId() != null && givenUp.contains(game.gameId())) {
      detailsState = FixturesResponse.DetailsState.UNAVAILABLE;
    } else {
      detailsState = FixturesResponse.DetailsState.PENDING;
    }
    return new FixturesResponse.Game(
        game.date(),
        game.kickoffTime(),
        game.home() ? FixturesResponse.Side.HOME : FixturesResponse.Side.AWAY,
        game.opponentName(),
        game.venue(),
        game.ownScore(),
        game.opponentScore(),
        outcome.result(),
        stored == null ? null : stored.getId(),
        detailsState,
        game.competition(),
        game.round(),
        outcome.extraTime(),
        outcome.penalties(),
        outcome.decidedBy());
  }
}
