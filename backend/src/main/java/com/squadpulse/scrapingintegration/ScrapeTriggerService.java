package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapeRunStatusService.leagueLockKey;
import static com.squadpulse.scrapingintegration.ScrapeRunStatusService.runKey;
import static com.squadpulse.scrapingintegration.ScrapeRunStatusService.teamLockKey;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.common.ClubContext;
import com.squadpulse.scrapingintegration.RefreshStatusResponse.Outcome;
import com.squadpulse.scrapingintegration.ScrapeRunStatusService.Run;
import com.squadpulse.scrapingintegration.ScraperClient.Answer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Starts a refresh of the current club's match data (KAN-68): decides whether the scraper may be
 * asked, asks it, and answers with the club's status. Its Redis locks are <b>the primary protection
 * of football.org.il</b> against being asked too often (the scraper's own queue dedupe is only a
 * second line of defence).
 *
 * <p>The club comes only from {@link ClubContext}, the ids only from its own mapping ({@link
 * ClubFootballMappings#currentClubMapping()}). Nothing here reads the HTTP request, Spring
 * Security's context or the user, so a scheduled job can call {@link #refresh} unchanged inside
 * {@code ClubContext.callAs(clubId, ...)} with {@link TriggerSource#SCHEDULED}.
 *
 * <h2>Locks</h2>
 *
 * <pre>
 * scrape:lock:team:{teamId}:{leagueId}:{seasonId}   STRING  runId   TTL: the refresh cooldown
 * scrape:lock:league:{leagueId}:{seasonId}          STRING  runId   TTL: the refresh cooldown
 * </pre>
 *
 * Only football.org.il ids (public, not club-owned) are in these keys. The team key is the one the
 * scraper dedupes by. The league key stops two clubs of one league from each having the same table
 * scraped: a club that finds it held asks for its fixtures only ({@code standings: false}),
 * <b>silently</b> — its answer is exactly what it would be otherwise, so no club learns that
 * another club in its league refreshed.
 *
 * <h2>Order of operations</h2>
 *
 * <ol>
 *   <li>No mapping → {@code NOT_MAPPED}; scraper not configured → {@code SCRAPER_UNAVAILABLE}.
 *       Neither touches a lock.
 *   <li>{@link #ACQUIRE_SCRIPT}, one atomic step: {@code SET NX PX} the team lock with a new runId;
 *       if it's held, stop ({@code ALREADY_RUNNING} if the holder, or the club's current run, is
 *       this club's and still running, else {@code COOLDOWN}). Otherwise {@code SET NX PX} the
 *       league lock (held → no standings), and write the run record as {@code PENDING}. So exactly
 *       one of any number of simultaneous requests calls the scraper (the {@code NX}), and the
 *       record exists before the scraper can post a result for it. A Redis failure here is a 5xx
 *       and the scraper is never called without the locks.
 *   <li>The trigger ({@link ScraperClient}).
 *   <li>Accepted ({@code 202 QUEUED} with our runId, or {@code ALREADY_QUEUED}) → the cooldown
 *       stands — even if the scrape later fails: the site was asked — and the run is {@code
 *       RUNNING} unless its result already arrived, and the club's current run. Anything else →
 *       {@link #RELEASE_SCRIPT} deletes both locks only if they still hold our runId, and the
 *       record: the site was never asked, so a retry may start at once, and the previous status
 *       stays.
 * </ol>
 */
@Service
public class ScrapeTriggerService {

  /**
   * KEYS: team lock, league lock, run key. ARGV: runId, cooldown ms, run TTL ms, clubId, teamId,
   * leagueId, seasonId, source, startedAt ms, playerStats ("1" / "0"). Returns {@code [HELD, holder
   * runId]} or {@code [ACQUIRED, "1" if the league lock was taken too, else "0"]}.
   */
  @SuppressWarnings("rawtypes")
  static final RedisScript<List> ACQUIRE_SCRIPT =
      RedisScript.of(
          """
          if not redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
            return {'HELD', redis.call('GET', KEYS[1]) or ''}
          end
          local standings = '0'
          if redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', ARGV[2]) then
            standings = '1'
          end
          redis.call('HSET', KEYS[3], 'clubId', ARGV[4], 'teamId', ARGV[5], 'leagueId', ARGV[6],
                     'seasonId', ARGV[7], 'fixtures', '1', 'standings', standings,
                     'playerStats', ARGV[10], 'source', ARGV[8], 'state', 'PENDING',
                     'startedAt', ARGV[9], 'failureReason', '')
          redis.call('PEXPIRE', KEYS[3], ARGV[3])
          return {'ACQUIRED', standings}
          """,
          List.class);

  /**
   * KEYS: team lock, league lock, run key. ARGV: runId. Deletes each lock only if it still holds
   * this runId (a lock that expired and was taken by another run stays), then the run record.
   * Returns how many locks it deleted.
   */
  static final RedisScript<Long> RELEASE_SCRIPT =
      RedisScript.of(
          """
          local released = 0
          for i = 1, 2 do
            if redis.call('GET', KEYS[i]) == ARGV[1] then
              redis.call('DEL', KEYS[i])
              released = released + 1
            end
          end
          redis.call('DEL', KEYS[3])
          return released
          """,
          Long.class);

  /**
   * Whether a trigger asks for the team's player statistics (KAN-72). {@code true} since KAN-73,
   * which taught the scraper the property in the same change: every refresh asks for them, and the
   * run counts the part, so a refresh succeeds only if the stats are stored (or already up to date)
   * too. The scraper treats the property as optional (absent = {@code false}), so either value is
   * safe whatever the deploy order. The stats share the per-team lock; the league lock doesn't
   * concern them.
   */
  static final boolean REQUEST_PLAYER_STATS = true;

  private static final Logger log = LoggerFactory.getLogger(ScrapeTriggerService.class);

  private final ClubContext clubContext;
  private final ClubFootballMappings mappings;
  private final StringRedisTemplate redis;
  private final ScraperProperties properties;
  private final ScraperClient scraperClient;
  private final ScrapeRunStatusService statusService;
  private final Clock clock;

  @Autowired
  ScrapeTriggerService(
      ClubContext clubContext,
      ClubFootballMappings mappings,
      StringRedisTemplate redis,
      ScraperProperties properties,
      ScraperClient scraperClient,
      ScrapeRunStatusService statusService) {
    this(clubContext, mappings, redis, properties, scraperClient, statusService, Clock.systemUTC());
  }

  ScrapeTriggerService(
      ClubContext clubContext,
      ClubFootballMappings mappings,
      StringRedisTemplate redis,
      ScraperProperties properties,
      ScraperClient scraperClient,
      ScrapeRunStatusService statusService,
      Clock clock) {
    this.clubContext = clubContext;
    this.mappings = mappings;
    this.redis = redis;
    this.properties = properties;
    this.scraperClient = scraperClient;
    this.statusService = statusService;
    this.clock = clock;
  }

  /** The current club's refresh status, without starting anything ({@code outcome} null). */
  public RefreshStatusResponse status() {
    String clubId = clubContext.requireClubId();
    Optional<FootballOrgIlIds> mapping = mappings.currentClubMapping();
    if (mapping.isEmpty()) {
      return RefreshStatusResponse.notMapped(null);
    }
    return statusService.currentStatus(clubId, mapping.get(), null);
  }

  /** Starts a refresh for the current club if it may start; see the class comment. */
  public RefreshStatusResponse refresh(TriggerSource source) {
    String clubId = clubContext.requireClubId();
    Optional<FootballOrgIlIds> found = mappings.currentClubMapping();
    if (found.isEmpty()) {
      log.info("Refresh NOT_MAPPED (source {})", source);
      return RefreshStatusResponse.notMapped(Outcome.NOT_MAPPED);
    }
    FootballOrgIlIds ids = found.get();
    if (!scraperClient.configured()) {
      logDecision(source, Outcome.SCRAPER_UNAVAILABLE, null, ids, null);
      return statusService.currentStatus(clubId, ids, Outcome.SCRAPER_UNAVAILABLE);
    }

    String runId = UUID.randomUUID().toString();
    Instant startedAt = clock.instant();
    List<?> acquired =
        redis.execute(
            ACQUIRE_SCRIPT,
            List.of(teamLockKey(ids), leagueLockKey(ids), runKey(runId)),
            runId,
            String.valueOf(properties.refreshCooldown().toMillis()),
            String.valueOf(ScrapeRunStatusService.RUN_TTL.toMillis()),
            clubId,
            String.valueOf(ids.teamId()),
            String.valueOf(ids.leagueId()),
            String.valueOf(ids.seasonId()),
            source.name(),
            String.valueOf(startedAt.toEpochMilli()),
            REQUEST_PLAYER_STATS ? "1" : "0");
    if (acquired == null || acquired.isEmpty()) {
      throw new IllegalStateException("The scrape lock script returned nothing");
    }
    if ("HELD".equals(String.valueOf(acquired.get(0)))) {
      return held(clubId, ids, source, String.valueOf(acquired.get(1)));
    }
    boolean standings = "1".equals(String.valueOf(acquired.get(1)));

    Answer answer =
        scraperClient.trigger(
            new ScraperClient.TriggerRequest(
                runId,
                ids.teamId(),
                ids.leagueId(),
                ids.seasonId(),
                true,
                standings,
                REQUEST_PLAYER_STATS));
    return switch (answer) {
      case Answer.Queued queued when runId.equals(queued.runId()) -> {
        statusService.markAccepted(clubId, runId);
        logDecision(source, Outcome.STARTED, runId, ids, standings);
        yield statusService.currentStatus(clubId, ids, Outcome.STARTED);
      }
      case Answer.AlreadyQueued already ->
          alreadyQueued(clubId, ids, source, runId, standings, already);
      case Answer.Queued queued ->
          unavailable(clubId, ids, source, runId, standings, 202, "runId not echoed");
      case Answer.Unavailable unavailable ->
          unavailable(
              clubId, ids, source, runId, standings, unavailable.status(), unavailable.why());
    };
  }

  /** The team lock is held: by a run of this club still in progress, or it's the cooldown. */
  private RefreshStatusResponse held(
      String clubId, FootballOrgIlIds ids, TriggerSource source, String holderRunId) {
    Optional<Run> holder =
        holderRunId.isEmpty() ? Optional.empty() : statusService.run(holderRunId);
    if (holder.isPresent() && holder.get().clubId().equals(clubId) && holder.get().inProgress()) {
      logDecision(source, Outcome.ALREADY_RUNNING, holderRunId, ids, null);
      return statusService.status(holder.get(), ids, Outcome.ALREADY_RUNNING);
    }
    Optional<Run> current = statusService.currentRun(clubId);
    if (current.isPresent() && current.get().ids().equals(ids) && current.get().inProgress()) {
      logDecision(source, Outcome.ALREADY_RUNNING, current.get().runId(), ids, null);
      return statusService.status(current.get(), ids, Outcome.ALREADY_RUNNING);
    }
    logDecision(source, Outcome.COOLDOWN, null, ids, null);
    return statusService.currentStatus(clubId, ids, Outcome.COOLDOWN);
  }

  /**
   * The scraper already had a scrape of these ids. If it's a run of this club, that's the current
   * run and ours is dropped; otherwise (our locks were lost, or someone triggered the scraper by
   * hand) ours stays {@code RUNNING} and times out unless a result with our runId arrives.
   */
  private RefreshStatusResponse alreadyQueued(
      String clubId,
      FootballOrgIlIds ids,
      TriggerSource source,
      String runId,
      boolean standings,
      Answer.AlreadyQueued already) {
    Optional<Run> existing =
        already.runId() == null ? Optional.empty() : statusService.run(already.runId());
    if (existing.isPresent() && existing.get().clubId().equals(clubId)) {
      statusService.adopt(clubId, already.runId(), runId);
      logDecision(source, Outcome.STARTED, already.runId(), ids, standings);
    } else {
      log.warn(
          "Scraper already had a scrape of team {} league {} season {} queued that isn't one of"
              + " this club's runs; run {} kept and will time out without a result",
          ids.teamId(),
          ids.leagueId(),
          ids.seasonId(),
          runId);
      statusService.markAccepted(clubId, runId);
      logDecision(source, Outcome.STARTED, runId, ids, standings);
    }
    return statusService.currentStatus(clubId, ids, Outcome.STARTED);
  }

  /** Not reached or refused: the site wasn't asked, so our locks go (only ours) and the record. */
  private RefreshStatusResponse unavailable(
      String clubId,
      FootballOrgIlIds ids,
      TriggerSource source,
      String runId,
      boolean standings,
      int status,
      String why) {
    redis.execute(
        RELEASE_SCRIPT, List.of(teamLockKey(ids), leagueLockKey(ids), runKey(runId)), runId);
    if (status == 400 || status == 401 || status == 413 || status == 415) {
      log.error(
          "Scraper refused the trigger for run {} with {}: a bug in the trigger or a"
              + " SCRAPER_SERVICE_TOKEN mismatch",
          runId,
          status);
    } else {
      log.warn("Scraper unavailable for run {}: {}", runId, why);
    }
    logDecision(source, Outcome.SCRAPER_UNAVAILABLE, runId, ids, standings);
    return statusService.currentStatus(clubId, ids, Outcome.SCRAPER_UNAVAILABLE);
  }

  private static void logDecision(
      TriggerSource source,
      Outcome outcome,
      String runId,
      FootballOrgIlIds ids,
      Boolean standings) {
    log.info(
        "Refresh {} (source {}, run {}, team {} league {} season {}, standings sent: {})",
        outcome,
        source,
        runId == null ? "-" : runId,
        ids.teamId(),
        ids.leagueId(),
        ids.seasonId(),
        standings == null ? "-" : standings);
  }
}
