package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapePayloads.COHEN_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.COHEN_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.FETCHED_AT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.KIRYAT_MALACHI;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEAGUE;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEVI_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEVI_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SEASON;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA;
import static com.squadpulse.scrapingintegration.ScrapePayloads.json;
import static com.squadpulse.scrapingintegration.ScrapePayloads.playerStats;
import static com.squadpulse.scrapingintegration.ScrapePayloads.playerStatsWith;
import static com.squadpulse.scrapingintegration.ScrapePayloads.request;
import static com.squadpulse.scrapingintegration.ScrapePayloads.statsRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.squad.Player;
import com.squadpulse.squad.PlayerCreation;
import com.squadpulse.squad.PlayerRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creating a squad player from a scraped statistics row and linking it in one step (KAN-77), end to
 * end on a real MongoDB replica set behind the real security chain: the answer, who may call it,
 * the refusals (each writing nothing), a dangling link, the rollbacks that prove the transaction,
 * races, and club isolation inside the transaction.
 *
 * <p>The test seam: {@link HookedPlayerCreation} decorates {@code squad}'s {@link PlayerCreation}
 * and runs {@link #afterCreate} right after the player insert, inside the transaction.
 *
 * <p>Clubs A and C are mapped to the same team (Sderot), B to another (Tira).
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + StatsPlayerCreationIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import({
  TestAccessTokens.class,
  TestClubMappings.class,
  StatsPlayerCreationIntegrationTest.HookedPlayerCreation.class
})
class StatsPlayerCreationIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  private static final String STATS = "/matches/player-stats";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Runs inside the transaction, right after the player insert; {@code null} = nothing. */
  static final AtomicReference<Runnable> afterCreate = new AtomicReference<>();

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @TestConfiguration
  static class HookedPlayerCreation {
    @Bean
    @Primary
    PlayerCreation hookedPlayerCreation(@Qualifier("playerService") PlayerCreation delegate) {
      return request -> {
        Player player = delegate.create(request);
        Runnable hook = afterCreate.get();
        if (hook != null) {
          hook.run();
        }
        return player;
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private TestAccessTokens tokens;
  @Autowired private TestClubMappings clubs;
  @Autowired private PlayerRepository playerRepository;

  private String clubA;
  private String clubB;
  private String clubC;

  @BeforeEach
  void setUp() throws Exception {
    clubA = clubs.createClub("Sderot", SDEROT, LEAGUE, SEASON);
    clubB = clubs.createClub("Tira", TIRA, LEAGUE, SEASON);
    clubC = clubs.createClub("Sderot fans", SDEROT, LEAGUE, SEASON);
    ingestStats(SDEROT, playerStats(FETCHED_AT));
    ingestStats(TIRA, playerStatsWith(FETCHED_AT, statsRow(910001, "חדד רון")));
  }

  @AfterEach
  void tearDown() {
    afterCreate.set(null);
    for (String collection : List.of("player_season_stats", "player_stats_links", "players")) {
      mongoTemplate.getCollection(collection).deleteMany(new Document());
    }
    clubs.deleteAllClubs();
  }

  // --- the answer ------------------------------------------------------------------------------

  @Test
  void createsThePlayerAndLinksTheRow() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    MockHttpServletResponse response =
        create(clubA, row, fullBody(), PermissionLevel.EDIT_FULL)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.fullName").value("דניאל כהן"))
            .andExpect(jsonPath("$.primaryPosition").value("CM"))
            .andExpect(jsonPath("$.secondaryPosition").value("DM"))
            .andExpect(jsonPath("$.jerseyNumber").value(8))
            .andExpect(jsonPath("$.dateOfBirth").value("1995-05-20"))
            .andExpect(jsonPath("$.heightCm").value(180))
            .andExpect(jsonPath("$.weightKg").value(75))
            .andExpect(jsonPath("$.preferredFoot").value("LEFT"))
            .andExpect(jsonPath("$.medicalStatus").value("INJURED"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.hasPhoto").value(false))
            .andReturn()
            .getResponse();
    String body = response.getContentAsString();
    String playerId = JSON.readTree(body).get("id").stringValue();

    // The player's own URL, never one under the stats row's path.
    assertThat(response.getHeader("Location"))
        .isEqualTo("http://localhost/squad/players/" + playerId);
    // No football.org.il id anywhere, and the same shape as POST /squad/players.
    assertThat(body).doesNotContain(String.valueOf(COHEN_ID)).doesNotContain("clubId");
    assertThat(JSON.readTree(body).propertyNames())
        .containsExactlyInAnyOrderElementsOf(
            JSON.readTree(squadCreate(clubA, minimalBody("אחר")).getContentAsString())
                .propertyNames());

    List<Document> links = rawLinks();
    assertThat(links).hasSize(1);
    assertThat(links.getFirst().getString("playerId")).isEqualTo(playerId);
    assertThat(links.getFirst().getInteger("sourcePlayerId")).isEqualTo(COHEN_ID);
    Instant linkedAt = links.getFirst().getDate("linkedAt").toInstant();
    assertThat(linkedAt).isBetween(before, Instant.now());

    JsonNode cohen = rowNamed(read(clubA), COHEN_NAME);
    assertThat(cohen.get("linkedPlayer").get("id").stringValue()).isEqualTo(playerId);
    assertThat(cohen.get("linkedPlayer").get("fullName").stringValue()).isEqualTo("דניאל כהן");

    mockMvc
        .perform(
            get("/squad/players/" + playerId)
                .header("Authorization", tokens.bearer(clubA, PermissionLevel.VIEW_ONLY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jerseyNumber").value(8));
  }

  @Test
  void aMinimalBodyIsEnoughAndTheMedicalStatusDefaultsToFit() throws Exception {
    create(clubA, rowId(clubA, COHEN_NAME), minimalBody("דניאל כהן"), PermissionLevel.ADMIN)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.medicalStatus").value("FIT"))
        .andExpect(jsonPath("$.secondaryPosition").isEmpty())
        .andExpect(jsonPath("$.jerseyNumber").isEmpty());
    assertThat(rawLinks()).hasSize(1);
  }

  // --- refusals that write nothing --------------------------------------------------------------

  /** The same body, the same 400 as POST /squad/players: one request type, one validation. */
  @Test
  void anInvalidBodyIsTheSame400AsCreatingInTheSquad() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    List<Map<String, Object>> invalid = new ArrayList<>();
    invalid.add(with(minimalBody("  "), "fullName", "  "));
    invalid.add(with(minimalBody("שם"), "primaryPosition", null));
    invalid.add(with(minimalBody("שם"), "dateOfBirth", Instant.now().toString().substring(0, 10)));
    invalid.add(with(minimalBody("שם"), "secondaryPosition", "ST"));
    invalid.add(with(minimalBody("שם"), "jerseyNumber", 100));
    invalid.add(with(minimalBody("שם"), "heightCm", 100));
    invalid.add(with(minimalBody("שם"), "primaryPosition", "XX"));
    invalid.add(with(minimalBody("x".repeat(101)), "weightKg", 10));

    for (Map<String, Object> body : invalid) {
      JsonNode viaStats =
          JSON.readTree(
              create(clubA, row, body, PermissionLevel.EDIT_FULL)
                  .andExpect(status().isBadRequest())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      MockHttpServletResponse squadResponse = squadCreate(clubA, body);
      assertThat(squadResponse.getStatus()).isEqualTo(400);
      JsonNode viaSquad = JSON.readTree(squadResponse.getContentAsString());
      for (String field : List.of("status", "error", "code", "message")) {
        assertThat(viaStats.get(field)).as(field + " for " + body).isEqualTo(viaSquad.get(field));
      }
      // Bean Validation reports violations in no fixed order.
      assertThat(viaStats.get("details").valueStream().toList())
          .as("details for " + body)
          .containsExactlyInAnyOrderElementsOf(viaSquad.get("details").valueStream().toList());
    }
    assertNothingWritten();
  }

  @Test
  void belowEditFullIsForbiddenAndWritesNothing() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    for (PermissionLevel level : List.of(PermissionLevel.VIEW_ONLY, PermissionLevel.EDIT_PARTIAL)) {
      create(clubA, row, minimalBody("דניאל כהן"), level).andExpect(status().isForbidden());
    }
    assertNothingWritten();
  }

  @Test
  void aRowThatIsNotTheClubsIsNotFoundAndWritesNothing() throws Exception {
    String unmapped = clubs.createClub("Unmapped");
    String noSnapshot = clubs.createClub("No snapshot", KIRYAT_MALACHI, LEAGUE, SEASON);
    String rowOfB = rowId(clubB, "חדד רון");
    String rowOfA = rowId(clubA, COHEN_NAME);

    create(clubA, "no-such-row", minimalBody("א"), PermissionLevel.EDIT_FULL)
        .andExpect(status().isNotFound());
    create(clubA, rowOfB, minimalBody("א"), PermissionLevel.EDIT_FULL)
        .andExpect(status().isNotFound());
    create(unmapped, rowOfA, minimalBody("א"), PermissionLevel.EDIT_FULL)
        .andExpect(status().isNotFound());
    create(noSnapshot, rowOfA, minimalBody("א"), PermissionLevel.EDIT_FULL)
        .andExpect(status().isNotFound());

    assertNothingWritten();
  }

  @Test
  void aRowLinkedToAnExistingPlayerIsRefusedAndKeepsItsLink() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    String linked = createLinked(clubA, row, "דניאל כהן");
    Document before = rawLinks().getFirst();

    String body =
        create(clubA, row, minimalBody("כהן אחר"), PermissionLevel.EDIT_FULL)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STATS_ROW_ALREADY_LINKED"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(String.valueOf(COHEN_ID));
    assertThat(players(clubA)).containsExactly(linked);
    assertThat(rawLinks()).containsExactly(before);
  }

  // --- a dangling link --------------------------------------------------------------------------

  @Test
  void aLinkToADeletedPlayerIsReplaced() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    String deleted = createLinked(clubA, row, "דניאל כהן");
    mongoTemplate.getCollection("players").deleteOne(new Document("_id", new ObjectId(deleted)));

    String created = createLinked(clubA, row, "כהן דניאל");

    assertThat(rawLinks()).hasSize(1);
    assertThat(rawLinks().getFirst().getString("playerId")).isEqualTo(created);
  }

  // --- rollbacks: the transaction is real -------------------------------------------------------

  @Test
  void aTakenJerseyNumberCreatesNoPlayerAndNoLink() throws Exception {
    squadCreate(clubA, with(minimalBody("בעל המספר"), "jerseyNumber", 8));
    List<String> before = players(clubA);

    create(clubA, rowId(clubA, COHEN_NAME), with(minimalBody("כהן"), "jerseyNumber", 8), editFull())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("JERSEY_NUMBER_TAKEN"));

    assertThat(players(clubA)).isEqualTo(before);
    assertThat(rawLinks()).isEmpty();
  }

  /**
   * The row is linked by someone else after the check but before this transaction's link write —
   * committed from another thread while the player insert is pending. The link write then fails,
   * and the player insert must be rolled back: without the transaction it would stay. MongoDB
   * reports it as a write conflict (code 112, {@code TransientTransactionError}), not a 500.
   */
  @Test
  void aLinkWriteThatFailsRollsThePlayerInsertBack() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    String other = squadPlayer(clubA, "לוי יוסף");
    afterCreate.set(
        () -> CompletableFuture.runAsync(() -> insertRawLink(clubA, COHEN_ID, other)).join());

    create(clubA, row, minimalBody("דניאל כהן"), PermissionLevel.EDIT_FULL)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    assertThat(players(clubA)).containsExactly(other);
    assertThat(rawLinks()).hasSize(1);
    assertThat(rawLinks().getFirst().getString("playerId")).isEqualTo(other);
  }

  /** The same, but the row is linked inside the window and seen by the transaction's own read. */
  @Test
  void aRowLinkedInsideTheTransactionsWindowIsRefusedAndRolledBack() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    String other = squadPlayer(clubA, "לוי יוסף");
    afterCreate.set(() -> insertRawLink(clubA, COHEN_ID, other)); // joins the transaction

    create(clubA, row, minimalBody("דניאל כהן"), PermissionLevel.EDIT_FULL)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATS_ROW_ALREADY_LINKED"));

    // Both the player and the hook's own link were rolled back.
    assertThat(players(clubA)).containsExactly(other);
    assertThat(rawLinks()).isEmpty();
  }

  // --- races ------------------------------------------------------------------------------------

  @Test
  void twoCreatesForOneRowAtOnceLetExactlyOneWin() throws Exception {
    assertExactlyOneWins(rowId(clubA, COHEN_NAME), false);
  }

  /** Two writers replacing the same dangling link document. */
  @Test
  void twoCreatesForOneRowWithADanglingLinkLetExactlyOneWin() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    String deleted = createLinked(clubA, row, "נמחק");
    mongoTemplate.getCollection("players").deleteOne(new Document("_id", new ObjectId(deleted)));

    assertExactlyOneWins(row, true);
  }

  /** Five rounds; between them the winner is deleted and its link left dangling or removed. */
  private void assertExactlyOneWins(String row, boolean dangling) throws Exception {
    List<String> before = players(clubA);
    for (int round = 0; round < 5; round++) {
      List<MockHttpServletResponse> responses =
          inParallel(
              List.of(
                  () ->
                      create(clubA, row, minimalBody("ראשון"), editFull())
                          .andReturn()
                          .getResponse(),
                  () ->
                      create(clubA, row, minimalBody("שני"), editFull())
                          .andReturn()
                          .getResponse()));

      assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201, 409);
      assertThat(responses).filteredOn(r -> r.getStatus() == 201).hasSize(1);
      for (MockHttpServletResponse response : responses) {
        if (response.getStatus() == 409) {
          assertThat(JSON.readTree(response.getContentAsString()).get("code").stringValue())
              .isIn("STATS_ROW_ALREADY_LINKED", "CONCURRENT_MODIFICATION");
        }
      }
      String winner =
          JSON.readTree(
                  responses.stream()
                      .filter(r -> r.getStatus() == 201)
                      .findFirst()
                      .orElseThrow()
                      .getContentAsString())
              .get("id")
              .stringValue();
      List<String> after = new ArrayList<>(before);
      after.add(winner);
      assertThat(players(clubA)).containsExactlyInAnyOrderElementsOf(after);
      assertThat(rawLinks()).hasSize(1);
      assertThat(rawLinks().getFirst().getString("playerId")).isEqualTo(winner);

      mongoTemplate.getCollection("players").deleteOne(new Document("_id", new ObjectId(winner)));
      if (!dangling) {
        mongoTemplate.getCollection("player_stats_links").deleteMany(new Document());
      }
    }
  }

  /**
   * Two rows created at once with the same jersey number: the two inserts clash on the players'
   * jersey index inside two transactions. Exactly one wins; the other is a 409 with nothing
   * written. While the winner hasn't committed, MongoDB reports the clash as a write conflict, not
   * a duplicate key, so the loser gets CONCURRENT_MODIFICATION (5 of 5 rounds when this was
   * written); JERSEY_NUMBER_TAKEN only if the winner had already committed. A retry then answers
   * JERSEY_NUMBER_TAKEN (see StatsPlayerCreationService#isWriteConflict).
   */
  @Test
  void twoRowsCreatedAtOnceWithTheSameJerseyNumberLetExactlyOneWin() throws Exception {
    String cohen = rowId(clubA, COHEN_NAME);
    String levi = rowId(clubA, LEVI_NAME);
    for (int round = 0; round < 5; round++) {
      List<MockHttpServletResponse> responses =
          inParallel(
              List.of(
                  () ->
                      create(clubA, cohen, with(minimalBody("כהן"), "jerseyNumber", 8), editFull())
                          .andReturn()
                          .getResponse(),
                  () ->
                      create(clubA, levi, with(minimalBody("לוי"), "jerseyNumber", 8), editFull())
                          .andReturn()
                          .getResponse()));

      assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201, 409);
      assertThat(responses).filteredOn(r -> r.getStatus() == 201).hasSize(1);
      for (MockHttpServletResponse response : responses) {
        if (response.getStatus() == 409) {
          assertThat(JSON.readTree(response.getContentAsString()).get("code").stringValue())
              .isIn("JERSEY_NUMBER_TAKEN", "CONCURRENT_MODIFICATION");
        }
      }
      assertThat(players(clubA)).hasSize(1);
      assertThat(rawLinks()).hasSize(1);
      assertThat(rawLinks().getFirst().getString("playerId")).isEqualTo(players(clubA).getFirst());

      mongoTemplate.getCollection("players").deleteMany(new Document());
      mongoTemplate.getCollection("player_stats_links").deleteMany(new Document());
    }
  }

  // --- isolation --------------------------------------------------------------------------------

  /** Inside the transaction the club-scoped repositories still stamp and filter by the clubId. */
  @Test
  void theTransactionStaysInsideTheCallersClub() throws Exception {
    String playerOfC = createLinked(clubC, rowId(clubC, LEVI_NAME), "יוסף לוי");
    Document linkOfC = rawLinks().getFirst();
    AtomicReference<Boolean> otherClubVisible = new AtomicReference<>();
    afterCreate.set(() -> otherClubVisible.set(playerRepository.findById(playerOfC).isPresent()));

    String created = createLinked(clubA, rowId(clubA, COHEN_NAME), "דניאל כהן");

    assertThat(otherClubVisible.get()).isFalse();
    Document player =
        mongoTemplate
            .getCollection("players")
            .find(new Document("_id", new ObjectId(created)))
            .first();
    assertThat(player.getString("clubId")).isEqualTo(clubA);
    Document link =
        mongoTemplate
            .getCollection("player_stats_links")
            .find(new Document("playerId", created))
            .first();
    assertThat(link.getString("clubId")).isEqualTo(clubA);
    assertThat(players(clubC)).containsExactly(playerOfC);
    assertThat(players(clubB)).isEmpty();
    assertThat(rawLinks()).contains(linkOfC).hasSize(2);
    // Club C, mapped to the same team, still sees its own row unlinked from A's player.
    assertThat(rowNamed(read(clubC), COHEN_NAME).get("linkedPlayer").isNull()).isTrue();
  }

  // --- a later ingest ---------------------------------------------------------------------------

  @Test
  void theLinkSurvivesALaterIngestThatKeepsTheRow() throws Exception {
    String created = createLinked(clubA, rowId(clubA, COHEN_NAME), "דניאל כהן");

    ingestStats(
        SDEROT,
        playerStatsWith(
            FETCHED_AT.plus(1, ChronoUnit.HOURS),
            statsRow(COHEN_ID, COHEN_NAME),
            statsRow(LEVI_ID, LEVI_NAME)));

    assertThat(rowNamed(read(clubA), COHEN_NAME).get("linkedPlayer").get("id").stringValue())
        .isEqualTo(created);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static PermissionLevel editFull() {
    return PermissionLevel.EDIT_FULL;
  }

  private void ingestStats(int teamId, Map<String, Object> part) throws Exception {
    mockMvc
        .perform(
            post("/internal/scrape-results")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request(teamId, LEAGUE, SEASON, null, null, part))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.playerStats.outcome").value("STORED"));
  }

  private static Map<String, Object> minimalBody(String fullName) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fullName", fullName);
    body.put("primaryPosition", "ST");
    body.put("dateOfBirth", "1995-05-20");
    return body;
  }

  private static Map<String, Object> fullBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fullName", "  דניאל כהן ");
    body.put("primaryPosition", "CM");
    body.put("secondaryPosition", "DM");
    body.put("jerseyNumber", 8);
    body.put("dateOfBirth", "1995-05-20");
    body.put("heightCm", 180);
    body.put("weightKg", 75);
    body.put("preferredFoot", "LEFT");
    body.put("medicalStatus", "INJURED");
    return body;
  }

  private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
    body.put(key, value);
    return body;
  }

  private ResultActions create(
      String clubId, String rowId, Map<String, Object> body, PermissionLevel level)
      throws Exception {
    return mockMvc.perform(
        post(STATS + "/" + rowId + "/player")
            .header("Authorization", tokens.bearer(clubId, level))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
  }

  /** Creates and links, expecting a 201; returns the new player's id. */
  private String createLinked(String clubId, String rowId, String fullName) throws Exception {
    String body =
        create(clubId, rowId, minimalBody(fullName), PermissionLevel.EDIT_FULL)
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body).get("id").stringValue();
  }

  private MockHttpServletResponse squadCreate(String clubId, Map<String, Object> body)
      throws Exception {
    return mockMvc
        .perform(
            post("/squad/players")
                .header("Authorization", tokens.bearer(clubId, PermissionLevel.EDIT_FULL))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
        .andReturn()
        .getResponse();
  }

  private String squadPlayer(String clubId, String fullName) throws Exception {
    MockHttpServletResponse response = squadCreate(clubId, minimalBody(fullName));
    assertThat(response.getStatus()).isEqualTo(201);
    return JSON.readTree(response.getContentAsString()).get("id").stringValue();
  }

  /** A link written straight to the collection, as a concurrent link request would leave it. */
  private void insertRawLink(String clubId, int sourcePlayerId, String playerId) {
    mongoTemplate
        .getCollection("player_stats_links")
        .insertOne(
            new Document("clubId", clubId)
                .append("sourcePlayerId", sourcePlayerId)
                .append("playerId", playerId)
                .append("linkedAt", Date.from(Instant.now())));
  }

  private void assertNothingWritten() {
    assertThat(mongoTemplate.getCollection("players").countDocuments()).isZero();
    assertThat(rawLinks()).isEmpty();
  }

  /** The club's player ids, straight from the collection. */
  private List<String> players(String clubId) {
    List<String> ids = new ArrayList<>();
    for (Document player :
        mongoTemplate.getCollection("players").find(new Document("clubId", clubId))) {
      ids.add(player.getObjectId("_id").toHexString());
    }
    return ids;
  }

  private List<Document> rawLinks() {
    return mongoTemplate.getCollection("player_stats_links").find().into(new ArrayList<>());
  }

  /** Sends all at once (released together by a latch) and returns their responses in order. */
  private static List<MockHttpServletResponse> inParallel(
      List<Callable<MockHttpServletResponse>> requests) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(requests.size())) {
      for (Callable<MockHttpServletResponse> request : requests) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return request.call();
                }));
      }
      start.countDown();
      List<MockHttpServletResponse> responses = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        responses.add(future.get());
      }
      return responses;
    }
  }

  private JsonNode read(String clubId) throws Exception {
    return JSON.readTree(
        mockMvc
            .perform(
                get(STATS)
                    .header("Authorization", tokens.bearer(clubId, PermissionLevel.VIEW_ONLY)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private String rowId(String clubId, String name) throws Exception {
    return rowNamed(read(clubId), name).get("id").stringValue();
  }

  private static JsonNode rowNamed(JsonNode stats, String name) {
    for (JsonNode row : stats.get("players")) {
      if (row.get("name").stringValue().equals(name)) {
        return row;
      }
    }
    throw new AssertionError("No row named " + name);
  }
}
