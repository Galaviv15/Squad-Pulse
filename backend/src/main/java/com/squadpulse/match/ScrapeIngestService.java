package com.squadpulse.match;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.common.ClubContext;
import com.squadpulse.match.IngestResult.CompetitionResult;
import com.squadpulse.match.IngestResult.CupGamesResult;
import com.squadpulse.match.IngestResult.PartResult;
import com.squadpulse.match.IngestResult.RejectionReason;
import com.squadpulse.match.IngestResult.Warning;
import com.squadpulse.match.IngestResult.WarningCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Applies one scrape's successful parts to the stored snapshots (KAN-66). The only writer of {@link
 * LeagueStandingsSnapshot}, {@link LeagueFixturesSnapshot}, {@link PlayerSeasonStatsSnapshot}
 * (KAN-72), {@link CupFixturesSnapshot} (KAN-84), {@link GameDetails} and {@link
 * GameDetailAttempts} (KAN-79), called only by the service-token ingest endpoint in {@code
 * scrapingintegration}, which validated the input and handles the parts the scraper reported as
 * failed. See docs/scraper-backend-contract.md.
 *
 * <p>Order: the standings first, so the fixtures can be resolved against them; then the fixtures;
 * then the player statistics; then the cup games; then the game details. The parts are independent
 * — one rejected doesn't stop another.
 *
 * <ul>
 *   <li><b>Standings</b>: stored only if some club is currently mapped to the league and season
 *       ({@link RejectionReason#NOT_MAPPED} otherwise, so no caller can fill the shared collection
 *       with arbitrary leagues).
 *   <li><b>Fixtures</b>: written for every club currently mapped to exactly the scrape's team,
 *       league and season — found from those ids, never from a clubId in the request — each inside
 *       {@link ClubContext#callAs}, so the club-scoped repository does the write. No such club →
 *       {@code NOT_MAPPED}; a home / away pairing twice → {@code DUPLICATE_GAME}; our team's name
 *       unknown → {@code OWN_TEAM_UNKNOWN}; a game without our team → {@code INVALID}.
 *   <li><b>Team names</b> are resolved to ids by exact match against the same league's standings:
 *       the newest table — this scrape's if it was just stored, otherwise the stored one (a stale
 *       table in the scrape is older by definition). Our team's name is that of the row with the
 *       configured team id; without such a row, the one name in every game. An unknown name keeps a
 *       {@code null} id ({@link WarningCode#UNRESOLVED_TEAM_NAMES}); standings that lack the
 *       configured team are stored anyway ({@link WarningCode#TEAM_NOT_IN_STANDINGS}, logged at
 *       WARN with ids only).
 *   <li><b>Player statistics</b> (KAN-72): written like the fixtures — for every club mapped to
 *       exactly the scrape's three ids, each inside {@link ClubContext#callAs}, the whole list
 *       replacing the club's snapshot; no such club → {@code NOT_MAPPED}. Every row keeps the
 *       opaque id it had in the stored snapshot (by {@code player_id}); only a new {@code
 *       player_id} gets a new random one. Never touches a {@code squad.Player} or a link. Logged
 *       with ids and counts only, never a player's name.
 *   <li><b>Game details</b> (KAN-79): our side of each game the scraper read, for every club mapped
 *       to exactly the scrape's three ids, each inside {@link ClubContext#callAs}; no such club →
 *       {@code NOT_MAPPED}, otherwise {@code STORED}. <b>Per game, not a snapshot</b>: a game
 *       creates its own record or replaces that one record only, and only with a newer {@code
 *       fetchedAt} (otherwise it's skipped silently, and the part is still {@code STORED}); the
 *       record keeps its opaque id; other games' records are never touched. Storing a game deletes
 *       its {@link GameDetailAttempts}. Each failed game the scraper reports counts as an attempt
 *       if the page was broken ({@code PARSE_ERROR}, {@code SOURCE_ERROR}), not if the site or the
 *       network was ({@code TIMEOUT}, {@code SOURCE_BLOCKED}: only its last reason and time
 *       change); a failure for a game whose details are stored, or one not newer than the last
 *       recorded attempt (a replay), is ignored. A given-up game is retried {@code
 *       GameDetailAttempts.RETRY_GIVEN_UP_AFTER} after its last counted failure. Never writes a
 *       {@code squad.Player}. Logged with ids and counts only: never a player's or team's name,
 *       never the scraper's {@code detail} above DEBUG.
 *   <li><b>Cup games</b> (KAN-84): for every club mapped to exactly the scrape's three ids, each
 *       inside {@link ClubContext#callAs}; no such club → every competition {@code NOT_MAPPED}.
 *       <b>Per competition, independently</b>: a competition the scraper reported {@code FAILED}
 *       never reaches here (the caller handles it; its stored games stay). A round / home / away
 *       twice in one competition's list → that competition {@code DUPLICATE_GAME}, never a 400 (it
 *       would lose the request's other parts). An empty list while the club's stored snapshot of
 *       that competition has games → {@code EMPTY_REPLACES_GAMES} for that club, the games stay
 *       (checked per club, after staleness). Otherwise the whole list replaces the competition's
 *       snapshot. No ownership check, no team-name resolution and no team ids for cups: our side is
 *       the scraper's {@code ownSide}. Never touches the league snapshot. Logged with ids,
 *       competition and counts only: never a team name or a round.
 *   <li><b>Staleness</b>: a part whose {@code fetchedAt} isn't newer than the stored snapshot's is
 *       {@link IngestResult.Outcome#STALE}, nothing written — so neither a late old scrape nor a
 *       replay ever overwrites newer data. Load, compare, save, with {@code @Version}: on a lost
 *       race (an optimistic-lock failure, or a duplicate key on the first insert) it re-reads once
 *       and compares again.
 * </ul>
 */
@Service
public class ScrapeIngestService {

  private static final Logger log = LoggerFactory.getLogger(ScrapeIngestService.class);

  private final LeagueStandingsSnapshotRepository standingsRepository;
  private final LeagueFixturesSnapshotRepository fixturesRepository;
  private final PlayerSeasonStatsSnapshotRepository playerStatsRepository;
  private final GameDetailsRepository gameDetailsRepository;
  private final GameDetailAttemptsRepository attemptsRepository;
  private final CupFixturesSnapshotRepository cupRepository;
  private final ClubFootballMappings mappings;
  private final ClubContext clubContext;
  private final Clock clock;

  @Autowired
  ScrapeIngestService(
      LeagueStandingsSnapshotRepository standingsRepository,
      LeagueFixturesSnapshotRepository fixturesRepository,
      PlayerSeasonStatsSnapshotRepository playerStatsRepository,
      GameDetailsRepository gameDetailsRepository,
      GameDetailAttemptsRepository attemptsRepository,
      CupFixturesSnapshotRepository cupRepository,
      ClubFootballMappings mappings,
      ClubContext clubContext) {
    this(
        standingsRepository,
        fixturesRepository,
        playerStatsRepository,
        gameDetailsRepository,
        attemptsRepository,
        cupRepository,
        mappings,
        clubContext,
        Clock.systemUTC());
  }

  /** For tests: {@code clock} sets {@code storedAt}. */
  ScrapeIngestService(
      LeagueStandingsSnapshotRepository standingsRepository,
      LeagueFixturesSnapshotRepository fixturesRepository,
      PlayerSeasonStatsSnapshotRepository playerStatsRepository,
      GameDetailsRepository gameDetailsRepository,
      GameDetailAttemptsRepository attemptsRepository,
      CupFixturesSnapshotRepository cupRepository,
      ClubFootballMappings mappings,
      ClubContext clubContext,
      Clock clock) {
    this.standingsRepository = standingsRepository;
    this.fixturesRepository = fixturesRepository;
    this.playerStatsRepository = playerStatsRepository;
    this.gameDetailsRepository = gameDetailsRepository;
    this.attemptsRepository = attemptsRepository;
    this.cupRepository = cupRepository;
    this.mappings = mappings;
    this.clubContext = clubContext;
    this.clock = clock;
  }

  /**
   * @param fixtures {@code null} if this scrape has no successful fixtures part
   * @param standings {@code null} if this scrape has no successful standings part
   * @param playerStats {@code null} if this scrape has no successful player statistics part
   * @param gameDetails {@code null} if this scrape has no successful game-details part
   * @param cupGames {@code null} if this scrape has no successful cup-games part (KAN-84); only its
   *     {@code OK} competitions
   */
  public IngestResult ingest(
      int teamId,
      int leagueId,
      int seasonId,
      ScrapedFixtures fixtures,
      ScrapedStandings standings,
      ScrapedPlayerStats playerStats,
      ScrapedGameDetails gameDetails,
      ScrapedCupGames cupGames) {
    List<Warning> warnings = new ArrayList<>();

    PartResult standingsResult = PartResult.NOT_SENT;
    if (standings != null) {
      standingsResult =
          mappings.anyClubMappedTo(leagueId, seasonId)
              ? storeStandings(leagueId, seasonId, standings)
              : PartResult.rejected(RejectionReason.NOT_MAPPED);
    }

    // The table to resolve names against: the newest one. This scrape's if it was just stored;
    // otherwise (not sent, stale — the stored one is newer by definition — or rejected) the
    // stored one, if any.
    List<StoredStandingsRow> table =
        standingsResult.outcome() == IngestResult.Outcome.STORED
            ? toRows(standings)
            : standingsRepository
                .findByLeagueIdAndSeasonId(leagueId, seasonId)
                .map(LeagueStandingsSnapshot::getRows)
                .orElse(null);
    if (table != null && table.stream().noneMatch(row -> isTeam(row, teamId))) {
      warnings.add(new Warning(WarningCode.TEAM_NOT_IN_STANDINGS, null));
      log.warn(
          "Team {} is not in the standings of league {} season {}: the club mapping is probably"
              + " wrong",
          teamId,
          leagueId,
          seasonId);
    }

    int clubsUpdated = 0;
    PartResult fixturesResult = PartResult.NOT_SENT;
    if (fixtures != null) {
      FixturesOutcome outcome =
          ingestFixtures(teamId, leagueId, seasonId, fixtures, table, warnings);
      fixturesResult = outcome.result();
      clubsUpdated = outcome.clubsUpdated();
    }

    PartResult playerStatsResult =
        playerStats == null
            ? PartResult.NOT_SENT
            : ingestPlayerStats(teamId, leagueId, seasonId, playerStats);
    CupGamesResult cupGamesResult =
        cupGames == null
            ? CupGamesResult.NOT_SENT
            : ingestCupGames(teamId, leagueId, seasonId, cupGames);
    PartResult gameDetailsResult =
        gameDetails == null
            ? PartResult.NOT_SENT
            : ingestGameDetails(teamId, leagueId, seasonId, gameDetails);
    return new IngestResult(
        fixturesResult,
        standingsResult,
        playerStatsResult,
        gameDetailsResult,
        cupGamesResult,
        clubsUpdated,
        List.copyOf(warnings));
  }

  /** How one club's copy of one cup competition went. */
  private enum CupClubOutcome {
    WRITTEN,
    STALE,
    /** Empty list over stored games: {@link RejectionReason#EMPTY_REPLACES_GAMES}. */
    REFUSED_EMPTY
  }

  private CupGamesResult ingestCupGames(
      int teamId, int leagueId, int seasonId, ScrapedCupGames cupGames) {
    List<String> clubIds = mappings.clubIdsMappedTo(teamId, leagueId, seasonId);
    if (clubIds.isEmpty()) {
      return new CupGamesResult(
          PartResult.rejected(RejectionReason.NOT_MAPPED),
          cupGames.competitions().stream()
              .map(
                  competition ->
                      new CompetitionResult(
                          competition.competition(),
                          PartResult.rejected(RejectionReason.NOT_MAPPED)))
              .toList());
    }
    List<CompetitionResult> results = new ArrayList<>();
    for (ScrapedCupGames.Competition competition : cupGames.competitions()) {
      PartResult result =
          ingestCupCompetition(teamId, seasonId, cupGames.fetchedAt(), competition, clubIds);
      log.info(
          "Cup games {} for team {} season {}: {} games, {}{}",
          competition.competition(),
          teamId,
          seasonId,
          competition.games().size(),
          result.outcome(),
          result.reason() == null ? "" : " (" + result.reason() + ")");
      results.add(new CompetitionResult(competition.competition(), result));
    }
    return new CupGamesResult(PartResult.STORED, List.copyOf(results));
  }

  /**
   * One competition for every mapped club. A club that refused an empty list makes the competition
   * {@code REJECTED} even if another club stored it (both mapped to the same team, so it shouldn't
   * happen): the failure must be visible.
   */
  private PartResult ingestCupCompetition(
      int teamId,
      int seasonId,
      Instant fetchedAt,
      ScrapedCupGames.Competition competition,
      List<String> clubIds) {
    List<StoredCupGame> games = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (ScrapedCupGames.Game game : competition.games()) {
      String key = StoredCupGame.keyOf(game.round(), game.homeTeamName(), game.awayTeamName());
      if (!keys.add(key)) {
        // One game would silently overwrite the other: reject this competition, visibly.
        return PartResult.rejected(RejectionReason.DUPLICATE_GAME);
      }
      games.add(
          new StoredCupGame(
              key,
              game.gameId(),
              game.round(),
              game.date().toString(),
              game.kickoffTime() == null ? null : game.kickoffTime().toString(),
              game.homeTeamName(),
              game.awayTeamName(),
              game.ownSide(),
              game.venue(),
              game.homeScore(),
              game.awayScore(),
              game.extraTime(),
              game.penalties()));
    }
    int written = 0;
    boolean refused = false;
    for (String clubId : clubIds) {
      CupClubOutcome outcome =
          clubContext.callAs(
              clubId,
              () ->
                  storeCupCompetition(
                      teamId, seasonId, competition.competition(), fetchedAt, games));
      if (outcome == CupClubOutcome.WRITTEN) {
        written++;
      } else if (outcome == CupClubOutcome.REFUSED_EMPTY) {
        refused = true;
      }
    }
    if (refused) {
      return PartResult.rejected(RejectionReason.EMPTY_REPLACES_GAMES);
    }
    return written > 0 ? PartResult.STORED : PartResult.STALE;
  }

  /**
   * In the current club's context. Staleness first (an old replay is {@code STALE}, whatever it
   * holds); then an empty list over stored games is refused, re-checked on every attempt of {@link
   * #storeIfNewer} against the copy it loaded.
   */
  private CupClubOutcome storeCupCompetition(
      int teamId,
      int seasonId,
      CupCompetition competition,
      Instant fetchedAt,
      List<StoredCupGame> games) {
    String clubId = clubContext.requireClubId();
    boolean[] refusedEmpty = {false};
    boolean written =
        storeIfNewer(
            () ->
                cupRepository.findByClubIdAndTeamIdAndSeasonIdAndCompetition(
                    clubId, teamId, seasonId, competition),
            CupFixturesSnapshot::getFetchedAt,
            CupFixturesSnapshot::new,
            snapshot -> {
              refusedEmpty[0] =
                  games.isEmpty() && snapshot.getGames() != null && !snapshot.getGames().isEmpty();
              if (refusedEmpty[0]) {
                return;
              }
              snapshot.setTeamId(teamId);
              snapshot.setSeasonId(seasonId);
              snapshot.setCompetition(competition);
              snapshot.setFetchedAt(fetchedAt);
              snapshot.setStoredAt(clock.instant());
              snapshot.setGames(games);
              cupRepository.save(snapshot);
            },
            fetchedAt);
    if (!written) {
      return CupClubOutcome.STALE;
    }
    return refusedEmpty[0] ? CupClubOutcome.REFUSED_EMPTY : CupClubOutcome.WRITTEN;
  }

  /** How one club's copy of a game-details part went: counts only. */
  private record GameDetailsCounts(int stored, int failuresCounted) {}

  private PartResult ingestGameDetails(
      int teamId, int leagueId, int seasonId, ScrapedGameDetails gameDetails) {
    List<String> clubIds = mappings.clubIdsMappedTo(teamId, leagueId, seasonId);
    if (clubIds.isEmpty()) {
      return PartResult.rejected(RejectionReason.NOT_MAPPED);
    }
    for (ScrapedGameDetails.Failure failure : gameDetails.failures()) {
      log.debug(
          "Scraper's detail for the failed game {} ({}): {}",
          failure.gameId(),
          failure.reason(),
          failure.detail());
    }
    for (String clubId : clubIds) {
      GameDetailsCounts counts =
          clubContext.callAs(clubId, () -> storeGameDetails(teamId, seasonId, gameDetails));
      log.info(
          "Game details for team {} season {}: {} games sent, {} stored; {} failures sent, {}"
              + " counted as attempts",
          teamId,
          seasonId,
          gameDetails.games().size(),
          counts.stored(),
          gameDetails.failures().size(),
          counts.failuresCounted());
    }
    return PartResult.STORED;
  }

  /** In the current club's context: each game on its own, then each failure. */
  private GameDetailsCounts storeGameDetails(
      int teamId, int seasonId, ScrapedGameDetails gameDetails) {
    String clubId = clubContext.requireClubId();
    Instant fetchedAt = gameDetails.fetchedAt();
    int stored = 0;
    for (ScrapedGameDetails.Game game : gameDetails.games()) {
      boolean written =
          storeIfNewer(
              () ->
                  gameDetailsRepository.findByClubIdAndTeamIdAndSeasonIdAndGameId(
                      clubId, teamId, seasonId, game.gameId()),
              GameDetails::getFetchedAt,
              GameDetails::create,
              details -> {
                details.setTeamId(teamId);
                details.setSeasonId(seasonId);
                details.setGameId(game.gameId());
                details.setFetchedAt(fetchedAt);
                details.setStoredAt(clock.instant());
                details.setDate(game.date());
                details.setKickoffTime(game.kickoffTime());
                details.setCompetition(game.competition());
                details.setRound(game.round());
                details.setOwnSide(game.ownSide());
                details.setHomeTeamName(game.homeTeamName());
                details.setAwayTeamName(game.awayTeamName());
                details.setHomeScore(game.homeScore());
                details.setAwayScore(game.awayScore());
                details.setAppearances(toAppearances(game.players()));
                gameDetailsRepository.save(details);
              },
              fetchedAt);
      if (written) {
        stored++;
        attemptsRepository.deleteByClubIdAndTeamIdAndSeasonIdAndGameId(
            clubId, teamId, seasonId, game.gameId());
      }
    }
    int counted = 0;
    for (ScrapedGameDetails.Failure failure : gameDetails.failures()) {
      if (recordFailedAttempt(clubId, teamId, seasonId, fetchedAt, failure)) {
        counted++;
      }
    }
    return new GameDetailsCounts(stored, counted);
  }

  /**
   * Records one failed game in the current club's context, unless its details are stored (then it's
   * ignored) or this scrape isn't newer than the last recorded attempt (a replay).
   *
   * @return true if it counted towards giving the game up
   */
  private boolean recordFailedAttempt(
      String clubId,
      int teamId,
      int seasonId,
      Instant fetchedAt,
      ScrapedGameDetails.Failure failure) {
    if (gameDetailsRepository
        .findByClubIdAndTeamIdAndSeasonIdAndGameId(clubId, teamId, seasonId, failure.gameId())
        .isPresent()) {
      return false;
    }
    boolean counts = failure.reason().countsAsAttempt();
    boolean written =
        storeIfNewer(
            () ->
                attemptsRepository.findByClubIdAndTeamIdAndSeasonIdAndGameId(
                    clubId, teamId, seasonId, failure.gameId()),
            GameDetailAttempts::getLastAttemptAt,
            GameDetailAttempts::new,
            attempts -> {
              attempts.setTeamId(teamId);
              attempts.setSeasonId(seasonId);
              attempts.setGameId(failure.gameId());
              if (counts) {
                attempts.setFailedAttempts(attempts.getFailedAttempts() + 1);
                attempts.setLastCountedAttemptAt(fetchedAt);
              }
              attempts.setLastReason(failure.reason());
              attempts.setLastAttemptAt(fetchedAt);
              attemptsRepository.save(attempts);
            },
            fetchedAt);
    return written && counts;
  }

  /** As sent, each player's events sorted by minute, an unknown minute last. */
  private static List<GameDetails.StoredAppearance> toAppearances(
      List<ScrapedGameDetails.Appearance> players) {
    return players.stream()
        .map(
            player ->
                new GameDetails.StoredAppearance(
                    player.playerId(),
                    player.name(),
                    player.shirtNumber(),
                    player.role(),
                    player.goalkeeper(),
                    player.captain(),
                    player.events().stream()
                        .sorted(
                            Comparator.comparing(
                                ScrapedGameDetails.Event::minute,
                                Comparator.nullsLast(Comparator.<Integer>naturalOrder())))
                        .map(event -> new GameDetails.StoredEvent(event.kind(), event.minute()))
                        .toList()))
        .toList();
  }

  private PartResult ingestPlayerStats(
      int teamId, int leagueId, int seasonId, ScrapedPlayerStats playerStats) {
    List<String> clubIds = mappings.clubIdsMappedTo(teamId, leagueId, seasonId);
    if (clubIds.isEmpty()) {
      return PartResult.rejected(RejectionReason.NOT_MAPPED);
    }
    int stored = 0;
    for (String clubId : clubIds) {
      if (clubContext.callAs(clubId, () -> storePlayerStats(teamId, seasonId, playerStats))) {
        stored++;
      }
    }
    log.info(
        "Player stats for team {} season {}: {} players, stored for {} of {} clubs",
        teamId,
        seasonId,
        playerStats.players().size(),
        stored,
        clubIds.size());
    return stored > 0 ? PartResult.STORED : PartResult.STALE;
  }

  /** In the current club's context. True if written, false if stale. */
  private boolean storePlayerStats(int teamId, int seasonId, ScrapedPlayerStats playerStats) {
    String clubId = clubContext.requireClubId();
    return storeIfNewer(
        () -> playerStatsRepository.findByClubIdAndTeamIdAndSeasonId(clubId, teamId, seasonId),
        PlayerSeasonStatsSnapshot::getFetchedAt,
        PlayerSeasonStatsSnapshot::new,
        snapshot -> {
          snapshot.setTeamId(teamId);
          snapshot.setSeasonId(seasonId);
          snapshot.setFetchedAt(playerStats.fetchedAt());
          snapshot.setStoredAt(clock.instant());
          snapshot.setPlayers(withOpaqueIds(playerStats.players(), snapshot.getPlayers()));
          playerStatsRepository.save(snapshot);
        },
        playerStats.fetchedAt());
  }

  /**
   * The scraped rows with our opaque ids: the id a {@code player_id} already has in the stored
   * snapshot (the one this write replaces, re-read on a retry), else a new random one. So a link
   * request that arrives during a refresh still finds its row.
   */
  private static List<StoredPlayerStats> withOpaqueIds(
      List<ScrapedPlayerStats.Row> rows, List<StoredPlayerStats> stored) {
    Map<Integer, String> idsBySource = new HashMap<>();
    if (stored != null) {
      stored.forEach(row -> idsBySource.put(row.sourcePlayerId(), row.opaqueId()));
    }
    return rows.stream()
        .map(
            row ->
                new StoredPlayerStats(
                    Optional.ofNullable(idsBySource.get(row.playerId()))
                        .orElseGet(() -> UUID.randomUUID().toString()),
                    row.playerId(),
                    row.name(),
                    row.games(),
                    row.goals(),
                    row.yellowCardsLeagueAndCup(),
                    row.yellowCardsToto(),
                    row.redCards(),
                    row.starts(),
                    row.cameOnAsSub(),
                    row.subbedOff(),
                    row.minutes()))
        .toList();
  }

  private record FixturesOutcome(PartResult result, int clubsUpdated) {

    static FixturesOutcome rejected(RejectionReason reason) {
      return new FixturesOutcome(PartResult.rejected(reason), 0);
    }
  }

  private FixturesOutcome ingestFixtures(
      int teamId,
      int leagueId,
      int seasonId,
      ScrapedFixtures fixtures,
      List<StoredStandingsRow> table,
      List<Warning> warnings) {
    List<String> clubIds = mappings.clubIdsMappedTo(teamId, leagueId, seasonId);
    if (clubIds.isEmpty()) {
      return FixturesOutcome.rejected(RejectionReason.NOT_MAPPED);
    }

    // The key assumes the page lists league games only, where each home / away pairing occurs
    // once a season (scraper/docs/KAN-64-findings.md). It does today, but the league table carries
    // a "table-w-playoff" class: a playoff stage, or a cup game ever listed here, could repeat a
    // pairing. Then one game would silently overwrite the other, so the whole part is rejected
    // instead, visibly.
    Set<String> keys = new HashSet<>();
    for (ScrapedFixtures.Game game : fixtures.games()) {
      if (!keys.add(StoredGame.keyOf(game.homeTeamName(), game.awayTeamName()))) {
        return FixturesOutcome.rejected(RejectionReason.DUPLICATE_GAME);
      }
    }

    Optional<String> ownTeamName =
        Optional.ofNullable(table).stream()
            .flatMap(List::stream)
            .filter(row -> isTeam(row, teamId))
            .map(StoredStandingsRow::teamName)
            .findFirst()
            .or(() -> nameInEveryGame(fixtures.games()));
    if (ownTeamName.isEmpty()) {
      return FixturesOutcome.rejected(RejectionReason.OWN_TEAM_UNKNOWN);
    }
    String ownName = ownTeamName.get();
    // The page is our team's own list: a game without it means the scrape read something else.
    if (fixtures.games().stream()
        .anyMatch(g -> !g.homeTeamName().equals(ownName) && !g.awayTeamName().equals(ownName))) {
      return FixturesOutcome.rejected(RejectionReason.INVALID);
    }

    Map<String, Integer> idsByName = new HashMap<>();
    if (table != null) {
      table.forEach(row -> idsByName.put(row.teamName(), row.teamId()));
    }
    Set<String> unresolved = new LinkedHashSet<>();
    List<StoredGame> games = new ArrayList<>();
    for (ScrapedFixtures.Game game : fixtures.games()) {
      Integer homeTeamId = idsByName.get(game.homeTeamName());
      Integer awayTeamId = idsByName.get(game.awayTeamName());
      if (homeTeamId == null) {
        unresolved.add(game.homeTeamName());
      }
      if (awayTeamId == null) {
        unresolved.add(game.awayTeamName());
      }
      games.add(
          new StoredGame(
              StoredGame.keyOf(game.homeTeamName(), game.awayTeamName()),
              game.gameId(),
              game.date().toString(),
              game.kickoffTime() == null ? null : game.kickoffTime().toString(),
              game.homeTeamName(),
              game.awayTeamName(),
              homeTeamId,
              awayTeamId,
              game.venue(),
              game.homeScore(),
              game.awayScore()));
    }
    if (!unresolved.isEmpty()) {
      warnings.add(new Warning(WarningCode.UNRESOLVED_TEAM_NAMES, unresolved.size()));
    }

    int stored = 0;
    for (String clubId : clubIds) {
      boolean written =
          clubContext.callAs(
              clubId,
              () ->
                  storeFixtures(teamId, leagueId, seasonId, ownName, fixtures.fetchedAt(), games));
      if (written) {
        stored++;
      }
    }
    return new FixturesOutcome(stored > 0 ? PartResult.STORED : PartResult.STALE, stored);
  }

  /** By value, whatever the boxing of either side. */
  private static boolean isTeam(StoredStandingsRow row, int teamId) {
    return Objects.equals(row.teamId(), teamId);
  }

  /** The one name that appears in every game, if exactly one does. */
  private static Optional<String> nameInEveryGame(List<ScrapedFixtures.Game> games) {
    Set<String> common = null;
    for (ScrapedFixtures.Game game : games) {
      Set<String> names = Set.of(game.homeTeamName(), game.awayTeamName());
      if (common == null) {
        common = new HashSet<>(names);
      } else {
        common.retainAll(names);
      }
    }
    return common != null && common.size() == 1
        ? Optional.of(common.iterator().next())
        : Optional.empty();
  }

  /** In the current club's context. True if written, false if stale. */
  private boolean storeFixtures(
      int teamId,
      int leagueId,
      int seasonId,
      String teamName,
      Instant fetchedAt,
      List<StoredGame> games) {
    String clubId = clubContext.requireClubId();
    return storeIfNewer(
        () -> fixturesRepository.findByClubIdAndTeamIdAndSeasonId(clubId, teamId, seasonId),
        LeagueFixturesSnapshot::getFetchedAt,
        LeagueFixturesSnapshot::new,
        snapshot -> {
          snapshot.setTeamId(teamId);
          snapshot.setLeagueId(leagueId);
          snapshot.setSeasonId(seasonId);
          snapshot.setTeamName(teamName);
          snapshot.setFetchedAt(fetchedAt);
          snapshot.setStoredAt(clock.instant());
          snapshot.setGames(games);
          fixturesRepository.save(snapshot);
        },
        fetchedAt);
  }

  private PartResult storeStandings(int leagueId, int seasonId, ScrapedStandings standings) {
    List<StoredStandingsRow> rows = toRows(standings);
    boolean written =
        storeIfNewer(
            () -> standingsRepository.findByLeagueIdAndSeasonId(leagueId, seasonId),
            LeagueStandingsSnapshot::getFetchedAt,
            LeagueStandingsSnapshot::new,
            snapshot -> {
              snapshot.setLeagueId(leagueId);
              snapshot.setSeasonId(seasonId);
              snapshot.setRound(standings.round());
              snapshot.setFetchedAt(standings.fetchedAt());
              snapshot.setStoredAt(clock.instant());
              snapshot.setRows(rows);
              standingsRepository.save(snapshot);
            },
            standings.fetchedAt());
    return written ? PartResult.STORED : PartResult.STALE;
  }

  /**
   * Load → compare {@code fetchedAt} → save. A lost race — the stored copy changed since it was
   * loaded ({@link OptimisticLockingFailureException}), or another writer inserted the first one
   * ({@link DuplicateKeyException} on the unique index) — re-reads once and compares again. A
   * second lost race of either kind is thrown as an {@link OptimisticLockingFailureException}:
   * {@code GlobalExceptionHandler} answers it {@code 409 CONCURRENT_MODIFICATION} (logged at WARN,
   * not ERROR), never a silent overwrite; the scraper may retry once.
   *
   * @return true if written, false if not newer than the stored one
   */
  private static <T> boolean storeIfNewer(
      Supplier<Optional<T>> load,
      Function<T, Instant> fetchedAtOf,
      Supplier<T> create,
      Consumer<T> fillAndSave,
      Instant fetchedAt) {
    for (int attempt = 1; ; attempt++) {
      Optional<T> existing = load.get();
      if (existing.isPresent() && !fetchedAt.isAfter(fetchedAtOf.apply(existing.get()))) {
        return false;
      }
      try {
        fillAndSave.accept(existing.orElseGet(create));
        return true;
      } catch (OptimisticLockingFailureException | DuplicateKeyException e) {
        if (attempt == 2) {
          throw new OptimisticLockingFailureException(
              "Snapshot write lost a race twice, answering 409", e);
        }
      }
    }
  }

  private static List<StoredStandingsRow> toRows(ScrapedStandings standings) {
    return standings.rows().stream()
        .map(
            row ->
                new StoredStandingsRow(
                    row.position(),
                    row.teamId(),
                    row.teamName(),
                    row.played(),
                    row.won(),
                    row.drawn(),
                    row.lost(),
                    row.goalsFor(),
                    row.goalsAgainst(),
                    row.points()))
        .toList();
  }
}
