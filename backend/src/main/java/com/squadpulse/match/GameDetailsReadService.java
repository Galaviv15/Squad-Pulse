package com.squadpulse.match;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.NotFoundException;
import com.squadpulse.squad.Player;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * One game's details as the club sees them, read-only (KAN-79). The record is found by our opaque
 * id through the club-scoped repository, so another club's record is a 404, the same as one that
 * doesn't exist (never a 403). It must also belong to the club's <b>current</b> mapping's team and
 * season ({@link ClubFootballMappings#currentClubMapping()}): records of an earlier mapping stay in
 * the database but are never shown, like the snapshots. Linked players come from the KAN-72 links
 * ({@link PlayerStatsReadService#linkedPlayers()}), with the same rule for a link to a deleted
 * player.
 *
 * <p>The one thing it reads besides (KAN-84): the club's cup snapshots for the current team and
 * season (club-scoped, one query), so a cup game gets its {@code result}, {@code extraTime}, {@code
 * penalties} and {@code decidedBy} by the same rule as on {@code GET /matches/fixtures} ({@link
 * MatchOutcome}), and (KAN-86) its {@code competitionType} and {@code cupRound}. Nothing else of
 * the read changes.
 */
@Service
class GameDetailsReadService {

  private static final Comparator<GameDetails.StoredAppearance> ROLE_ORDER =
      Comparator.comparing(GameDetails.StoredAppearance::role);

  private final ClubFootballMappings mappings;
  private final GameDetailsRepository repository;
  private final PlayerStatsReadService playerStatsReadService;
  private final CupFixturesSnapshotRepository cupRepository;
  private final ClubContext clubContext;

  GameDetailsReadService(
      ClubFootballMappings mappings,
      GameDetailsRepository repository,
      PlayerStatsReadService playerStatsReadService,
      CupFixturesSnapshotRepository cupRepository,
      ClubContext clubContext) {
    this.mappings = mappings;
    this.repository = repository;
    this.playerStatsReadService = playerStatsReadService;
    this.cupRepository = cupRepository;
    this.clubContext = clubContext;
  }

  /**
   * @throws NotFoundException if the club isn't mapped, or has no such record for its current team
   *     and season
   */
  GameDetailsResponse read(String id) {
    FootballOrgIlIds ids =
        mappings.currentClubMapping().orElseThrow(GameDetailsReadService::notFound);
    GameDetails details =
        repository
            .findById(id)
            .filter(found -> Objects.equals(found.getTeamId(), ids.teamId()))
            .filter(found -> Objects.equals(found.getSeasonId(), ids.seasonId()))
            .orElseThrow(GameDetailsReadService::notFound);

    boolean home = details.getOwnSide() == FixturesResponse.Side.HOME;
    int ownScore = home ? details.getHomeScore() : details.getAwayScore();
    int opponentScore = home ? details.getAwayScore() : details.getHomeScore();
    CupMatch cup = cupGame(ids, details.getGameId());
    MatchOutcome outcome =
        cup == null
            ? MatchOutcome.ofScore(ownScore, opponentScore)
            : MatchOutcome.ofCup(cup.game());
    Map<Integer, Player> linked = playerStatsReadService.linkedPlayers();
    List<GameDetailsResponse.Appearance> players =
        details.getAppearances().stream()
            .sorted(ROLE_ORDER) // stable: within a role, as sent
            .map(appearance -> toResponse(appearance, linked.get(appearance.sourcePlayerId())))
            .toList();
    return new GameDetailsResponse(
        details.getId(),
        details.localDate(),
        details.localKickoffTime(),
        details.getCompetition(),
        details.getRound(),
        details.getOwnSide(),
        home ? details.getAwayTeamName() : details.getHomeTeamName(),
        ownScore,
        opponentScore,
        outcome.result(),
        outcome.extraTime(),
        outcome.penalties(),
        outcome.decidedBy(),
        cup == null ? null : FixturesResponse.Competition.of(cup.competition()),
        cup == null ? null : cup.game().round(),
        details.getFetchedAt(),
        players);
  }

  /**
   * The club's current cup snapshots' game with this {@code gameId} (KAN-84), with its snapshot's
   * competition, or {@code null} (a league game, or a cup game no longer in the current snapshots).
   * One club-scoped query; its game gives the outcome by the shared rule ({@link
   * MatchOutcome#ofCup}) and, since KAN-86, the competition and the cup round.
   */
  private CupMatch cupGame(FootballOrgIlIds ids, Integer gameId) {
    return cupRepository
        .findByClubIdAndTeamIdAndSeasonId(clubContext.requireClubId(), ids.teamId(), ids.seasonId())
        .stream()
        .flatMap(
            snapshot ->
                snapshot.getGames().stream()
                    .map(game -> new CupMatch(snapshot.getCompetition(), game)))
        .filter(match -> Objects.equals(match.game().gameId(), gameId))
        .findFirst()
        .orElse(null);
  }

  /** A cup game found by {@link #cupGame}, with the competition of the snapshot it's in. */
  private record CupMatch(CupCompetition competition, StoredCupGame game) {}

  private static GameDetailsResponse.Appearance toResponse(
      GameDetails.StoredAppearance appearance, Player linkedPlayer) {
    return new GameDetailsResponse.Appearance(
        appearance.name(),
        linkedPlayer == null ? null : PlayerStatsResponse.PlayerRef.of(linkedPlayer),
        appearance.shirtNumber(),
        appearance.role(),
        appearance.goalkeeper(),
        appearance.captain(),
        appearance.events().stream()
            .map(event -> new GameDetailsResponse.Event(event.kind(), event.minute()))
            .toList());
  }

  private static NotFoundException notFound() {
    return new NotFoundException("Game details not found");
  }
}
