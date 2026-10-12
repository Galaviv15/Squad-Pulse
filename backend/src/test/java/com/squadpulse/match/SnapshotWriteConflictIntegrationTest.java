package com.squadpulse.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.CapturedLogs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * A snapshot write that loses its race twice (KAN-66) is a {@code 409 CONCURRENT_MODIFICATION} over
 * the real chain — whether the database answered with a duplicate key or a version mismatch — not a
 * 500, and nothing is logged at ERROR.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + SnapshotWriteConflictIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import({TestClubMappings.class, StandingsSaveFailures.class})
class SnapshotWriteConflictIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private TestClubMappings clubs;
  @Autowired private StandingsSaveFailures saveFailures;

  private final CapturedLogs logs = new CapturedLogs();

  @BeforeEach
  void setUp() {
    clubs.createClub("Sderot", 7617, 1010, 28);
    logs.start();
  }

  @AfterEach
  void tearDown() {
    logs.stop();
    saveFailures.disarm();
    mongoTemplate.getCollection("league_standings").deleteMany(new Document());
    clubs.deleteAllClubs();
  }

  @ParameterizedTest
  @ValueSource(strings = {"duplicate key", "version mismatch"})
  void aSecondLostRaceIsA409NotA500(String kind) throws Exception {
    Supplier<RuntimeException> failure =
        kind.equals("duplicate key")
            ? () -> new DuplicateKeyException("E11000 duplicate key (simulated)")
            : () -> new OptimisticLockingFailureException("version mismatch (simulated)");
    saveFailures.failNextSaves(2, failure);

    mockMvc
        .perform(
            post("/internal/scrape-results")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(standingsOnly()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

    logs.assertNoErrors();
    assertThat(mongoTemplate.getCollection("league_standings").countDocuments()).isZero();
  }

  private static String standingsOnly() {
    String fetchedAt = Instant.now().minus(1, ChronoUnit.HOURS).toString();
    return """
        {"teamId":7617,"leagueId":1010,"seasonId":28,
         "standings":{"status":"OK","fetchedAt":"%s","round":1,"rows":[
           {"position":1,"teamId":7001,"teamName":"Tira","played":1,"won":1,"drawn":0,"lost":0,
            "goalsFor":2,"goalsAgainst":1,"points":3},
           {"position":2,"teamId":7617,"teamName":"Sderot","played":1,"won":0,"drawn":0,"lost":1,
            "goalsFor":1,"goalsAgainst":2,"points":0}]}}
        """
        .formatted(fetchedAt);
  }
}
