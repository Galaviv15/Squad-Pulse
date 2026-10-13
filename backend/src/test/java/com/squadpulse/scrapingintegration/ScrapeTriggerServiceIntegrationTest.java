package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapeRunStatusService.currentKey;
import static com.squadpulse.scrapingintegration.ScrapeRunStatusService.leagueLockKey;
import static com.squadpulse.scrapingintegration.ScrapeRunStatusService.runKey;
import static com.squadpulse.scrapingintegration.ScrapeRunStatusService.teamLockKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.redis.testcontainers.RedisContainer;
import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.common.CapturedLogs;
import com.squadpulse.common.ClubContext;
import com.squadpulse.scrapingintegration.RefreshStatusResponse.Outcome;
import com.squadpulse.scrapingintegration.RefreshStatusResponse.State;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ScrapeTriggerService} and its {@link ScraperClient} on a real Redis, against a real local
 * HTTP server ({@link FakeScraper}), with no Spring context and no security context: every way the
 * trigger can fail, that a failure releases only our own locks and keeps the previous status, the
 * {@code ALREADY_QUEUED} answers, the timeouts, and the scheduled-job path (KAN-68).
 */
@Testcontainers
class ScrapeTriggerServiceIntegrationTest {

  static final String TOKEN = "test-only-scraper-token-0123456789abcdef";
  private static final String CLUB = "club-a";
  private static final FootballOrgIlIds IDS = new FootballOrgIlIds(7617, 1010, 28);

  @Container
  static final RedisContainer REDIS_CONTAINER =
      new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;

  private final FakeScraper scraper = new FakeScraper();
  private final ClubContext clubContext = new ClubContext();
  private final ClubFootballMappings mappings = mock(ClubFootballMappings.class);
  private final CapturedLogs logs = new CapturedLogs();

