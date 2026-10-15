package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapePayloads.COHEN_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.COHEN_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.FETCHED_AT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEAGUE;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEVI_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEVI_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.MIZRAHI_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.MIZRAHI_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.PERETZ_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.PERETZ_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SEASON;
import static com.squadpulse.scrapingintegration.ScrapePayloads.fixtures;
import static com.squadpulse.scrapingintegration.ScrapePayloads.json;
import static com.squadpulse.scrapingintegration.ScrapePayloads.playerStats;
import static com.squadpulse.scrapingintegration.ScrapePayloads.playerStatsOnly;
import static com.squadpulse.scrapingintegration.ScrapePayloads.playerStatsWith;
import static com.squadpulse.scrapingintegration.ScrapePayloads.request;
import static com.squadpulse.scrapingintegration.ScrapePayloads.standings;
import static com.squadpulse.scrapingintegration.ScrapePayloads.statsRow;
import static com.squadpulse.scrapingintegration.ScrapePayloads.statsRows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.CapturedLogs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bson.Document;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The {@code playerStats} part of the scraper ingest (KAN-72) end to end on a real MongoDB, through
 * the real {@code /internal/scrape-results} with the service token: validation (a 400 writes
 * nothing, not even the request's other parts), who gets the snapshot, staleness, replace semantics
 * with stable opaque ids, the parts' independence, and the logs.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + PlayerStatsIngestIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestClubMappings.class)
class PlayerStatsIngestIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  private static final String INGEST = "/internal/scrape-results";
  private static final String STATS = "player_season_stats";

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

  private final CapturedLogs logs = new CapturedLogs();

  /** Sderot's club. */
  private String clubA;

  @BeforeEach
  void setUp() {
    logs.start();
    clubA = clubs.createClub("Sderot", SDEROT, LEAGUE, SEASON);
  }

  @AfterEach
  void tearDown() {
    logs.stop();
    for (String collection : List.of("league_fixtures", "league_standings", STATS)) {
      mongoTemplate.getCollection(collection).deleteMany(new Document());
    }
    clubs.deleteAllClubs();
  }

  // --- stored -----------------------------------------------------------------------------------

  @Test
  void aValidPartAloneIsStoredForEveryClubMappedToExactlyTheThreeIds() throws Exception {
    String sameTeam = clubs.createClub("Sderot fans", SDEROT, LEAGUE, SEASON);
    String otherSeason = clubs.createClub("Sderot last season", SDEROT, LEAGUE, SEASON - 1);
    String otherLeague = clubs.createClub("Sderot elsewhere", SDEROT, LEAGUE + 1, SEASON);

    ingest(playerStatsOnly(FETCHED_AT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.playerStats.outcome").value("STORED"))
        .andExpect(jsonPath("$.playerStats.reason").isEmpty())
        .andExpect(jsonPath("$.fixtures.outcome").value("NOT_SENT"))
        .andExpect(jsonPath("$.standings.outcome").value("NOT_SENT"))
        .andExpect(jsonPath("$.clubsUpdated").value(0));

    assertThat(snapshotOf(clubA)).isNotNull();
    assertThat(snapshotOf(sameTeam)).isNotNull();
    assertThat(snapshotOf(otherSeason)).isNull();
    assertThat(snapshotOf(otherLeague)).isNull();
    assertThat(rawDocuments(STATS)).hasSize(2);
  }

  @Test
  void theSnapshotHoldsEveryPlayerAsSentInPageOrder() throws Exception {
    ingest(playerStatsOnly(FETCHED_AT)).andExpect(status().isOk());

    Document snapshot = snapshotOf(clubA);
    assertThat(snapshot.getInteger("teamId")).isEqualTo(SDEROT);
    assertThat(snapshot.getInteger("seasonId")).isEqualTo(SEASON);
    assertThat(snapshot.getDate("fetchedAt").toInstant()).isEqualTo(FETCHED_AT);
    assertThat(snapshot.get("storedAt")).isNotNull();
    assertThat(snapshot.get("version")).isNotNull();
    List<Document> players = players(snapshot);
    assertThat(players)
        .extracting(p -> p.getInteger("sourcePlayerId"))
        .containsExactly(COHEN_ID, LEVI_ID, MIZRAHI_ID);
    Document cohen = players.getFirst();
    assertThat(cohen.getString("name")).isEqualTo(COHEN_NAME);
    assertThat(cohen.getString("opaqueId")).matches("[0-9a-f-]{36}");
    Map<String, Object> counts = new LinkedHashMap<>(cohen);
    counts.keySet().removeAll(List.of("opaqueId", "sourcePlayerId", "name"));
    assertThat(counts)
        .isEqualTo(
            Map.of(
                "games",
                10,
                "goals",
                2,
                "yellowCardsLeagueAndCup",
                1,
                "yellowCardsToto",
                0,
                "redCards",
                0,
                "starts",
                8,
                "cameOnAsSub",
                2,
                "subbedOff",
                3,
                "minutes",
                712));
    // A registered player who hasn't played is kept.
    assertThat(players.get(2).getInteger("minutes")).isZero();
    assertThat(players).extracting(p -> p.getString("opaqueId")).doesNotHaveDuplicates();
  }

  /** Added time and extra time: minutes are never checked against games. */
  @Test
  void minutesFarAboveNinetyAGameAreAccepted() throws Exception {
    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON,
                null,
                null,
                playerStatsWith(
                    FETCHED_AT, statsRow(COHEN_ID, COHEN_NAME, 2, 0, 0, 0, 0, 2, 0, 0, 900))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.playerStats.outcome").value("STORED"));
  }

  @Test
  void namesAreTrimmedOfSpaces() throws Exception {
    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON,
                null,
                null,
                playerStatsWith(FETCHED_AT, statsRow(COHEN_ID, "  " + COHEN_NAME + " "))))
        .andExpect(status().isOk());

    assertThat(players(snapshotOf(clubA)).getFirst().getString("name")).isEqualTo(COHEN_NAME);
  }

  // --- not stored -------------------------------------------------------------------------------

  @Test
  void aFailedPartIsRecordedAndLeavesTheOldSnapshot() throws Exception {
    ingest(playerStatsOnly(FETCHED_AT)).andExpect(status().isOk());
    Document before = snapshotOf(clubA);

    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON,
                null,
                null,
                ScrapePayloads.failed("PARSE_ERROR", "no table")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.playerStats.outcome").value("FAILURE_RECORDED"));

    assertThat(snapshotOf(clubA)).isEqualTo(before);
  }

  @Test
  void anUnmappedTeamIsRejected() throws Exception {
    ingest(request(SDEROT + 1, LEAGUE, SEASON, null, null, playerStats(FETCHED_AT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.playerStats.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.playerStats.reason").value("NOT_MAPPED"));

    assertThat(rawDocuments(STATS)).isEmpty();
  }

  @Test
  void anOlderScrapeOrAReplayChangesNothing() throws Exception {
    Instant newer = FETCHED_AT.plus(2, ChronoUnit.HOURS);
    ingest(playerStatsOnly(newer)).andExpect(jsonPath("$.playerStats.outcome").value("STORED"));
    Document stored = snapshotOf(clubA);

    ingest(playerStatsOnly(newer)).andExpect(jsonPath("$.playerStats.outcome").value("STALE"));
    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON,
                null,
                null,
                playerStatsWith(FETCHED_AT, statsRow(PERETZ_ID, PERETZ_NAME))))
        .andExpect(jsonPath("$.playerStats.outcome").value("STALE"));

    assertThat(snapshotOf(clubA)).isEqualTo(stored);
  }

  // --- replace ----------------------------------------------------------------------------------

  /**
   * Levi drops out and Peretz is added: Levi's stats go, the survivors keep their opaque ids, and
   * Peretz gets a new one.
   */
  @Test
  void aNewerScrapeReplacesThePlayersAndKeepsTheSurvivorsIds() throws Exception {
    ingest(playerStatsOnly(FETCHED_AT)).andExpect(status().isOk());
    Map<Integer, String> before = opaqueIds(snapshotOf(clubA));

    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON,
                null,
                null,
                playerStatsWith(
                    FETCHED_AT.plus(1, ChronoUnit.HOURS),
                    statsRow(PERETZ_ID, PERETZ_NAME),
                    statsRow(MIZRAHI_ID, MIZRAHI_NAME, 1, 0, 0, 0, 0, 0, 1, 0, 12),
                    statsRow(COHEN_ID, COHEN_NAME, 11, 2, 1, 0, 0, 9, 2, 3, 800))))
        .andExpect(jsonPath("$.playerStats.outcome").value("STORED"));

    Document after = snapshotOf(clubA);
    Map<Integer, String> ids = opaqueIds(after);
    assertThat(ids).containsOnlyKeys(PERETZ_ID, MIZRAHI_ID, COHEN_ID);
    assertThat(ids.get(COHEN_ID)).isEqualTo(before.get(COHEN_ID));
    assertThat(ids.get(MIZRAHI_ID)).isEqualTo(before.get(MIZRAHI_ID));
    assertThat(ids.get(PERETZ_ID)).isNotNull().isNotIn(before.values());
    assertThat(players(after))
        .extracting(p -> p.getInteger("sourcePlayerId"))
        .containsExactly(PERETZ_ID, MIZRAHI_ID, COHEN_ID);
    assertThat(players(after).get(2).getInteger("minutes")).isEqualTo(800);
  }

  // --- independence -----------------------------------------------------------------------------

  /**
   * All three parts in one request, each with its own outcome: the fixtures are rejected (a
   * duplicate pairing), the standings are stale (stored before), the stats are stored.
   */
  @Test
  void eachOfThreePartsHasItsOwnOutcome() throws Exception {
    ingest(request(SDEROT, LEAGUE, SEASON, null, standings(FETCHED_AT), null))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"));

    Map<String, Object> all =
        request(
            SDEROT,
            LEAGUE,
            SEASON,
            fixtures(FETCHED_AT),
            standings(FETCHED_AT),
            playerStats(FETCHED_AT));
    ScrapePayloads.games(all).add(ScrapePayloads.games(all).getFirst());
    ingest(all)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.fixtures.reason").value("DUPLICATE_GAME"))
        .andExpect(jsonPath("$.standings.outcome").value("STALE"))
        .andExpect(jsonPath("$.playerStats.outcome").value("STORED"));

    assertThat(snapshotOf(clubA)).isNotNull();
    assertThat(rawDocuments("league_fixtures")).isEmpty();
  }

  @Test
  void aFailedStatsPartDoesntStopTheFixtures() throws Exception {
    Map<String, Object> body =
        request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), null, playerStats(FETCHED_AT));
    ingest(body).andExpect(jsonPath("$.playerStats.outcome").value("STORED"));

    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON,
                fixtures(FETCHED_AT.plusSeconds(60)),
                null,
                ScrapePayloads.failed("SOURCE_BLOCKED", null)))
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.playerStats.outcome").value("FAILURE_RECORDED"))
        .andExpect(jsonPath("$.clubsUpdated").value(1));
  }

  // --- validation: every 400 writes nothing, not even the other parts -------------------------

  @Test
  void everyInvalidStatsPartIsA400ThatWritesNothing() throws Exception {
    List<Object[]> cases = new ArrayList<>();
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("games", 11)),
          "playerStats.players[0].appearancesConsistent: starts + cameOnAsSub must equal games"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).get(1).put("playerId", COHEN_ID)),
          "playerStats.playerIdsUnique: playerId must be unique"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).clear()), "playerStats.players: size must be between 1 and 80"
        });
    cases.add(
        new Object[] {
          change(
              b -> {
                List<Map<String, Object>> rows = statsRows(b);
                rows.clear();
                for (int i = 1; i <= 81; i++) {
                  rows.add(statsRow(800000 + i, "שחקן " + i));
                }
              }),
          "playerStats.players: size must be between 1 and 80"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("goals", -1)),
          "playerStats.players[0].goals: must be greater than or equal to 0"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("minutes", 20001)),
          "playerStats.players[0].minutes: must be less than or equal to 20000"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("redCards", 201)),
          "playerStats.players[0].redCards: must be less than or equal to 200"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("playerId", 0)),
          "playerStats.players[0].playerId: must be greater than 0"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("name", "   ")),
          "playerStats.players[0].name: must not be blank"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("name", "x".repeat(101))),
          "playerStats.players[0].name: size must be between 0 and 100"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("name", "כהן\u001Fדניאל")),
          "playerStats.players[0].name: must not contain control characters"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("name", "כהן דניאל\n")),
          "playerStats.players[0].name: must not contain control characters"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().remove("subbedOff")),
          "playerStats.players[0].subbedOff: must not be null"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).add(null)), "playerStats.players[3]: must not be null"
        });
    cases.add(
        new Object[] {
          change(b -> statsRows(b).getFirst().put("shirtNumber", 9)),
          "playerStats.players[0].shirtNumber: invalid value"
        });
    cases.add(
        new Object[] {
          change(b -> ScrapePayloads.part(b, "playerStats").put("reason", "TIMEOUT")),
          "playerStats.consistentWithStatus: status OK needs fetchedAt and players and no reason"
              + " or detail; FAILED needs a reason and no fetchedAt or players"
        });
    cases.add(
        new Object[] {
          change(b -> ScrapePayloads.part(b, "playerStats").put("status", "FAILED")),
          "playerStats.consistentWithStatus: status OK needs fetchedAt and players and no reason"
              + " or detail; FAILED needs a reason and no fetchedAt or players"
        });
    cases.add(
        new Object[] {
          change(
              b ->
                  ScrapePayloads.part(b, "playerStats")
                      .put("fetchedAt", Instant.now().plus(10, ChronoUnit.MINUTES).toString())),
          "playerStats.fetchedAt: must not be in the future"
        });

    for (Object[] testCase : cases) {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) testCase[0];
      ingest(body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.details[0]").value(testCase[1]));
      assertThat(rawDocuments(STATS)).as((String) testCase[1]).isEmpty();
      assertThat(rawDocuments("league_fixtures")).as((String) testCase[1]).isEmpty();
      assertThat(rawDocuments("league_standings")).as((String) testCase[1]).isEmpty();
    }
  }

  @Test
  void aFailedPartWithPlayersIsA400() throws Exception {
    Map<String, Object> failed = ScrapePayloads.failed("TIMEOUT", null);
    failed.put("players", List.of(statsRow(COHEN_ID, COHEN_NAME)));
    ingest(request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), null, failed))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.details[0]")
                .value(
                    "playerStats.consistentWithStatus: status OK needs fetchedAt and players and"
                        + " no reason or detail; FAILED needs a reason and no fetchedAt or"
                        + " players"));
    assertThat(rawDocuments("league_fixtures")).isEmpty();
  }

  @Test
  void neitherOfThePartsIsA400() throws Exception {
    ingest(request(SDEROT, LEAGUE, SEASON, null, null, null))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.details[0]")
                .value(
                    "anyPartPresent: fixtures, standings, playerStats or gameDetails must be present"));
  }

  // --- logs -------------------------------------------------------------------------------------

  @Test
  void noLogLineNamesAPlayer() throws Exception {
    ingest(playerStatsOnly(FETCHED_AT)).andExpect(status().isOk());
    ingest(playerStatsOnly(FETCHED_AT)).andExpect(status().isOk());
    ingest(request(SDEROT + 1, LEAGUE, SEASON, null, null, playerStats(FETCHED_AT)))
        .andExpect(status().isOk());
    ingest(change(b -> statsRows(b).getFirst().put("games", 99)))
        .andExpect(status().isBadRequest());

    String logged = logs.everythingLogged();
    assertThat(logged).contains("Player stats for team " + SDEROT);
    for (String name : List.of(COHEN_NAME, LEVI_NAME, MIZRAHI_NAME, "כהן", "לוי", "מזרחי")) {
      assertThat(logged).doesNotContain(name);
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** Stats, fixtures and standings, all valid, with one change. */
  private static Map<String, Object> change(Consumer<Map<String, Object>> change) {
    Map<String, Object> body =
        request(
            SDEROT,
            LEAGUE,
            SEASON,
            fixtures(FETCHED_AT),
            standings(FETCHED_AT),
            playerStats(FETCHED_AT));
    change.accept(body);
    return body;
  }

  private ResultActions ingest(Object body) throws Exception {
    return mockMvc.perform(
        post(INGEST)
            .header("Authorization", "Bearer " + SERVICE_TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
  }

  private Document snapshotOf(String clubId) {
    return mongoTemplate.getCollection(STATS).find(new Document("clubId", clubId)).first();
  }

  @SuppressWarnings("unchecked")
  private static List<Document> players(Document snapshot) {
    return (List<Document>) snapshot.get("players");
  }

  private static Map<Integer, String> opaqueIds(Document snapshot) {
    return players(snapshot).stream()
        .collect(
            Collectors.toMap(p -> p.getInteger("sourcePlayerId"), p -> p.getString("opaqueId")));
  }

  private List<Document> rawDocuments(String collection) {
    return mongoTemplate.getCollection(collection).find().into(new ArrayList<>());
  }
}
