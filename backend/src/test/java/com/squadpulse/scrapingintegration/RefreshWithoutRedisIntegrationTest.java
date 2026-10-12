package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapePayloads.LEAGUE;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SEASON;
import static com.squadpulse.scrapingintegration.ScrapePayloads.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.CapturedLogs;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The refresh flow with Redis unreachable (KAN-68): a refresh fails as a 5xx and never calls the
 * scraper without its locks, and the scraper's ingest still answers 200 with its outcomes — the
 * data is stored; only recording the run's status failed (a WARN).
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + RefreshWithoutRedisIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import({TestAccessTokens.class, TestClubMappings.class})
class RefreshWithoutRedisIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  static final FakeScraper SCRAPER = new FakeScraper();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
    registry.add("spring.data.redis.host", () -> "127.0.0.1");
    registry.add("spring.data.redis.port", RefreshWithoutRedisIntegrationTest::closedPort);
    registry.add("squadpulse.scraper.url", SCRAPER::url);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private TestAccessTokens tokens;
  @Autowired private TestClubMappings clubs;

  private final CapturedLogs logs = new CapturedLogs();
  private String club;

  @BeforeEach
  void setUp() {
    logs.start();
    club = clubs.createClub("Sderot", SDEROT, LEAGUE, SEASON);
  }

  @AfterEach
  void tearDown() {
    logs.stop();
    mongoTemplate.getCollection("league_fixtures").deleteMany(new Document());
    mongoTemplate.getCollection("league_standings").deleteMany(new Document());
    clubs.deleteAllClubs();
  }

  @AfterAll
  static void stopScraper() {
    SCRAPER.close();
  }

  @Test
  void aRefreshIsA5xxAndNeverReachesTheScraper() throws Exception {
    mockMvc
        .perform(
            post("/matches/refresh")
                .header("Authorization", tokens.bearer(club, PermissionLevel.EDIT_FULL)))
        .andExpect(status().is5xxServerError());

    assertThat(SCRAPER.received()).isEmpty();
  }

  @Test
  void theIngestStillAnswersWithItsOutcomes() throws Exception {
    Map<String, Object> request = ScrapePayloads.full();
    request.put("runId", "a-run");

    mockMvc
        .perform(
            post("/internal/scrape-results")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"));

    assertThat(logs.warningsOrAbove())
        .singleElement()
        .satisfies(
            event ->
                assertThat(event.getFormattedMessage())
                    .contains("Couldn't record the status of scrape run a-run")
                    .contains("team 7617 league 1010 season 28"));
  }

  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
