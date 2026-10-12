package com.squadpulse.match;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.common.ClubContext;
import com.squadpulse.match.IngestResult.PartResult;
import com.squadpulse.match.IngestResult.RejectionReason;
import com.squadpulse.match.IngestResult.Warning;
import com.squadpulse.match.IngestResult.WarningCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * LeagueStandingsSnapshot} and {@link LeagueFixturesSnapshot}, called only by the service-token
 * ingest endpoint in {@code scrapingintegration}, which validated the input and handles the parts
 * the scraper reported as failed. See docs/scraper-backend-contract.md.
 *
 * <p>Order: the standings first, so the fixtures can be resolved against them; then the fixtures.
 * The two are independent — one rejected doesn't stop the other.
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
  private final ClubFootballMappings mappings;
  private final ClubContext clubContext;
  private final Clock clock;

  @Autowired
  ScrapeIngestService(
      LeagueStandingsSnapshotRepository standingsRepository,
      LeagueFixturesSnapshotRepository fixturesRepository,
      ClubFootballMappings mappings,
      ClubContext clubContext) {
    this(standingsRepository, fixturesRepository, mappings, clubContext, Clock.systemUTC());
  }

  /** For tests: {@code clock} sets {@code storedAt}. */
  ScrapeIngestService(
      LeagueStandingsSnapshotRepository standingsRepository,
      LeagueFixturesSnapshotRepository fixturesRepository,
      ClubFootballMappings mappings,
      ClubContext clubContext,
      Clock clock) {
    this.standingsRepository = standingsRepository;
    this.fixturesRepository = fixturesRepository;
    this.mappings = mappings;
    this.clubContext = clubContext;
    this.clock = clock;
  }

  /**
   * @param fixtures {@code null} if this scrape has no successful fixtures part
   * @param standings {@code null} if this scrape has no successful standings part
   */
  public IngestResult ingest(
      int teamId,
      int leagueId,
      int seasonId,
      ScrapedFixtures fixtures,
      ScrapedStandings standings) {
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
    return new IngestResult(fixturesResult, standingsResult, clubsUpdated, List.copyOf(warnings));
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
