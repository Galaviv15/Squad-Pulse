package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.TestClubMappings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.JsonNode;

/**
 * Keeps docs/scraper-backend-contract.md honest (KAN-66): the example request is read from the doc
 * itself — the fenced block after {@code <!-- contract-example-request -->} — and posted through
 * the real chain with the service token, and the real answer must equal the block after {@code <!--
 * contract-example-response -->}. KAN-67 builds against that doc.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + ContractExampleIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestClubMappings.class)
class ContractExampleIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  /** Surefire runs in backend/, so the repo's docs are one level up. */
  static final Path CONTRACT = Path.of("..", "docs", "scraper-backend-contract.md");

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

  @AfterEach
  void tearDown() {
    mongoTemplate.getCollection("league_fixtures").deleteMany(new Document());
    mongoTemplate.getCollection("league_standings").deleteMany(new Document());
    mongoTemplate.getCollection("player_season_stats").deleteMany(new Document());
    mongoTemplate.getCollection("game_details").deleteMany(new Document());
    mongoTemplate.getCollection("game_detail_attempts").deleteMany(new Document());
    clubs.deleteAllClubs();
  }

  @Test
  void theDocumentedExampleIsAcceptedAndAnsweredAsDocumented() throws Exception {
    String doc = Files.readString(CONTRACT, StandardCharsets.UTF_8);
    String requestJson = fencedBlockAfter(doc, "<!-- contract-example-request -->");
    JsonNode request = ScrapePayloads.JSON.readTree(requestJson);
    JsonNode expected =
        ScrapePayloads.JSON.readTree(fencedBlockAfter(doc, "<!-- contract-example-response -->"));
    // The example's dates are fixed; validation allows nothing more than 5 minutes ahead.
    for (String part : new String[] {"fixtures", "standings"}) {
      assertThat(Instant.parse(request.get(part).get("fetchedAt").asString()))
          .as("the example's %s.fetchedAt must not be in the future", part)
          .isBefore(Instant.now());
    }
    clubs.createClub(
        "Example club",
        request.get("teamId").asInt(),
        request.get("leagueId").asInt(),
        request.get("seasonId").asInt());

    String response =
        mockMvc
            .perform(
                post("/internal/scrape-results")
                    .header("Authorization", "Bearer " + SERVICE_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode actual = ScrapePayloads.JSON.readTree(response);
    assertThat(actual.get("fixtures").get("outcome").asString()).isEqualTo("STORED");
    assertThat(actual.get("standings").get("outcome").asString()).isEqualTo("STORED");
    assertThat(actual).isEqualTo(expected);
  }

  /**
   * KAN-73: the player statistics example (the block after {@code <!--
   * contract-example-player-stats -->}, which the scraper's own contract check also accepts) is
   * accepted, and answered as the block after {@code <!-- contract-example-player-stats-response
   * -->}.
   */
  @Test
  void theDocumentedPlayerStatsExampleIsAcceptedAndAnsweredAsDocumented() throws Exception {
    String doc = Files.readString(CONTRACT, StandardCharsets.UTF_8);
    String requestJson = fencedBlockAfter(doc, "<!-- contract-example-player-stats -->");
    JsonNode expected =
        ScrapePayloads.JSON.readTree(
            fencedBlockAfter(doc, "<!-- contract-example-player-stats-response -->"));
    JsonNode request = ScrapePayloads.JSON.readTree(requestJson);
    assertThat(Instant.parse(request.get("playerStats").get("fetchedAt").asString()))
        .isBefore(Instant.now());
    clubs.createClub(
        "Example club",
        request.get("teamId").asInt(),
        request.get("leagueId").asInt(),
        request.get("seasonId").asInt());

    String response =
        mockMvc
            .perform(
                post("/internal/scrape-results")
                    .header("Authorization", "Bearer " + SERVICE_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode actual = ScrapePayloads.JSON.readTree(response);
    assertThat(actual.get("playerStats").get("outcome").asString()).isEqualTo("STORED");
    assertThat(actual).isEqualTo(expected);
  }

  /**
   * KAN-79: the game-details example (the block after {@code <!-- contract-example-game-details
   * -->}) is accepted, answered as the block after {@code <!--
   * contract-example-game-details-response -->}, and stores its one game.
   */
  @Test
  void theDocumentedGameDetailsExampleIsAcceptedAndAnsweredAsDocumented() throws Exception {
    String doc = Files.readString(CONTRACT, StandardCharsets.UTF_8);
    String requestJson = fencedBlockAfter(doc, "<!-- contract-example-game-details -->");
    JsonNode expected =
        ScrapePayloads.JSON.readTree(
            fencedBlockAfter(doc, "<!-- contract-example-game-details-response -->"));
    JsonNode request = ScrapePayloads.JSON.readTree(requestJson);
    assertThat(Instant.parse(request.get("gameDetails").get("fetchedAt").asString()))
        .isBefore(Instant.now());
    clubs.createClub(
        "Example club",
        request.get("teamId").asInt(),
        request.get("leagueId").asInt(),
        request.get("seasonId").asInt());

    String response =
        mockMvc
            .perform(
                post("/internal/scrape-results")
                    .header("Authorization", "Bearer " + SERVICE_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode actual = ScrapePayloads.JSON.readTree(response);
    assertThat(actual.get("gameDetails").get("outcome").asString()).isEqualTo("STORED");
    assertThat(actual).isEqualTo(expected);
    assertThat(mongoTemplate.getCollection("game_details").countDocuments()).isEqualTo(1);
    assertThat(mongoTemplate.getCollection("game_detail_attempts").countDocuments()).isEqualTo(1);
  }

  /** The content of the first {@code ```json} block after {@code marker}. */
  /** The fenced {@code json} block after {@code marker} (also {@code ScraperClientTest}). */
  static String fencedBlockAfter(String doc, String marker) {
    int at = doc.indexOf(marker);
    assertThat(at).as("marker %s in %s", marker, CONTRACT).isNotNegative();
    int open = doc.indexOf("```json\n", at);
    int close = doc.indexOf("\n```", open + 8);
    assertThat(open).as("```json block after %s", marker).isPositive();
    return doc.substring(open + "```json\n".length(), close);
  }
}
