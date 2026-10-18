package com.squadpulse.scrapingintegration;

import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.match.CupCompetition;
import com.squadpulse.scrapingintegration.RefreshStatusResponse.FailureReason;
import com.squadpulse.scrapingintegration.RefreshStatusResponse.Outcome;
import com.squadpulse.scrapingintegration.RefreshStatusResponse.State;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.CupCompetitionPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.CupGamesPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.FixturesPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.GameDetailsPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.PartStatus;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.PlayerStatsPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.StandingsPart;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Scrape runs and their status, in Redis (KAN-68): what {@code GET /matches/refresh} shows, the
 * timeout of a run whose result never came, and the status a result sets when the scraper posts it.
 *
 * <h2>Redis keys (prefix {@value #PREFIX})</h2>
 *
 * <pre>
 * scrape:run:{runId}               HASH    clubId, teamId, leagueId, seasonId - who and what it's for
 *                                          fixtures, standings, playerStats, gameDetails,
 *                                            cupGames - the parts requested ("1" / "0";
 *                                            playerStats absent in records written before KAN-72,
 *                                            gameDetails before KAN-79, cupGames before KAN-84 =
 *                                            not requested)
 *                                          source    - USER / SCHEDULED
 *                                          state     - PENDING / RUNNING / SUCCEEDED / FAILED
 *                                          startedAt, finishedAt - epoch millis (backend clock)
 *                                          failureReason - with FAILED, else ""
 *                                          finishedBy - RESULT / TIMEOUT
 *                                          TTL: {@value #RUN_TTL_DAYS} days
 * scrape:club:{clubId}:current     STRING  runId of the club's latest run
 *                                          TTL: {@value #CURRENT_TTL_DAYS} days
 * </pre>
 *
 * The clubId in these keys and records comes only from {@code ClubContext} (when a run is created,
 * by {@link ScrapeTriggerService}) or from the run record — never from a request. A result finds
 * its run by {@code runId} alone and changes only that run's record, so it can never touch another
 * club's status, nor the club's current pointer.
 *
 * <p>Every change to a record's {@code state} is a Lua script that checks the state it expects
 * first, and none of them recreates a record that's gone (an {@code HSET} would, without a TTL).
 * {@code PENDING} is the state while the trigger is being sent: {@link #markAccepted} moves it to
 * {@code RUNNING} only if no result arrived first.
 *
 * <p>The timeout is applied lazily, whenever a status is read: no scheduler.
 */
@Service
class ScrapeRunStatusService {

  static final String PREFIX = "scrape:";
  static final String RUN_KEY_PREFIX = PREFIX + "run:";
  static final String CLUB_KEY_PREFIX = PREFIX + "club:";
  static final String TEAM_LOCK_PREFIX = PREFIX + "lock:team:";
  static final String LEAGUE_LOCK_PREFIX = PREFIX + "lock:league:";

  static final long RUN_TTL_DAYS = 7;
  static final long CURRENT_TTL_DAYS = 30;
  static final Duration RUN_TTL = Duration.ofDays(RUN_TTL_DAYS);
  static final Duration CURRENT_TTL = Duration.ofDays(CURRENT_TTL_DAYS);

  static final String PENDING = "PENDING";
  static final String RUNNING = "RUNNING";

  /** KEYS: run key, club's current key. ARGV: runId, current TTL in ms. */
  static final RedisScript<Long> MARK_ACCEPTED_SCRIPT =
      RedisScript.of(
          """
          if redis.call('HGET', KEYS[1], 'state') == 'PENDING' then
            redis.call('HSET', KEYS[1], 'state', 'RUNNING')
          end
          redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
          return 1
          """,
          Long.class);

  /** KEYS: run key. ARGV: finishedAt. 1 if it timed the run out, 0 if it had already ended. */
  static final RedisScript<Long> TIMEOUT_SCRIPT =
      RedisScript.of(
          """
          local state = redis.call('HGET', KEYS[1], 'state')
          if state == 'PENDING' or state == 'RUNNING' then
            redis.call('HSET', KEYS[1], 'state', 'FAILED', 'failureReason', 'NO_RESULT',
                       'finishedAt', ARGV[1], 'finishedBy', 'TIMEOUT')
            return 1
          end
          return 0
          """,
          Long.class);

  /**
   * KEYS: run key. ARGV: state, failureReason ("" for none), finishedAt. 0 if the record is gone,
   * or already holds this very result (a replay keeps its first {@code finishedAt}); else 1. A
   * result replaces a timeout's {@code NO_RESULT}: the data was stored, the status must say so.
   */
  static final RedisScript<Long> RECORD_RESULT_SCRIPT =
      RedisScript.of(
          """
          if redis.call('EXISTS', KEYS[1]) == 0 then
            return 0
          end
          local run = redis.call('HMGET', KEYS[1], 'state', 'failureReason', 'finishedBy')
          if run[3] == 'RESULT' and run[1] == ARGV[1] and (run[2] or '') == ARGV[2] then
            return 0
          end
          redis.call('HSET', KEYS[1], 'state', ARGV[1], 'failureReason', ARGV[2],
                     'finishedAt', ARGV[3], 'finishedBy', 'RESULT')
          return 1
          """,
          Long.class);

  /**
   * The cup competitions a processed cup part must contain for a run that requested cup games
   * (KAN-84): one missing fails the run ({@code NO_RESULT}), as an absent part does.
   */
  static final Set<CupCompetition> EXPECTED_CUP_COMPETITIONS = Set.of(CupCompetition.STATE_CUP);

  private static final Logger log = LoggerFactory.getLogger(ScrapeRunStatusService.class);

  /** One run record, as read from Redis. */
  record Run(
      String runId,
      String clubId,
      FootballOrgIlIds ids,
      boolean fixtures,
      boolean standings,
      boolean playerStats,
      boolean gameDetails,
      boolean cupGames,
      String state,
      String failureReason,
      Instant startedAt,
      Instant finishedAt) {

    boolean inProgress() {
      return PENDING.equals(state) || RUNNING.equals(state);
    }
  }

  private final StringRedisTemplate redis;
  private final ScraperProperties properties;
  private final Clock clock;

  @Autowired
  ScrapeRunStatusService(StringRedisTemplate redis, ScraperProperties properties) {
    this(redis, properties, Clock.systemUTC());
  }

  ScrapeRunStatusService(StringRedisTemplate redis, ScraperProperties properties, Clock clock) {
    this.redis = redis;
    this.properties = properties;
    this.clock = clock;
  }

  static String runKey(String runId) {
    return RUN_KEY_PREFIX + runId;
  }

  static String currentKey(String clubId) {
    return CLUB_KEY_PREFIX + clubId + ":current";
  }

  static String teamLockKey(FootballOrgIlIds ids) {
    return TEAM_LOCK_PREFIX + ids.teamId() + ":" + ids.leagueId() + ":" + ids.seasonId();
  }

  static String leagueLockKey(FootballOrgIlIds ids) {
    return LEAGUE_LOCK_PREFIX + ids.leagueId() + ":" + ids.seasonId();
  }

  // --- reads ---------------------------------------------------------------------------------

  /**
   * The club's status for its current {@code mapping}, with {@code outcome}: its latest run (timed
   * out first if due), or {@code IDLE} if there's none or it was for other ids.
   */
  RefreshStatusResponse currentStatus(String clubId, FootballOrgIlIds mapping, Outcome outcome) {
    Optional<Run> run = currentRun(clubId).filter(r -> r.ids().equals(mapping));
    return view(outcome, run.orElse(null), mapping);
  }

  /** The status shown for {@code run} (timed out first if due). */
  RefreshStatusResponse status(Run run, FootballOrgIlIds mapping, Outcome outcome) {
    return view(outcome, run, mapping);
  }

  /** The club's latest run, timed out first if due; empty if none, or its record expired. */
  Optional<Run> currentRun(String clubId) {
    String runId = redis.opsForValue().get(currentKey(clubId));
    return runId == null ? Optional.empty() : run(runId);
  }

  /** A run by id, timed out first if due; empty if unknown or expired. */
  Optional<Run> run(String runId) {
    return read(runId).map(this::applyTimeout);
  }

  private RefreshStatusResponse view(Outcome outcome, Run run, FootballOrgIlIds mapping) {
    Instant nextRefreshAt = nextRefreshAt(mapping);
    if (run == null) {
      return new RefreshStatusResponse(outcome, State.IDLE, null, null, null, nextRefreshAt);
    }
    State state =
        switch (run.state()) {
          case "SUCCEEDED" -> State.SUCCEEDED;
          case "FAILED" -> State.FAILED;
          default -> State.RUNNING;
        };
    FailureReason reason = state == State.FAILED ? failureReason(run.failureReason()) : null;
    return new RefreshStatusResponse(
        outcome,
        state,
        reason,
        run.startedAt(),
        state == State.RUNNING ? null : run.finishedAt(),
        nextRefreshAt);
  }

  /** From the team lock's remaining TTL ({@code PTTL}); {@code null} if it's free. */
  Instant nextRefreshAt(FootballOrgIlIds mapping) {
    Long millis = redis.getExpire(teamLockKey(mapping), TimeUnit.MILLISECONDS);
    return millis == null || millis <= 0
        ? null
        : clock.instant().truncatedTo(ChronoUnit.MILLIS).plusMillis(millis);
  }

  private Run applyTimeout(Run run) {
    if (!run.inProgress()
        || clock.instant().isBefore(run.startedAt().plus(properties.runTimeout()))) {
      return run;
    }
    Long changed =
        redis.execute(TIMEOUT_SCRIPT, List.of(runKey(run.runId())), String.valueOf(clock.millis()));
    if (changed != null && changed == 1) {
      log.info("Scrape run {} is now FAILED (NO_RESULT): timed out", run.runId());
    }
    return read(run.runId()).orElse(run);
  }

  private Optional<Run> read(String runId) {
    Map<Object, Object> hash = redis.opsForHash().entries(runKey(runId));
    if (hash.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Run(
              runId,
              string(hash, "clubId"),
              new FootballOrgIlIds(
                  Integer.parseInt(string(hash, "teamId")),
                  Integer.parseInt(string(hash, "leagueId")),
                  Integer.parseInt(string(hash, "seasonId"))),
              "1".equals(string(hash, "fixtures")),
              "1".equals(string(hash, "standings")),
              "1".equals(string(hash, "playerStats")),
              "1".equals(string(hash, "gameDetails")),
              "1".equals(string(hash, "cupGames")),
              string(hash, "state"),
              string(hash, "failureReason"),
              Instant.ofEpochMilli(Long.parseLong(string(hash, "startedAt"))),
              millis(string(hash, "finishedAt"))));
    } catch (RuntimeException e) {
      // Only a record written outside this module could fail to parse: treat it as absent.
      log.warn("Scrape run {} has an unreadable record; ignored", runId);
      return Optional.empty();
    }
  }

  // --- writes from the trigger -----------------------------------------------------------------

  /**
   * The scraper accepted {@code runId}'s trigger: it's {@code RUNNING} unless a result already
   * ended it, and it's now the club's current run.
   */
  void markAccepted(String clubId, String runId) {
    redis.execute(
        MARK_ACCEPTED_SCRIPT,
        List.of(runKey(runId), currentKey(clubId)),
        runId,
        String.valueOf(CURRENT_TTL.toMillis()));
  }

  /** The scraper already had {@code existingRunId} (a run of this club) queued: that's current. */
  void adopt(String clubId, String existingRunId, String ownRunId) {
    redis.opsForValue().set(currentKey(clubId), existingRunId, CURRENT_TTL);
    redis.delete(runKey(ownRunId));
  }

  // --- writes from the ingest ------------------------------------------------------------------

  /**
   * Records what an accepted scrape result means for its run, from the outcomes the ingest
   * computed. Called by {@link ScrapeResultController} only after the ingest returned normally, so
   * a 400 / 409 / 5xx never changes a status (the scraper's retry will).
   *
   * <p>No change for a {@code null} or unknown {@code runId}, or one whose record is for other ids
   * than the request's (WARN). Never throws: the data is already stored, so a Redis failure here is
   * a WARN and the ingest's 200 stands.
   */
  void recordResult(ScrapeResultRequest request, ScrapeResultResponse response) {
    String runId = request.runId();
    if (runId == null) {
      return;
    }
    try {
      Optional<Run> found = read(runId);
      if (found.isEmpty()) {
        log.debug("Scrape result for unknown run {}: no status recorded", runId);
        return;
      }
      Run run = found.get();
      FootballOrgIlIds sent =
          new FootballOrgIlIds(request.teamId(), request.leagueId(), request.seasonId());
      if (!run.ids().equals(sent)) {
        log.warn(
            "Scrape result for run {} has team {} league {} season {}, the run was for team {}"
                + " league {} season {}: no status recorded",
            runId,
            sent.teamId(),
            sent.leagueId(),
            sent.seasonId(),
            run.ids().teamId(),
            run.ids().leagueId(),
            run.ids().seasonId());
        return;
      }
      FailureReason reason = firstFailure(run, request, response);
      String state = reason == null ? "SUCCEEDED" : "FAILED";
      Long changed =
          redis.execute(
              RECORD_RESULT_SCRIPT,
              List.of(runKey(runId)),
              state,
              reason == null ? "" : reason.name(),
              String.valueOf(clock.millis()));
      if (changed != null && changed == 1) {
        log.info(
            "Scrape run {} is now {}{}", runId, state, reason == null ? "" : " (" + reason + ")");
      }
    } catch (RuntimeException e) {
      log.warn(
          "Couldn't record the status of scrape run {} (team {} league {} season {}): {}",
          runId,
          request.teamId(),
          request.leagueId(),
          request.seasonId(),
          e.getClass().getSimpleName());
    }
  }

  /**
   * The first failed part the run requested, in the order fixtures, standings, player statistics
   * (KAN-72), cup games (KAN-84), game details (KAN-79); {@code null} if none. A part the run
   * didn't request is ignored, whatever the result says about it: a run succeeds only if every part
   * it asked for was stored or already up to date. The game details' per-game {@code failures}
   * never fail a run (the part is {@code STORED}): one broken page must not turn every refresh red.
   * The cup games, unlike them, fail it per competition ({@link #cupGamesFailure}): a competition
   * the scraper couldn't read (e.g. a name mismatch) must be visible, not silent.
   */
  private static FailureReason firstFailure(
      Run run, ScrapeResultRequest request, ScrapeResultResponse response) {
    if (run.fixtures()) {
      FailureReason reason =
          partFailure(response.fixtures().outcome(), scraperReason(request.fixtures()));
      if (reason != null) {
        return reason;
      }
    }
    if (run.standings()) {
      FailureReason reason =
          partFailure(response.standings().outcome(), scraperReason(request.standings()));
      if (reason != null) {
        return reason;
      }
    }
    if (run.playerStats()) {
      FailureReason reason =
          partFailure(response.playerStats().outcome(), scraperReason(request.playerStats()));
      if (reason != null) {
        return reason;
      }
    }
    if (run.cupGames()) {
      FailureReason reason =
          cupGamesFailure(response.cupGames(), request.cupGames(), EXPECTED_CUP_COMPETITIONS);
      if (reason != null) {
        return reason;
      }
    }
    if (run.gameDetails()) {
      return partFailure(response.gameDetails().outcome(), scraperReason(request.gameDetails()));
    }
    return null;
  }

  /**
   * What a requested cup part means for its run: the part {@code FAILURE_RECORDED} → the scraper's
   * reason, {@code REJECTED} → {@code REJECTED}, {@code NOT_SENT} → {@code NO_RESULT}; processed,
   * but a competition of {@code expected} missing → {@code NO_RESULT}; else the first competition,
   * in the request's order, that the scraper reported {@code FAILED} (its reason) or the ingest
   * rejected ({@code REJECTED}, whatever the reason); {@code null} if every competition was stored
   * or already up to date.
   *
   * @param expected {@link #EXPECTED_CUP_COMPETITIONS}; a parameter so a test can exercise a
   *     missing competition while the enum has a single value
   */
  static FailureReason cupGamesFailure(
      ScrapeResultResponse.CupGamesOutcome response,
      CupGamesPart request,
      Set<CupCompetition> expected) {
    FailureReason partReason = partFailure(response.outcome(), scraperReason(request));
    if (partReason != null) {
      return partReason;
    }
    Set<CupCompetition> present =
        response.competitions().stream()
            .map(ScrapeResultResponse.CompetitionOutcome::competition)
            .collect(Collectors.toSet());
    if (!present.containsAll(expected)) {
      return FailureReason.NO_RESULT;
    }
    for (ScrapeResultResponse.CompetitionOutcome competition : response.competitions()) {
      ScrapeResultRequest.FailureReason scraperReason =
          request == null
              ? null
              : request.competitions().stream()
                  .filter(sent -> sent.competition() == competition.competition())
                  .findFirst()
                  .map(ScrapeRunStatusService::scraperReason)
                  .orElse(null);
      FailureReason reason = partFailure(competition.outcome(), scraperReason);
      if (reason != null) {
        return reason;
      }
    }
    return null;
  }

  private static FailureReason partFailure(
      ScrapeResultResponse.Outcome outcome, ScrapeResultRequest.FailureReason scraperReason) {
    return switch (outcome) {
      case STORED, STALE -> null;
      case REJECTED -> FailureReason.REJECTED;
      case NOT_SENT -> FailureReason.NO_RESULT;
      case FAILURE_RECORDED ->
          switch (scraperReason) {
            case SOURCE_BLOCKED -> FailureReason.SOURCE_BLOCKED;
            case TIMEOUT -> FailureReason.SOURCE_TIMEOUT;
            case SOURCE_ERROR -> FailureReason.SOURCE_ERROR;
            case PARSE_ERROR -> FailureReason.PARSE_ERROR;
            case null -> FailureReason.SOURCE_ERROR;
          };
    };
  }

  private static ScrapeResultRequest.FailureReason scraperReason(Object part) {
    if (part instanceof FixturesPart fixtures && fixtures.status() == PartStatus.FAILED) {
      return fixtures.reason();
    }
    if (part instanceof StandingsPart standings && standings.status() == PartStatus.FAILED) {
      return standings.reason();
    }
    if (part instanceof PlayerStatsPart playerStats && playerStats.status() == PartStatus.FAILED) {
      return playerStats.reason();
    }
    if (part instanceof GameDetailsPart gameDetails && gameDetails.status() == PartStatus.FAILED) {
      return gameDetails.reason();
    }
    if (part instanceof CupGamesPart cupGames && cupGames.status() == PartStatus.FAILED) {
      return cupGames.reason();
    }
    if (part instanceof CupCompetitionPart competition
        && competition.status() == PartStatus.FAILED) {
      return competition.reason();
    }
    return null;
  }

  private static FailureReason failureReason(String stored) {
    try {
      return FailureReason.valueOf(stored);
    } catch (RuntimeException e) {
      return FailureReason.NO_RESULT;
    }
  }

  private static String string(Map<Object, Object> hash, String field) {
    Object value = hash.get(field);
    return value == null ? null : value.toString();
  }

  private static Instant millis(String value) {
    return value == null || value.isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(value));
  }
}
