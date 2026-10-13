package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapePayloads.FETCHED_AT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEAGUE;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SEASON;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA;
import static com.squadpulse.scrapingintegration.ScrapePayloads.failed;
import static com.squadpulse.scrapingintegration.ScrapePayloads.fixtures;
import static com.squadpulse.scrapingintegration.ScrapePayloads.games;
import static com.squadpulse.scrapingintegration.ScrapePayloads.json;
import static com.squadpulse.scrapingintegration.ScrapePayloads.request;
import static com.squadpulse.scrapingintegration.ScrapePayloads.standings;
import static com.squadpulse.scrapingintegration.ScrapeRunStatusService.leagueLockKey;
import static com.squadpulse.scrapingintegration.ScrapeRunStatusService.runKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.redis.testcontainers.RedisContainer;
import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.CapturedLogs;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The refresh flow end to end (KAN-68): {@code POST} / {@code GET /matches/refresh} behind the real
 * user chain, the scraper's ingest behind the internal chain, a real MongoDB and Redis, and a real
 * local HTTP server as the scraper. Who may call it, club isolation, the locks (double click,
 * cooldown, the shared league), and every status a result or the timeout sets.
 *
 * <p>Short durations ({@value #COOLDOWN_MS} ms cooldown, {@value #TIMEOUT_MS} ms run timeout) so
 * the cooldown and the timeout can run out inside a test.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + RefreshApiIntegrationTest.SERVICE_TOKEN,
      "squadpulse.scraper.refresh-cooldown=" + RefreshApiIntegrationTest.COOLDOWN_MS + "ms",
      "squadpulse.scraper.run-timeout=" + RefreshApiIntegrationTest.TIMEOUT_MS + "ms"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import({TestAccessTokens.class, TestClubMappings.class})
class RefreshApiIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";
  static final long COOLDOWN_MS = 4000;
  static final long TIMEOUT_MS = 2500;

  private static final String REFRESH = "/matches/refresh";
  private static final String INGEST = "/internal/scrape-results";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FootballOrgIlIds SDEROT_IDS = new FootballOrgIlIds(SDEROT, LEAGUE, SEASON);

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @Container
  static final RedisContainer REDIS_CONTAINER =
      new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  static final FakeScraper SCRAPER = new FakeScraper();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
    registry.add("spring.data.redis.host", REDIS_CONTAINER::getRedisHost);
    registry.add("spring.data.redis.port", REDIS_CONTAINER::getRedisPort);
    registry.add("spring.data.redis.password", () -> "");
    registry.add("squadpulse.scraper.url", SCRAPER::url);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private StringRedisTemplate redis;
  @Autowired private TestAccessTokens tokens;
  @Autowired private TestClubMappings clubs;
  @Autowired private ScrapeRunStatusService statusService;

  private final CapturedLogs logs = new CapturedLogs();

  /** Sderot's club. */
  private String clubA;

  /** Tira's club: the same league and season. */
  private String clubB;

  @BeforeEach
  void setUp() {
    logs.start();
    SCRAPER.reset();
    clubA = clubs.createClub("Sderot", SDEROT, LEAGUE, SEASON);
    clubB = clubs.createClub("Tira", TIRA, LEAGUE, SEASON);
  }

  @AfterEach
  void tearDown() {
    logs.stop();
    mongoTemplate.getCollection("league_fixtures").deleteMany(new Document());
    mongoTemplate.getCollection("league_standings").deleteMany(new Document());
    mongoTemplate.getCollection("player_season_stats").deleteMany(new Document());
    clubs.deleteAllClubs();
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @AfterAll
  static void stopScraper() {
    SCRAPER.close();
  }

  // --- who may call it -------------------------------------------------------------------------

  @Test
  void onlyEditFullAndUpMayStartARefresh() throws Exception {
    mockMvc.perform(post(REFRESH)).andExpect(status().isUnauthorized());
    for (PermissionLevel level : List.of(PermissionLevel.VIEW_ONLY, PermissionLevel.EDIT_PARTIAL)) {
      mockMvc
          .perform(post(REFRESH).header("Authorization", tokens.bearer(clubA, level)))
          .andExpect(status().isForbidden());
    }
    assertThat(SCRAPER.received()).isEmpty();

    refresh(clubA, PermissionLevel.EDIT_FULL).andExpect(status().isAccepted());
    refresh(clubB, PermissionLevel.ADMIN).andExpect(status().isAccepted());
    assertThat(SCRAPER.received()).hasSize(2);
  }

  @Test
  void viewOnlyMayReadTheStatus() throws Exception {
    mockMvc.perform(get(REFRESH)).andExpect(status().isUnauthorized());
    statusOf(clubA)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").isEmpty())
        .andExpect(jsonPath("$.state").value("IDLE"));
  }

  @Test
  void theServiceTokenOpensNeither() throws Exception {
    mockMvc
        .perform(post(REFRESH).header("Authorization", "Bearer " + SERVICE_TOKEN))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(REFRESH).header("Authorization", "Bearer " + SERVICE_TOKEN))
        .andExpect(status().isUnauthorized());
    assertThat(SCRAPER.received()).isEmpty();
  }

  // --- isolation -------------------------------------------------------------------------------

  @Test
  void requestParametersChangeNothing() throws Exception {
    mockMvc
        .perform(
            post(REFRESH)
                .param("teamId", "999")
                .param("leagueId", "998")
                .param("seasonId", "997")
                .param("clubId", clubB)
                .header("Authorization", tokens.bearer(clubA, PermissionLevel.EDIT_FULL)))
        .andExpect(status().isAccepted());

    JsonNode body = SCRAPER.received().getFirst().body();
    assertThat(body.get("teamId").intValue()).isEqualTo(SDEROT);
    assertThat(body.get("leagueId").intValue()).isEqualTo(LEAGUE);
    assertThat(body.get("seasonId").intValue()).isEqualTo(SEASON);
    statusOf(clubB, "?clubId=" + clubA).andExpect(jsonPath("$.state").value("IDLE"));
  }

  @Test
  void neitherResponseCarriesAnIdTheRunIdOrTheUrl() throws Exception {
    List<String> bodies = new ArrayList<>();
    bodies.add(refreshBody(clubA));
    String runId = SCRAPER.received().getFirst().runId();
    ingest(
            withRunId(
                runId,
                request(SDEROT, LEAGUE, SEASON, failed("SOURCE_BLOCKED", "secret-detail"), null)))
        .andExpect(status().isOk());
    bodies.add(statusBody(clubA));
    bodies.add(refreshBody(clubA));

    for (String body : bodies) {
      assertThat(body)
          .doesNotContain(runId)
          .doesNotContain(String.valueOf(SDEROT))
          .doesNotContain(String.valueOf(LEAGUE))
          .doesNotContain(SCRAPER.url())
          .doesNotContain("127.0.0.1")
          .doesNotContain("secret-detail")
          .doesNotContain("scrape:")
          .doesNotContain(clubA);
    }
    assertThat(JSON.readTree(bodies.get(0)).propertyNames())
        .containsExactly(
            "outcome", "state", "failureReason", "startedAt", "finishedAt", "nextRefreshAt");
  }

  @Test
  void eachClubSeesOnlyItsOwnRunAndAResultChangesOnlyItsOwnRun() throws Exception {
    refresh(clubA, PermissionLevel.EDIT_FULL).andExpect(status().isAccepted());
    String runA = SCRAPER.received().getFirst().runId();

    statusOf(clubB).andExpect(jsonPath("$.state").value("IDLE"));
    ingest(withRunId(runA, allThree())).andExpect(status().isOk());

    statusOf(clubA).andExpect(jsonPath("$.state").value("SUCCEEDED"));
    statusOf(clubB)
        .andExpect(jsonPath("$.state").value("IDLE"))
        .andExpect(jsonPath("$.startedAt").isEmpty());
  }

  @Test
  void aResultWhoseIdsDontMatchItsRunChangesNothing() throws Exception {
    refresh(clubA, PermissionLevel.EDIT_FULL).andExpect(status().isAccepted());
    String runA = SCRAPER.received().getFirst().runId();

    for (Map<String, Object> request :
        List.of(
            request(SDEROT, LEAGUE, SEASON + 1, fixtures(FETCHED_AT), null),
            request(TIRA, LEAGUE, SEASON, null, standings(FETCHED_AT)))) {
      ingest(withRunId(runA, request)).andExpect(status().isOk());
    }

    statusOf(clubA).andExpect(jsonPath("$.state").value("RUNNING"));
    statusOf(clubB).andExpect(jsonPath("$.state").value("IDLE"));
    assertThat(logs.warningsOrAbove())
        .filteredOn(e -> e.getFormattedMessage().contains("no status recorded"))
        .hasSize(2);
  }

  // --- the locks -------------------------------------------------------------------------------

  @Test
  void aDoubleClickCallsTheScraperOnce() throws Exception {
    SCRAPER.answer(FakeScraper.after(request -> Thread.sleep(300), FakeScraper.queued()));
    int clicks = 10;
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(clicks);
    List<Future<MockHttpServletResponse>> responses = new ArrayList<>();
    String bearer = tokens.bearer(clubA, PermissionLevel.EDIT_FULL);
    try {
      for (int i = 0; i < clicks; i++) {
        Callable<MockHttpServletResponse> click =
            () -> {
              go.await();
              return mockMvc
                  .perform(post(REFRESH).header("Authorization", bearer))
                  .andReturn()
                  .getResponse();
            };
        responses.add(pool.submit(click));
      }
      go.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<MockHttpServletResponse> response : responses) {
        MockHttpServletResponse answer = response.get();
        String outcome = JSON.readTree(answer.getContentAsString()).get("outcome").stringValue();
        outcomes.add(outcome);
        assertThat(answer.getStatus()).isEqualTo(outcome.equals("STARTED") ? 202 : 200);
      }

      assertThat(SCRAPER.received()).hasSize(1);
      assertThat(outcomes).containsOnlyOnce("STARTED");
      assertThat(outcomes).filteredOn("ALREADY_RUNNING"::equals).hasSize(clicks - 1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aRefreshWithinTheCooldownIsRefusedAndOneAfterItStarts() throws Exception {
    refresh(clubA, PermissionLevel.EDIT_FULL).andExpect(status().isAccepted());
    String first = SCRAPER.received().getFirst().runId();
    ingest(withRunId(first, allThree())).andExpect(status().isOk());

    Instant before = Instant.now();
    String cooldown = refreshBody(clubA);
    JsonNode node = JSON.readTree(cooldown);
    assertThat(node.get("outcome").stringValue()).isEqualTo("COOLDOWN");
    assertThat(node.get("state").stringValue()).isEqualTo("SUCCEEDED");
    Instant next = Instant.parse(node.get("nextRefreshAt").stringValue());
    assertThat(next).isAfter(before).isBeforeOrEqualTo(before.plusMillis(COOLDOWN_MS + 100));
    statusOf(clubA)
        .andExpect(jsonPath("$.outcome").isEmpty())
        .andExpect(jsonPath("$.nextRefreshAt").isNotEmpty());
    assertThat(SCRAPER.received()).hasSize(1);

    await()
        .atMost(Duration.ofMillis(COOLDOWN_MS + 2000))
        .until(() -> JSON.readTree(statusBody(clubA)).get("nextRefreshAt").isNull());
    refresh(clubA, PermissionLevel.EDIT_FULL)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.outcome").value("STARTED"))
        .andExpect(jsonPath("$.state").value("RUNNING"));
    assertThat(SCRAPER.received()).hasSize(2);

    // The same data again: every part STALE, which is still a success.
    String second = SCRAPER.received().get(1).runId();
    ingest(withRunId(second, allThree()))
        .andExpect(jsonPath("$.fixtures.outcome").value("STALE"))
        .andExpect(jsonPath("$.standings.outcome").value("STALE"))
        .andExpect(jsonPath("$.playerStats.outcome").value("STALE"));
    statusOf(clubA).andExpect(jsonPath("$.state").value("SUCCEEDED"));
  }

  @Test
  void aSecondClubInTheLeagueGetsFixturesOnlyWithoutBeingTold() throws Exception {
    String a = refreshBody(clubA);
    String b = refreshBody(clubB);

    List<FakeScraper.Received> received = SCRAPER.received();
    assertThat(received).hasSize(2);
    assertThat(received.get(0).body().get("teamId").intValue()).isEqualTo(SDEROT);
    assertThat(received.get(0).body().get("standings").booleanValue()).isTrue();
    assertThat(received.get(1).body().get("teamId").intValue()).isEqualTo(TIRA);
    assertThat(received.get(1).body().get("fixtures").booleanValue()).isTrue();
    assertThat(received.get(1).body().get("standings").booleanValue()).isFalse();

    // B's answer looks exactly like A's, which had the league to itself.
    assertThat(shape(b)).isEqualTo(shape(a));
  }

  // --- status from results ---------------------------------------------------------------------

  @Test
  void everyPartStoredIsASuccess() throws Exception {
    String runId = start(clubA);
    Instant before = Instant.now();

    ingest(withRunId(runId, allThree())).andExpect(status().isOk());

    JsonNode node = JSON.readTree(statusBody(clubA));
    assertThat(node.get("state").stringValue()).isEqualTo("SUCCEEDED");
    assertThat(node.get("failureReason").isNull()).isTrue();
    assertThat(Instant.parse(node.get("finishedAt").stringValue()))
        .isAfterOrEqualTo(before.minusMillis(1))
        .isAfterOrEqualTo(Instant.parse(node.get("startedAt").stringValue()));
  }

  @Test
  void theSameResultTwiceGivesTheSameStatus() throws Exception {
    String runId = start(clubA);
    ingest(withRunId(runId, allThree())).andExpect(status().isOk());
    String once = statusBody(clubA);

    ingest(withRunId(runId, allThree())).andExpect(status().isOk());

    JsonNode first = JSON.readTree(once);
    JsonNode second = JSON.readTree(statusBody(clubA));
    for (String field : List.of("state", "failureReason", "startedAt", "finishedAt")) {
      assertThat(second.get(field)).as(field).isEqualTo(first.get(field));
    }
  }

  @Test
  void aBlockedFixturesPageFailsTheRun() throws Exception {
    String runId = start(clubA);

    ingest(
            withRunId(
                runId,
                request(
                    SDEROT,
                    LEAGUE,
                    SEASON,
                    failed("SOURCE_BLOCKED", "403"),
                    standings(FETCHED_AT))))
        .andExpect(status().isOk());

    statusOf(clubA)
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("SOURCE_BLOCKED"))
        .andExpect(jsonPath("$.finishedAt").isNotEmpty());
  }

  @Test
  void theScrapersReasonsMapToTheStatusReasons() throws Exception {
    for (String[] pair :
        new String[][] {
          {"TIMEOUT", "SOURCE_TIMEOUT"},
          {"SOURCE_ERROR", "SOURCE_ERROR"},
          {"PARSE_ERROR", "PARSE_ERROR"}
        }) {
      redis.getConnectionFactory().getConnection().serverCommands().flushAll();
      String runId = start(clubA);

      ingest(
              withRunId(
                  runId,
                  request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), failed(pair[0], null))))
          .andExpect(status().isOk());

      statusOf(clubA)
          .andExpect(jsonPath("$.state").value("FAILED"))
          .andExpect(jsonPath("$.failureReason").value(pair[1]));
    }
  }

  @Test
  void rejectedFixturesFailTheRun() throws Exception {
    String runId = start(clubA);
    Map<String, Object> request = allThree();
    List<Map<String, Object>> games = games(request);
    games.add(games.getFirst());

    ingest(withRunId(runId, request))
        .andExpect(jsonPath("$.fixtures.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"));

    statusOf(clubA)
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("REJECTED"));
  }

  /**
   * Through the real endpoint, standings are rejected only when no club is in the league — and then
   * the fixtures are too, and come first. So the standings' own reason is checked on the status
   * hook directly, with fixtures stored.
   */
  @Test
  void rejectedStandingsFailTheRun() throws Exception {
    String runId = start(clubA);

    statusService.recordResult(
        new ScrapeResultRequest(runId, SDEROT, LEAGUE, SEASON, null, null, null),
        new ScrapeResultResponse(
            new ScrapeResultResponse.PartOutcome(ScrapeResultResponse.Outcome.STORED, null),
            new ScrapeResultResponse.PartOutcome(
                ScrapeResultResponse.Outcome.REJECTED,
                com.squadpulse.match.IngestResult.RejectionReason.NOT_MAPPED),
            new ScrapeResultResponse.PartOutcome(ScrapeResultResponse.Outcome.NOT_SENT, null),
            1,
            List.of()));

    statusOf(clubA)
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("REJECTED"));
  }

  @Test
  void aFixturesFailureIsReportedBeforeAStandingsFailure() throws Exception {
    String runId = start(clubA);

    ingest(
            withRunId(
                runId,
                request(
                    SDEROT,
                    LEAGUE,
                    SEASON,
                    failed("PARSE_ERROR", null),
                    failed("SOURCE_BLOCKED", null))))
        .andExpect(status().isOk());

    statusOf(clubA).andExpect(jsonPath("$.failureReason").value("PARSE_ERROR"));
  }

  @Test
  void aRequestedPartThatWasntSentFailsTheRun() throws Exception {
    String runId = start(clubA);

    ingest(withRunId(runId, request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), null)))
        .andExpect(status().isOk());

    statusOf(clubA)
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("NO_RESULT"));
  }

  @Test
  void aRunWithoutStandingsSucceedsOnItsFixtures() throws Exception {
    redis.opsForValue().set(leagueLockKey(SDEROT_IDS), "another-run", Duration.ofMinutes(5));
    String runId = start(clubA);
    assertThat(SCRAPER.received().getFirst().body().get("standings").booleanValue()).isFalse();

    ingest(
            withRunId(
                runId,
                request(
                    SDEROT,
                    LEAGUE,
                    SEASON,
                    fixtures(FETCHED_AT),
                    null,
                    ScrapePayloads.playerStats(FETCHED_AT))))
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"));

    statusOf(clubA).andExpect(jsonPath("$.state").value("SUCCEEDED"));
  }

  @Test
  void anInvalidResultChangesNoStatus() throws Exception {
    String runId = start(clubA);
    Map<String, Object> request = ScrapePayloads.full();
    games(request).clear();

    ingest(withRunId(runId, request)).andExpect(status().isBadRequest());

    statusOf(clubA).andExpect(jsonPath("$.state").value("RUNNING"));
  }

  @Test
  void aRunWithoutAResultTimesOutAndALateResultStillCounts() throws Exception {
    String runId = start(clubA);

    await()
        .atMost(Duration.ofMillis(TIMEOUT_MS + 2000))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> JSON.readTree(statusBody(clubA)).get("state").stringValue().equals("FAILED"));
    statusOf(clubA)
        .andExpect(jsonPath("$.failureReason").value("NO_RESULT"))
        .andExpect(jsonPath("$.finishedAt").isNotEmpty());
    assertThat(logs.events())
        .anyMatch(e -> e.getFormattedMessage().contains(runId + " is now FAILED (NO_RESULT)"));

    ingest(withRunId(runId, allThree())).andExpect(status().isOk());

    statusOf(clubA)
        .andExpect(jsonPath("$.state").value("SUCCEEDED"))
        .andExpect(jsonPath("$.failureReason").isEmpty());
  }

  @Test
  void aResultBeforeTheTriggerReturnsIsNotOverwritten() throws Exception {
    SCRAPER.answer(
        FakeScraper.after(
            request -> ingest(withRunId(request.runId(), allThree())).andExpect(status().isOk()),
            FakeScraper.queued()));

    refresh(clubA, PermissionLevel.EDIT_FULL)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.outcome").value("STARTED"))
        .andExpect(jsonPath("$.state").value("SUCCEEDED"));

    statusOf(clubA).andExpect(jsonPath("$.state").value("SUCCEEDED"));
  }

  @Test
  void aChangedMappingShowsIdle() throws Exception {
    String runId = start(clubA);
    ingest(withRunId(runId, allThree())).andExpect(status().isOk());

    clubs.map(clubA, SDEROT, LEAGUE, SEASON + 1);

    statusOf(clubA)
        .andExpect(jsonPath("$.state").value("IDLE"))
        .andExpect(jsonPath("$.startedAt").isEmpty())
        .andExpect(jsonPath("$.nextRefreshAt").isEmpty());
    refresh(clubA, PermissionLevel.EDIT_FULL).andExpect(status().isAccepted());
    assertThat(SCRAPER.received().get(1).body().get("seasonId").intValue()).isEqualTo(SEASON + 1);
  }

  @Test
  void anUnmappedClubIsToldSo() throws Exception {
    String unmapped = clubs.createClub("No mapping");

    refresh(unmapped, PermissionLevel.ADMIN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("NOT_MAPPED"))
        .andExpect(jsonPath("$.state").value("NOT_MAPPED"));
    statusOf(unmapped)
        .andExpect(jsonPath("$.outcome").isEmpty())
        .andExpect(jsonPath("$.state").value("NOT_MAPPED"));
    assertThat(SCRAPER.received()).isEmpty();
    assertThat(redis.keys("*")).isEmpty();
  }

  @Test
  void anUnavailableScraperAnswers200AndKeepsTheStatus() throws Exception {
    SCRAPER.answer(FakeScraper.status(503));

    refresh(clubA, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("SCRAPER_UNAVAILABLE"))
        .andExpect(jsonPath("$.state").value("IDLE"))
        .andExpect(jsonPath("$.nextRefreshAt").isEmpty());
    assertThat(logs.everythingLogged()).doesNotContain(SERVICE_TOKEN);
  }

  // --- player statistics (KAN-72, requested since KAN-73) ------------------------------------

  /**
   * KAN-73: every trigger asks for the stats ({@code playerStats: true}, the seventh property,
   * which the scraper accepts as optional), and the run record says they were requested.
   */
  @Test
  void theTriggerAsksForTheStatsAndTheRunCountsThem() throws Exception {
    String runId = start(clubA);

    JsonNode body = SCRAPER.received().getFirst().body();
    assertThat(body.propertyNames())
        .containsExactly(
            "runId", "teamId", "leagueId", "seasonId", "fixtures", "standings", "playerStats");
    assertThat(body.get("playerStats").booleanValue()).isTrue();
    assertThat(redis.opsForHash().get(runKey(runId), "playerStats")).isEqualTo("1");
  }

  @Test
  void aRunThatRequestedStatsSucceedsWhenTheyreStoredOrAlreadyUpToDate() throws Exception {
    String runId = start(clubA);
    ingest(withRunId(runId, allThree()))
        .andExpect(jsonPath("$.playerStats.outcome").value("STORED"));
    statusOf(clubA).andExpect(jsonPath("$.state").value("SUCCEEDED"));

    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    String again = start(clubA);
    ingest(withRunId(again, allThree()))
        .andExpect(jsonPath("$.playerStats.outcome").value("STALE"));
    statusOf(clubA).andExpect(jsonPath("$.state").value("SUCCEEDED"));
  }

  @Test
  void aFailedStatsPartFailsARunThatRequestedIt() throws Exception {
    String runId = start(clubA);
    Map<String, Object> result = allThree();
    result.put("playerStats", failed("PARSE_ERROR", null));

    ingest(withRunId(runId, result)).andExpect(status().isOk());

    statusOf(clubA)
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("PARSE_ERROR"));
  }

  @Test
  void missingStatsFailARunThatRequestedThem() throws Exception {
    String runId = start(clubA);

    ingest(withRunId(runId, ScrapePayloads.full())).andExpect(status().isOk());

    statusOf(clubA)
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("NO_RESULT"));
  }

  /**
   * Through the real endpoint, stats are rejected only when no club is mapped — and then the
   * fixtures are too, and come first. So the stats' own reason is checked on the hook directly.
   */
  @Test
  void rejectedStatsFailARunThatRequestedThem() throws Exception {
    String runId = start(clubA);

    statusService.recordResult(
        new ScrapeResultRequest(runId, SDEROT, LEAGUE, SEASON, null, null, null),
        new ScrapeResultResponse(
            outcome(ScrapeResultResponse.Outcome.STORED),
            outcome(ScrapeResultResponse.Outcome.STORED),
            new ScrapeResultResponse.PartOutcome(
                ScrapeResultResponse.Outcome.REJECTED,
                com.squadpulse.match.IngestResult.RejectionReason.NOT_MAPPED),
            1,
            List.of()));

    statusOf(clubA)
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("REJECTED"));
  }

  @Test
  void aFixturesFailureIsReportedBeforeAStatsFailure() throws Exception {
    String runId = start(clubA);

    ingest(
            withRunId(
                runId,
                request(
                    SDEROT,
                    LEAGUE,
                    SEASON,
                    failed("SOURCE_BLOCKED", null),
                    standings(FETCHED_AT),
                    failed("PARSE_ERROR", null))))
        .andExpect(status().isOk());

    statusOf(clubA).andExpect(jsonPath("$.failureReason").value("SOURCE_BLOCKED"));
  }

  /**
   * A run that didn't ask for the stats (flag {@code "0"}, as every run before KAN-73) is judged on
   * the other parts, whatever the stats say.
   */
  @Test
  void aRunThatDidntRequestStatsIgnoresThem() throws Exception {
    String runId = start(clubA);
    redis.opsForHash().put(runKey(runId), "playerStats", "0");
    Map<String, Object> result = allThree();
    result.put("playerStats", failed("SOURCE_BLOCKED", null));

    ingest(withRunId(runId, result)).andExpect(status().isOk());

    statusOf(clubA).andExpect(jsonPath("$.state").value("SUCCEEDED"));
  }

  /** A run record written before KAN-72 has no {@code playerStats} field: not requested. */
  @Test
  void anOldRunRecordWithoutTheFlagIgnoresTheStats() throws Exception {
    String withStats = start(clubA);
    redis.opsForHash().delete(runKey(withStats), "playerStats");
    Map<String, Object> result = allThree();
    result.put("playerStats", failed("TIMEOUT", null));
    ingest(withRunId(withStats, result)).andExpect(status().isOk());
    statusOf(clubA).andExpect(jsonPath("$.state").value("SUCCEEDED"));

    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    String withoutStats = start(clubA);
    redis.opsForHash().delete(runKey(withoutStats), "playerStats");
    ingest(withRunId(withoutStats, ScrapePayloads.full())).andExpect(status().isOk());
    statusOf(clubA).andExpect(jsonPath("$.state").value("SUCCEEDED"));
  }

  // --- helpers -------------------------------------------------------------------------------

  private static Map<String, Object> allThree() {
    return request(
        SDEROT,
        LEAGUE,
        SEASON,
        fixtures(FETCHED_AT),
        standings(FETCHED_AT),
        ScrapePayloads.playerStats(FETCHED_AT));
  }

  private static ScrapeResultResponse.PartOutcome outcome(ScrapeResultResponse.Outcome outcome) {
    return new ScrapeResultResponse.PartOutcome(outcome, null);
  }

  private String start(String clubId) throws Exception {
    int before = SCRAPER.received().size();
    refresh(clubId, PermissionLevel.EDIT_FULL).andExpect(status().isAccepted());
    return SCRAPER.received().get(before).runId();
  }

  private ResultActions refresh(String clubId, PermissionLevel level) throws Exception {
    return mockMvc.perform(post(REFRESH).header("Authorization", tokens.bearer(clubId, level)));
  }

  private String refreshBody(String clubId) throws Exception {
    return refresh(clubId, PermissionLevel.EDIT_FULL)
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private ResultActions statusOf(String clubId) throws Exception {
    return statusOf(clubId, "");
  }

  private ResultActions statusOf(String clubId, String query) throws Exception {
    return mockMvc.perform(
        get(REFRESH + query)
            .header("Authorization", tokens.bearer(clubId, PermissionLevel.VIEW_ONLY)));
  }

  private String statusBody(String clubId) throws Exception {
    return statusOf(clubId).andReturn().getResponse().getContentAsString();
  }

  private ResultActions ingest(Object body) throws Exception {
    return mockMvc.perform(
        post(INGEST)
            .header("Authorization", "Bearer " + SERVICE_TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
  }

  private static Map<String, Object> withRunId(String runId, Map<String, Object> request) {
    request.put("runId", runId);
    return request;
  }

  /** Which fields are set, and the outcome and state: what a response may tell. */
  private static String shape(String body) {
    JsonNode node = JSON.readTree(body);
    StringBuilder shape = new StringBuilder();
    for (String field : node.propertyNames()) {
      JsonNode value = node.get(field);
      shape.append(field).append('=');
      if (field.equals("outcome") || field.equals("state") || value.isNull()) {
        shape.append(value);
      } else {
        shape.append("<set>");
      }
      shape.append(';');
    }
    return shape.toString();
  }
}