  @BeforeAll
  static void connect() {
    connectionFactory =
        connectionTo(REDIS_CONTAINER.getRedisHost(), REDIS_CONTAINER.getRedisPort());
    redis = new StringRedisTemplate(connectionFactory);
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void setUp() {
    logs.start();
    clubContext.setClubId(CLUB);
    when(mappings.currentClubMapping()).thenReturn(Optional.of(IDS));
  }

  @AfterEach
  void tearDown() {
    logs.stop();
    clubContext.clear();
    scraper.close();
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  // --- not mapped, not configured ------------------------------------------------------------

  @Test
  void anUnmappedClubWritesNothingAndCallsNoScraper() {
    when(mappings.currentClubMapping()).thenReturn(Optional.empty());

    RefreshStatusResponse response = service(scraper.url(), TOKEN).refresh(TriggerSource.USER);

    assertThat(response)
        .isEqualTo(
            new RefreshStatusResponse(
                Outcome.NOT_MAPPED, State.NOT_MAPPED, null, null, null, null));
    assertThat(service(scraper.url(), TOKEN).status())
        .isEqualTo(new RefreshStatusResponse(null, State.NOT_MAPPED, null, null, null, null));
    assertThat(redis.keys("*")).isEmpty();
    assertThat(scraper.received()).isEmpty();
  }

  @Test
  void withoutAUrlOrATokenNothingIsLockedOrCalled() {
    for (ScrapeTriggerService service :
        List.of(service(null, TOKEN), service(scraper.url(), ""), service(null, ""))) {
      RefreshStatusResponse response = service.refresh(TriggerSource.USER);

      assertThat(response.outcome()).isEqualTo(Outcome.SCRAPER_UNAVAILABLE);
      assertThat(response.state()).isEqualTo(State.IDLE);
      assertThat(redis.keys("*")).isEmpty();
    }
    assertThat(scraper.received()).isEmpty();
  }

  // --- the trigger ---------------------------------------------------------------------------

  @Test
  void aStartedRunSendsTheContractBodyAndIsRunning() {
    RefreshStatusResponse response = service(scraper.url(), TOKEN).refresh(TriggerSource.USER);

    assertThat(response.outcome()).isEqualTo(Outcome.STARTED);
    assertThat(response.state()).isEqualTo(State.RUNNING);
    assertThat(response.startedAt()).isNotNull();
    assertThat(response.finishedAt()).isNull();
    assertThat(response.nextRefreshAt()).isAfter(Instant.now().plusSeconds(590));
    FakeScraper.Received request = scraper.received().getFirst();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/scrape");
    assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
    assertThat(request.contentType()).startsWith("application/json");
    assertThat(UUID.fromString(request.runId()).toString()).isEqualTo(request.runId());
    assertThat(request.body().propertyNames())
        .containsExactly(
            "runId", "teamId", "leagueId", "seasonId", "fixtures", "standings", "playerStats");
    assertThat(request.body().get("teamId").intValue()).isEqualTo(7617);
    assertThat(request.body().get("leagueId").intValue()).isEqualTo(1010);
    assertThat(request.body().get("seasonId").intValue()).isEqualTo(28);
    assertThat(request.body().get("fixtures").booleanValue()).isTrue();
    assertThat(request.body().get("standings").booleanValue()).isTrue();
    assertThat(request.body().get("playerStats").booleanValue()).isTrue();
    assertThat(redis.opsForValue().get(teamLockKey(IDS))).isEqualTo(request.runId());
    assertThat(redis.opsForValue().get(leagueLockKey(IDS))).isEqualTo(request.runId());
    assertThat(redis.opsForValue().get(currentKey(CLUB))).isEqualTo(request.runId());
    assertThat(redis.opsForHash().entries(runKey(request.runId())))
        .containsEntry("clubId", CLUB)
        .containsEntry("state", "RUNNING")
        .containsEntry("source", "USER")
        .containsEntry("standings", "1")
        .containsEntry("playerStats", "1");
    assertThat(redis.getExpire(runKey(request.runId())))
        .isBetween(Duration.ofDays(7).toSeconds() - 5, Duration.ofDays(7).toSeconds());
    assertThat(redis.getExpire(currentKey(CLUB)))
        .isBetween(Duration.ofDays(30).toSeconds() - 5, Duration.ofDays(30).toSeconds());
    logs.assertNoWarningsOrAbove();
  }

  /** Decision 6: no security context, no request, only {@code ClubContext.callAs}. */
  @Test
  void aScheduledJobCanRefreshInsideCallAs() {
    clubContext.clear();
    SecurityContextHolder.clearContext();
    ScrapeTriggerService service = service(scraper.url(), TOKEN);

    RefreshStatusResponse response =
        clubContext.callAs(CLUB, () -> service.refresh(TriggerSource.SCHEDULED));

    assertThat(response.outcome()).isEqualTo(Outcome.STARTED);
    String runId = scraper.received().getFirst().runId();
    assertThat(redis.opsForHash().entries(runKey(runId)))
        .containsEntry("clubId", CLUB)
        .containsEntry("source", "SCHEDULED");
    assertThat(clubContext.getClubId()).isEmpty();
  }

  @Test
  void aHeldLeagueLockSendsFixturesOnly() {
    redis.opsForValue().set(leagueLockKey(IDS), "another-clubs-run", Duration.ofMinutes(5));

    RefreshStatusResponse response = service(scraper.url(), TOKEN).refresh(TriggerSource.USER);

    assertThat(response.outcome()).isEqualTo(Outcome.STARTED);
    FakeScraper.Received request = scraper.received().getFirst();
    assertThat(request.body().get("fixtures").booleanValue()).isTrue();
    assertThat(request.body().get("standings").booleanValue()).isFalse();
    assertThat(redis.opsForValue().get(leagueLockKey(IDS))).isEqualTo("another-clubs-run");
    assertThat(redis.opsForHash().entries(runKey(request.runId()))).containsEntry("standings", "0");
  }

  // --- the scraper unavailable ---------------------------------------------------------------

  @Test
  void aClosedPortIsUnavailableAndReleasesTheLocks() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    RefreshStatusResponse response =
        service("http://127.0.0.1:" + closedPort, TOKEN).refresh(TriggerSource.USER);

    assertUnavailableAndReleased(response);
  }

  @Test
  void aScraperThatNeverAnswersIsUnavailableWithinTheReadTimeout() {
    scraper.answer(FakeScraper.never());

    long started = System.nanoTime();
    RefreshStatusResponse response = service(scraper.url(), TOKEN).refresh(TriggerSource.USER);
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    System.out.println("No-answer trigger took " + took.toMillis() + " ms");
    assertThat(took)
        .isGreaterThanOrEqualTo(
            Duration.ofSeconds(ScraperClient.READ_TIMEOUT_SECONDS).minusMillis(200))
        .isLessThan(Duration.ofSeconds(ScraperClient.READ_TIMEOUT_SECONDS + 2));
    assertUnavailableAndReleased(response);
  }

  @Test
  void aScraperThatStopsAfterTheHeadersIsUnavailableWithinTheReadTimeout() {
    scraper.answer(FakeScraper.headersThenNothing());

    long started = System.nanoTime();
    RefreshStatusResponse response = service(scraper.url(), TOKEN).refresh(TriggerSource.USER);
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    System.out.println("Headers-then-nothing trigger took " + took.toMillis() + " ms");
    assertThat(took).isLessThan(Duration.ofSeconds(ScraperClient.READ_TIMEOUT_SECONDS + 2));
    assertUnavailableAndReleased(response);
  }

  @Test
  void everyRefusalIsUnavailableAndReleasesTheLocks() {
    for (FakeScraper.Responder answer :
        List.of(
            FakeScraper.status(503),
            FakeScraper.status(500),
            FakeScraper.status(404),
            FakeScraper.body(202, "not json"),
            FakeScraper.body(202, "[]"),
            FakeScraper.body(202, "{\"status\":\"QUEUED\"}"),
            FakeScraper.body(202, "{\"status\":\"WHATEVER\",\"runId\":null}"),
            FakeScraper.body(202, "{\"status\":\"QUEUED\",\"runId\":\"someone-else\"}"),
            FakeScraper.body(202, "{\"status\":\"QUEUED\",\"runId\":\"" + "x".repeat(2000) + "\"}"),
            FakeScraper.body(200, "{\"status\":\"QUEUED\",\"runId\":null}"))) {
      scraper.reset();
      scraper.answer(answer);

      assertUnavailableAndReleased(service(scraper.url(), TOKEN).refresh(TriggerSource.USER));
    }
    logs.assertNoErrors();
    assertThat(logs.everythingLogged()).doesNotContain(TOKEN).doesNotContain("someone-else");
  }

  @Test
  void ourBugsAndATokenMismatchAreLoggedAsErrors() {
    for (int status : new int[] {400, 401, 413, 415}) {
      scraper.reset();
      CapturedLogs captured = new CapturedLogs();
      captured.start();
      scraper.answer(FakeScraper.status(status));

      assertUnavailableAndReleased(service(scraper.url(), TOKEN).refresh(TriggerSource.USER));

      captured.stop();
      assertThat(captured.theOnlyError().getFormattedMessage()).contains(String.valueOf(status));
      assertThat(captured.everythingLogged()).doesNotContain(TOKEN);
    }
  }

  @Test
  void aRedirectIsNotFollowed() {
    try (FakeScraper target = new FakeScraper()) {
      scraper.answer(
          (exchange, request) -> {
            exchange.getResponseHeaders().set("Location", target.url() + "/scrape");
            exchange.sendResponseHeaders(307, -1);
          });

      assertUnavailableAndReleased(service(scraper.url(), TOKEN).refresh(TriggerSource.USER));

      assertThat(target.received()).isEmpty();
    }
  }

  @Test
  void aFailedTriggerKeepsThePreviousStatus() {
    ScrapeTriggerService service = service(scraper.url(), TOKEN);
    String previous = storeRun(CLUB, IDS, "SUCCEEDED", "");
    redis.opsForValue().set(currentKey(CLUB), previous);
    Map<Object, Object> before = redis.opsForHash().entries(runKey(previous));
    scraper.answer(FakeScraper.status(503));

    RefreshStatusResponse response = service.refresh(TriggerSource.USER);

    assertThat(response.outcome()).isEqualTo(Outcome.SCRAPER_UNAVAILABLE);
    assertThat(response.state()).isEqualTo(State.SUCCEEDED);
    assertThat(response.startedAt()).isEqualTo(Instant.ofEpochMilli(1_000));
    assertThat(response.finishedAt()).isEqualTo(Instant.ofEpochMilli(2_000));
    assertThat(response.nextRefreshAt()).isNull();
    assertThat(redis.opsForValue().get(currentKey(CLUB))).isEqualTo(previous);
    assertThat(redis.opsForHash().entries(runKey(previous))).isEqualTo(before);
  }

  @Test
  void aFailedTriggerReleasesOnlyTheLocksItStillOwns() {
    scraper.answer(
        FakeScraper.after(
            request ->
                redis.opsForValue().set(teamLockKey(IDS), "another-run", Duration.ofMinutes(5)),
            FakeScraper.status(503)));

    RefreshStatusResponse response = service(scraper.url(), TOKEN).refresh(TriggerSource.USER);

    assertThat(response.outcome()).isEqualTo(Outcome.SCRAPER_UNAVAILABLE);
    assertThat(redis.opsForValue().get(teamLockKey(IDS))).isEqualTo("another-run");
    assertThat(redis.hasKey(leagueLockKey(IDS))).isFalse();
    assertThat(redis.hasKey(runKey(scraper.received().getFirst().runId()))).isFalse();
  }

  // --- ALREADY_QUEUED -------------------------------------------------------------------------

  @Test
  void alreadyQueuedWithOneOfThisClubsRunsAdoptsIt() {
    String existing = storeRun(CLUB, IDS, "RUNNING", "");
    scraper.answer(FakeScraper.alreadyQueued(existing));

    RefreshStatusResponse response = service(scraper.url(), TOKEN).refresh(TriggerSource.USER);

    assertThat(response.outcome()).isEqualTo(Outcome.STARTED);
    assertThat(redis.opsForValue().get(currentKey(CLUB))).isEqualTo(existing);
    String ours = scraper.received().getFirst().runId();
    assertThat(redis.hasKey(runKey(ours))).isFalse();
    assertThat(redis.opsForValue().get(teamLockKey(IDS))).isEqualTo(ours);
    logs.assertNoWarningsOrAbove();
  }

  @Test
  void alreadyQueuedWithAnUnknownRunKeepsOursRunningAndWarns() {
    String otherClubs = storeRun("club-b", IDS, "RUNNING", "");
    for (String existing : new String[] {null, "unknown-run", otherClubs}) {
      redis.delete(List.of(teamLockKey(IDS), leagueLockKey(IDS)));
      scraper.reset();
      CapturedLogs captured = new CapturedLogs();
      captured.start();
      scraper.answer(FakeScraper.alreadyQueued(existing));

      RefreshStatusResponse response = service(scraper.url(), TOKEN).refresh(TriggerSource.USER);

      String ours = scraper.received().getFirst().runId();
      assertThat(response.outcome()).isEqualTo(Outcome.STARTED);
      assertThat(response.state()).isEqualTo(State.RUNNING);
      assertThat(redis.opsForValue().get(currentKey(CLUB))).isEqualTo(ours);
      assertThat(redis.opsForHash().entries(runKey(ours))).containsEntry("state", "RUNNING");
      captured.stop();
      assertThat(captured.warningsOrAbove()).hasSize(1);
    }
  }

  // --- Redis down ------------------------------------------------------------------------------

  @Test
  void withoutRedisTheScraperIsNeverCalled() throws Exception {
    LettuceConnectionFactory dead = connectionTo("127.0.0.1", closedPort());
    try {
      ScrapeTriggerService service =
          service(
              scraper.url(),
              TOKEN,
              new StringRedisTemplate(dead),
              properties(scraper.url(), TOKEN));

      assertThatThrownBy(() -> service.refresh(TriggerSource.USER))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(scraper.received()).isEmpty();
    } finally {
      dead.destroy();
    }
  }

  // --- helpers --------------------------------------------------------------------------------

  private void assertUnavailableAndReleased(RefreshStatusResponse response) {
    assertThat(response.outcome()).isEqualTo(Outcome.SCRAPER_UNAVAILABLE);
    assertThat(response.state()).isEqualTo(State.IDLE);
    assertThat(response.nextRefreshAt()).isNull();
    assertThat(redis.keys("scrape:lock:*")).isEmpty();
    assertThat(redis.keys("scrape:run:*")).isEmpty();
    assertThat(redis.hasKey(currentKey(CLUB))).isFalse();
    // The site was never asked, so a retry reaches the scraper at once.
    try (FakeScraper retry = new FakeScraper()) {
      assertThat(service(retry.url(), TOKEN).refresh(TriggerSource.USER).outcome())
          .isEqualTo(Outcome.STARTED);
      assertThat(retry.received()).hasSize(1);
    }
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  /** A run record as the trigger writes it, started at 1 s and (if ended) finished at 2 s. */
  static String storeRun(String clubId, FootballOrgIlIds ids, String state, String reason) {
    String runId = UUID.randomUUID().toString();
    redis
        .opsForHash()
        .putAll(
            runKey(runId),
            Map.of(
                "clubId",
                clubId,
                "teamId",
                String.valueOf(ids.teamId()),
                "leagueId",
                String.valueOf(ids.leagueId()),
                "seasonId",
                String.valueOf(ids.seasonId()),
                "fixtures",
                "1",
                "standings",
                "1",
                "source",
                "USER",
                "state",
                state,
                "startedAt",
                "1000",
                "failureReason",
                reason));
    if (state.equals("SUCCEEDED") || state.equals("FAILED")) {
      redis
          .opsForHash()
          .putAll(runKey(runId), Map.of("finishedAt", "2000", "finishedBy", "RESULT"));
    } else {
      // Started now, so it isn't timed out.
      redis
          .opsForHash()
          .put(runKey(runId), "startedAt", String.valueOf(System.currentTimeMillis()));
    }
    return runId;
  }

  private ScrapeTriggerService service(String url, String token) {
    return service(url, token, redis, properties(url, token));
  }

  private ScrapeTriggerService service(
      String url, String token, StringRedisTemplate template, ScraperProperties properties) {
    ScrapeRunStatusService statusService = new ScrapeRunStatusService(template, properties);
    return new ScrapeTriggerService(
        clubContext,
        mappings,
        template,
        properties,
        new ScraperClient(properties, JsonMapper.builder().build()),
        statusService);
  }

  private static ScraperProperties properties(String url, String token) {
    return new ScraperProperties(token, url, null, null);
  }

  private static int closedPort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static LettuceConnectionFactory connectionTo(String host, int port) {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }
}
