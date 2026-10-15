package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapePayloads.BENCH_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.FETCHED_AT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.GK_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.KIRYAT_MALACHI_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEAGUE;
import static com.squadpulse.scrapingintegration.ScrapePayloads.MALACHI_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.NIR_RAM_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SCORER_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SEASON;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SUB_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.appearance;
import static com.squadpulse.scrapingintegration.ScrapePayloads.detailsGames;
import static com.squadpulse.scrapingintegration.ScrapePayloads.detailsPlayers;
import static com.squadpulse.scrapingintegration.ScrapePayloads.event;
import static com.squadpulse.scrapingintegration.ScrapePayloads.fixtures;
import static com.squadpulse.scrapingintegration.ScrapePayloads.gameDetails;
import static com.squadpulse.scrapingintegration.ScrapePayloads.gameDetailsOnly;
import static com.squadpulse.scrapingintegration.ScrapePayloads.gameDetailsWith;
import static com.squadpulse.scrapingintegration.ScrapePayloads.gameFailure;
import static com.squadpulse.scrapingintegration.ScrapePayloads.json;
import static com.squadpulse.scrapingintegration.ScrapePayloads.malachiGame;
import static com.squadpulse.scrapingintegration.ScrapePayloads.playerStats;
import static com.squadpulse.scrapingintegration.ScrapePayloads.request;
import static com.squadpulse.scrapingintegration.ScrapePayloads.standings;
import static com.squadpulse.scrapingintegration.ScrapePayloads.tiraGame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import com.mongodb.MongoWriteException;
import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.CapturedLogs;
import com.squadpulse.common.ClubContext;
import com.squadpulse.match.KnownGames;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
 * The {@code gameDetails} part of the scraper ingest (KAN-79) end to end on a real MongoDB, through
 * the real {@code /internal/scrape-results} with the service token: validation (a 400 writes
 * nothing, not even the request's other parts; no count checks), who gets the records, per-game
 * staleness with the opaque id kept, the attempt counting and giving up, the known-games list the
 * trigger would send, the unique indexes, and the logs.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + GameDetailsIngestIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestClubMappings.class)
class GameDetailsIngestIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  private static final String INGEST = "/internal/scrape-results";
  private static final String DETAILS = "game_details";
  private static final String ATTEMPTS = "game_detail_attempts";
  private static final FootballOrgIlIds SDEROT_IDS = new FootballOrgIlIds(SDEROT, LEAGUE, SEASON);

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
  @Autowired private ClubContext clubContext;
  @Autowired private KnownGames knownGames;
  @Autowired private ScrapeTriggerService triggerService;

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
    for (String collection :
        List.of("league_fixtures", "league_standings", "player_season_stats", DETAILS, ATTEMPTS)) {
      mongoTemplate.getCollection(collection).deleteMany(new Document());
    }
    clubs.deleteAllClubs();
  }

  // --- stored -----------------------------------------------------------------------------------

  @Test
  void aValidPartIsStoredForEveryClubMappedToExactlyTheThreeIds() throws Exception {
    String sameTeam = clubs.createClub("Sderot fans", SDEROT, LEAGUE, SEASON);
    String otherSeason = clubs.createClub("Sderot last season", SDEROT, LEAGUE, SEASON - 1);
    String otherLeague = clubs.createClub("Sderot elsewhere", SDEROT, LEAGUE + 1, SEASON);

    ingest(gameDetailsOnly(gameDetails(FETCHED_AT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDetails.outcome").value("STORED"))
        .andExpect(jsonPath("$.gameDetails.reason").isEmpty())
        .andExpect(jsonPath("$.fixtures.outcome").value("NOT_SENT"))
        .andExpect(jsonPath("$.standings.outcome").value("NOT_SENT"))
        .andExpect(jsonPath("$.playerStats.outcome").value("NOT_SENT"))
        .andExpect(jsonPath("$.clubsUpdated").value(0));

    assertThat(gameIdsOf(clubA)).containsExactlyInAnyOrder(TIRA_GAME, MALACHI_GAME);
    assertThat(gameIdsOf(sameTeam)).containsExactlyInAnyOrder(TIRA_GAME, MALACHI_GAME);
    assertThat(gameIdsOf(otherSeason)).isEmpty();
    assertThat(gameIdsOf(otherLeague)).isEmpty();
    // Each club's copy has its own opaque id.
    assertThat(raw(DETAILS).stream().map(d -> d.getString("_id")).distinct()).hasSize(4);
  }

  @Test
  void theRecordHoldsTheGameAsSentWithEventsSortedByMinute() throws Exception {
    ingest(gameDetailsOnly(gameDetails(FETCHED_AT))).andExpect(status().isOk());

    Document tira = detailsOf(clubA, TIRA_GAME);
    assertThat(tira.getString("_id")).matches("[0-9a-f-]{36}");
    assertThat(tira.getInteger("teamId")).isEqualTo(SDEROT);
    assertThat(tira.getInteger("seasonId")).isEqualTo(SEASON);
    assertThat(tira.getString("date")).isEqualTo("2026-09-05");
    assertThat(tira.getString("kickoffTime")).isEqualTo("20:00");
    assertThat(tira.getString("competition")).isEqualTo("ליגה א' דרום");
    assertThat(tira.getInteger("round")).isEqualTo(1);
    assertThat(tira.getString("ownSide")).isEqualTo("AWAY");
    assertThat(tira.getString("homeTeamName")).isEqualTo(TIRA_NAME);
    assertThat(tira.getString("awayTeamName")).isEqualTo(SDEROT_NAME);
    assertThat(tira.getInteger("homeScore")).isEqualTo(4);
    assertThat(tira.getInteger("awayScore")).isEqualTo(1);
    assertThat(tira.getDate("fetchedAt").toInstant()).isEqualTo(FETCHED_AT);
    assertThat(tira.getDate("storedAt")).isNotNull();

    List<Document> appearances = tira.getList("appearances", Document.class);
    assertThat(appearances).hasSize(12);
    assertThat(appearances.stream().map(a -> a.getString("role")).toList())
        .startsWith("STARTER")
        .endsWith("SUBSTITUTE", "UNUSED");
    Document gk = appearances.get(0);
    assertThat(gk.getString("name")).isEqualTo(GK_NAME);
    assertThat(gk.getInteger("shirtNumber")).isEqualTo(1);
    assertThat(gk.getBoolean("goalkeeper")).isTrue();
    assertThat(gk.getBoolean("captain")).isTrue();
    assertThat(events(appearances.get(1))).containsExactly("GOAL 38", "RED 40");
    assertThat(appearances.get(2).getString("name")).isNull();
    assertThat(appearances.get(2).getInteger("sourcePlayerId")).isEqualTo(920003);
    assertThat(events(appearances.get(10)))
        .containsExactly("SUB_ON 57", "SUB_OFF 93", "YELLOW null");
    assertThat(events(appearances.get(11))).containsExactly("YELLOW 88");
    assertThat(events(detailsOf(clubA, MALACHI_GAME).getList("appearances", Document.class).get(0)))
        .containsExactly("GOAL 91", "GOAL 97");
    assertThat(raw("players")).isEmpty();
  }

  @Test
  void anEmptyOkPartIsStoredAndWritesNothing() throws Exception {
    ingest(gameDetailsOnly(gameDetailsWith(FETCHED_AT, List.of())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDetails.outcome").value("STORED"));

    assertThat(raw(DETAILS)).isEmpty();
    assertThat(raw(ATTEMPTS)).isEmpty();
  }

  /** Real pages break every count rule; none of them rejects a game. */
  @Test
  void noCountIsChecked() throws Exception {
    Map<String, Object> malachi = malachiGame(); // no goalkeeper at all
    malachi.put("homeScore", 5); // our goals (2) ≠ our score
    Map<String, Object> part = gameDetailsWith(FETCHED_AT, List.of(), tiraGame(), malachi);
    // tiraGame: 10 starters, 1 sub on and 2 subs off, a yellow on the unused player.

    ingest(gameDetailsOnly(part))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDetails.outcome").value("STORED"));

    assertThat(gameIdsOf(clubA)).containsExactlyInAnyOrder(TIRA_GAME, MALACHI_GAME);
  }

  @Test
  void aNewerFetchReplacesOnlyItsOwnGameAndKeepsItsOpaqueId() throws Exception {
    ingest(gameDetailsOnly(gameDetails(FETCHED_AT))).andExpect(status().isOk());
    Document tiraBefore = detailsOf(clubA, TIRA_GAME);
    Document malachiBefore = detailsOf(clubA, MALACHI_GAME);

    Instant later = FETCHED_AT.plus(1, ChronoUnit.HOURS);
    Map<String, Object> corrected = tiraGame();
    corrected.put("homeScore", 3);
    ingest(gameDetailsOnly(gameDetailsWith(later, List.of(), corrected)))
        .andExpect(jsonPath("$.gameDetails.outcome").value("STORED"));

    Document tiraAfter = detailsOf(clubA, TIRA_GAME);
    assertThat(tiraAfter.getString("_id")).isEqualTo(tiraBefore.getString("_id"));
    assertThat(tiraAfter.getInteger("homeScore")).isEqualTo(3);
    assertThat(tiraAfter.getDate("fetchedAt").toInstant()).isEqualTo(later);
    assertThat(detailsOf(clubA, MALACHI_GAME)).isEqualTo(malachiBefore);

    // Older or equal: skipped silently, the part is still STORED.
    for (Instant notNewer : List.of(FETCHED_AT, later)) {
      Map<String, Object> stale = tiraGame();
      stale.put("homeScore", 7);
      ingest(gameDetailsOnly(gameDetailsWith(notNewer, List.of(), stale)))
          .andExpect(jsonPath("$.gameDetails.outcome").value("STORED"));
      assertThat(detailsOf(clubA, TIRA_GAME)).isEqualTo(tiraAfter);
    }
  }

  @Test
  void aFailedPartIsRecordedAndWritesNothing() throws Exception {
    ingest(gameDetailsOnly(ScrapePayloads.failed("PARSE_ERROR", "the games page didn't load")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDetails.outcome").value("FAILURE_RECORDED"))
        .andExpect(jsonPath("$.gameDetails.reason").isEmpty());

    assertThat(raw(DETAILS)).isEmpty();
    assertThat(raw(ATTEMPTS)).isEmpty();
  }

  @Test
  void anUnmappedTeamIsRejected() throws Exception {
    ingest(request(SDEROT + 1, LEAGUE, SEASON, null, null, null, gameDetails(FETCHED_AT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDetails.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.gameDetails.reason").value("NOT_MAPPED"));

    assertThat(raw(DETAILS)).isEmpty();
  }

  @Test
  void allFourPartsHaveIndependentOutcomes() throws Exception {
    ingest(allFour())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"))
        .andExpect(jsonPath("$.playerStats.outcome").value("STORED"))
        .andExpect(jsonPath("$.gameDetails.outcome").value("STORED"))
        .andExpect(jsonPath("$.clubsUpdated").value(1));

    Map<String, Object> second =
        request(
            SDEROT,
            LEAGUE,
            SEASON,
            fixtures(FETCHED_AT),
            ScrapePayloads.failed("TIMEOUT", null),
            playerStats(FETCHED_AT.plusSeconds(60)),
            ScrapePayloads.failed("SOURCE_BLOCKED", null));
    ingest(second)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("STALE"))
        .andExpect(jsonPath("$.standings.outcome").value("FAILURE_RECORDED"))
        .andExpect(jsonPath("$.playerStats.outcome").value("STORED"))
        .andExpect(jsonPath("$.gameDetails.outcome").value("FAILURE_RECORDED"));
    assertThat(gameIdsOf(clubA)).hasSize(2);
  }

  // --- 400s -------------------------------------------------------------------------------------

  @Test
  void everyInvalidPartIsA400ThatWritesNothingNotEvenTheOtherParts() throws Exception {
    List<Object[]> cases = new ArrayList<>();
    cases.add(
        bad(
            b -> firstEvents(b).add(event("OWN_GOAL", 10)),
            "gameDetails.games[0].players[0].events[1].kind: invalid value"));
    cases.add(
        bad(
            b -> firstEvents(b).getFirst().put("minute", 151),
            "gameDetails.games[0].players[0].events[0].minute: must be less than or equal to"));
    cases.add(
        bad(
            b -> detailsGames(b).getFirst().put("round", 0),
            "gameDetails.games[0].round: must be greater than or equal to 1"));
    cases.add(
        bad(
            b -> detailsPlayers(detailsGames(b).getFirst()).clear(),
            "gameDetails.games[0].players: size must be between 1 and 40"));
    cases.add(
        bad(
            b -> {
              List<Map<String, Object>> players = detailsPlayers(detailsGames(b).getFirst());
              for (int i = players.size(); i < 41; i++) {
                players.add(appearance(930000 + i, "שחקן נוסף", null, "UNUSED", false, false));
              }
            },
            "gameDetails.games[0].players: size must be between 1 and 40"));
    cases.add(
        bad(
            b -> detailsPlayers(detailsGames(b).getFirst()).get(1).put("playerId", 920001),
            "gameDetails.games[0].playerIdsUnique: playerId must be unique"));
    cases.add(
        bad(
            b ->
                ScrapePayloads.part(b, "gameDetails")
                    .put("failures", new ArrayList<>(List.of(gameFailure(TIRA_GAME, "TIMEOUT")))),
            "gameDetails.gameIdsUnique: gameId must be unique across games and failures"));
    cases.add(
        bad(
            b -> detailsGames(b).get(1).put("gameId", TIRA_GAME),
            "gameDetails.gameIdsUnique: gameId must be unique across games and failures"));
    cases.add(
        bad(
            b -> detailsGames(b).getFirst().put("awayTeamName", " " + TIRA_NAME + " "),
            "gameDetails.games[0].twoTeams: homeTeamName and awayTeamName must differ"));
    cases.add(
        bad(
            b -> detailsPlayers(detailsGames(b).getFirst()).getFirst().put("name", "עומר\tגבאי"),
            "gameDetails.games[0].players[0].name: "));
    cases.add(
        bad(
            b -> ScrapePayloads.part(b, "gameDetails").put("reason", "TIMEOUT"),
            "gameDetails.consistentWithStatus: status OK needs fetchedAt, games and failures and"
                + " no reason or detail"));
    cases.add(
        bad(
            b -> {
              Map<String, Object> failed = ScrapePayloads.failed("TIMEOUT", null);
              failed.put("games", List.of(tiraGame()));
              b.put("gameDetails", failed);
            },
            "gameDetails.consistentWithStatus: "));
    cases.add(
        bad(
            b ->
                ScrapePayloads.part(b, "gameDetails")
                    .put("fetchedAt", Instant.now().plus(10, ChronoUnit.MINUTES).toString()),
            "gameDetails.fetchedAt: must not be in the future"));
    cases.add(
        bad(
            b -> detailsPlayers(detailsGames(b).getFirst()).getFirst().put("minutesPlayed", 90),
            "gameDetails.games[0].players[0].minutesPlayed: invalid value"));

    for (Object[] testCase : cases) {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) testCase[0];
      String expected = (String) testCase[1];
      ingest(body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.details[0]", startsWith(expected)));
      for (String collection :
          List.of(
              DETAILS, ATTEMPTS, "league_fixtures", "league_standings", "player_season_stats")) {
        assertThat(raw(collection)).as(expected + " / " + collection).isEmpty();
      }
    }
  }

  // --- attempts ---------------------------------------------------------------------------------

  @Test
  void threeParseErrorsGiveAGameUpAndTwoDont() throws Exception {
    failGame(NIR_RAM_GAME, "PARSE_ERROR", 1);
    failGame(NIR_RAM_GAME, "PARSE_ERROR", 2);
    assertThat(attemptsOf(NIR_RAM_GAME).getInteger("failedAttempts")).isEqualTo(2);
    assertThat(known()).doesNotContain(NIR_RAM_GAME);

    failGame(NIR_RAM_GAME, "PARSE_ERROR", 3);

    assertThat(attemptsOf(NIR_RAM_GAME).getInteger("failedAttempts")).isEqualTo(3);
    assertThat(attemptsOf(NIR_RAM_GAME).getString("lastReason")).isEqualTo("PARSE_ERROR");
    assertThat(known()).containsExactly(NIR_RAM_GAME);
  }

  @Test
  void sourceErrorsCountTheSame() throws Exception {
    for (int i = 1; i <= 3; i++) {
      failGame(NIR_RAM_GAME, "SOURCE_ERROR", i);
    }
    assertThat(known()).containsExactly(NIR_RAM_GAME);
  }

  @Test
  void timeoutsAndBlocksNeverGiveAGameUp() throws Exception {
    for (int i = 1; i <= 10; i++) {
      failGame(NIR_RAM_GAME, i % 2 == 0 ? "TIMEOUT" : "SOURCE_BLOCKED", i);
    }
    Document attempts = attemptsOf(NIR_RAM_GAME);
    assertThat(attempts.getInteger("failedAttempts")).isZero();
    assertThat(attempts.getString("lastReason")).isEqualTo("TIMEOUT");
    assertThat(attempts.getDate("lastAttemptAt").toInstant()).isEqualTo(FETCHED_AT.plusSeconds(10));
    assertThat(known()).isEmpty();
  }

  @Test
  void aReplayedFailureIsCountedOnce() throws Exception {
    failGame(NIR_RAM_GAME, "PARSE_ERROR", 1);
    failGame(NIR_RAM_GAME, "PARSE_ERROR", 1);
    failGame(NIR_RAM_GAME, "PARSE_ERROR", 0);
    assertThat(attemptsOf(NIR_RAM_GAME).getInteger("failedAttempts")).isEqualTo(1);
  }

  @Test
  void storingAGameDeletesItsAttempts() throws Exception {
    failGame(TIRA_GAME, "PARSE_ERROR", 1);
    failGame(TIRA_GAME, "PARSE_ERROR", 2);

    ingest(gameDetailsOnly(gameDetailsWith(FETCHED_AT.plusSeconds(3), List.of(), tiraGame())))
        .andExpect(status().isOk());

    assertThat(raw(ATTEMPTS)).isEmpty();
    assertThat(gameIdsOf(clubA)).containsExactly(TIRA_GAME);
    assertThat(known()).containsExactly(TIRA_GAME);
  }

  @Test
  void aFailureForAStoredGameIsIgnored() throws Exception {
    ingest(gameDetailsOnly(gameDetails(FETCHED_AT))).andExpect(status().isOk());

    failGame(TIRA_GAME, "PARSE_ERROR", 1);

    assertThat(raw(ATTEMPTS)).isEmpty();
  }

  // --- the known games the trigger would send ----------------------------------------------------

  /**
   * Decision 1, exercised with the flag {@code true}: stored ∪ given up, of the triggering club's
   * current team and season only, ascending, without duplicates.
   */
  @Test
  void theTriggersKnownGamesAreTheClubsStoredAndGivenUpGamesOfItsCurrentMapping() throws Exception {
    // A's previous season: a stored game that must not be listed.
    clubs.map(clubA, SDEROT, LEAGUE, SEASON - 1);
    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON - 1,
                null,
                null,
                null,
                gameDetailsWith(FETCHED_AT, List.of(), detailsGame(555001, tiraGame()))))
        .andExpect(jsonPath("$.gameDetails.outcome").value("STORED"));
    clubs.map(clubA, SDEROT, LEAGUE, SEASON);
    // Another club's (another team's) stored game.
    clubs.createClub("Tira", TIRA, LEAGUE, SEASON);
    ingest(
            request(
                TIRA,
                LEAGUE,
                SEASON,
                null,
                null,
                null,
                gameDetailsWith(FETCHED_AT, List.of(), detailsGame(777001, tiraGame()))))
        .andExpect(jsonPath("$.gameDetails.outcome").value("STORED"));
    // A's own: two stored, one given up, one with only two failures.
    ingest(gameDetailsOnly(gameDetails(FETCHED_AT))).andExpect(status().isOk());
    for (int i = 1; i <= 3; i++) {
      failGame(NIR_RAM_GAME, "PARSE_ERROR", i);
    }
    failGame(1143937, "PARSE_ERROR", 1);
    failGame(1143937, "PARSE_ERROR", 2);
    // A stored game that also has a given-up record (can't happen through the ingest).
    mongoTemplate
        .getCollection(ATTEMPTS)
        .insertOne(
            new Document("clubId", clubA)
                .append("teamId", SDEROT)
                .append("seasonId", SEASON)
                .append("gameId", TIRA_GAME)
                .append("failedAttempts", 3)
                .append("version", 0L));

    ScrapeTriggerService.GameDetailsRequest request =
        clubContext.callAs(clubA, () -> triggerService.gameDetailsRequest(true, SDEROT_IDS));

    assertThat(request.requested()).isTrue();
    assertThat(request.knownGameIds()).containsExactly(TIRA_GAME, MALACHI_GAME, NIR_RAM_GAME);
    assertThat(
            clubContext.callAs(clubA, () -> triggerService.gameDetailsRequest(false, SDEROT_IDS)))
        .isEqualTo(ScrapeTriggerService.GameDetailsRequest.NONE);
  }

  // --- indexes ----------------------------------------------------------------------------------

  @Test
  void eachCollectionHasAUniqueIndexThatRejectsADuplicateGame() {
    for (String collection : List.of(DETAILS, ATTEMPTS)) {
      Document index =
          mongoTemplate.getCollection(collection).listIndexes().into(new ArrayList<>()).stream()
              .filter(i -> i.getString("name").equals("club_team_season_game_unique"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no unique index on " + collection));
      assertThat(index.getBoolean("unique")).as(collection).isTrue();
      assertThat(index.get("key", Document.class).keySet())
          .as(collection)
          .containsExactly("clubId", "teamId", "seasonId", "gameId");

      Document game =
          new Document("clubId", clubA)
              .append("teamId", SDEROT)
              .append("seasonId", SEASON)
              .append("gameId", TIRA_GAME);
      mongoTemplate.getCollection(collection).insertOne(new Document(game).append("_id", "a"));
      assertThatThrownBy(
              () ->
                  mongoTemplate
                      .getCollection(collection)
                      .insertOne(new Document(game).append("_id", "b")))
          .as(collection)
          .isInstanceOf(MongoWriteException.class)
          .hasMessageContaining("E11000");
      // Another club, or another game, is fine.
      mongoTemplate
          .getCollection(collection)
          .insertOne(new Document(game).append("_id", "c").append("clubId", "other"));
      mongoTemplate
          .getCollection(collection)
          .insertOne(new Document(game).append("_id", "d").append("gameId", MALACHI_GAME));
    }
  }

  // --- logs -------------------------------------------------------------------------------------

  @Test
  void noLogLineNamesAPlayerOrATeamOrCarriesTheScrapersDetail() throws Exception {
    ingest(allFour()).andExpect(status().isOk());
    ingest(
            gameDetailsOnly(
                gameDetailsWith(
                    FETCHED_AT.plusSeconds(5),
                    List.of(gameFailure(NIR_RAM_GAME, "PARSE_ERROR")),
                    tiraGame())))
        .andExpect(status().isOk());
    ingest(
            change(
                b -> detailsPlayers(detailsGames(b).getFirst()).getFirst().put("shirtNumber", 100)))
        .andExpect(status().isBadRequest());

    String logged =
        logs.events().stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
            .map(e -> e.getFormattedMessage())
            .reduce("", (a, b) -> a + "\n" + b);
    assertThat(logged).contains("Game details for team " + SDEROT);
    for (String text :
        List.of(
            GK_NAME,
            SCORER_NAME,
            SUB_NAME,
            BENCH_NAME,
            SDEROT_NAME,
            TIRA_NAME,
            KIRYAT_MALACHI_NAME,
            "unknown event icon")) {
      assertThat(logged).doesNotContain(text);
    }
    assertThat(logs.everythingLogged()).doesNotContain(GK_NAME, SCORER_NAME, SDEROT_NAME);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static Map<String, Object> allFour() {
    return request(
        SDEROT,
        LEAGUE,
        SEASON,
        fixtures(FETCHED_AT),
        standings(FETCHED_AT),
        playerStats(FETCHED_AT),
        gameDetails(FETCHED_AT));
  }

  /** All four parts, valid, with one change. */
  private static Map<String, Object> change(Consumer<Map<String, Object>> change) {
    Map<String, Object> body = allFour();
    change.accept(body);
    return body;
  }

  private static Object[] bad(Consumer<Map<String, Object>> change, String expectedPrefix) {
    return new Object[] {change(change), expectedPrefix};
  }

  /** The first player's events in the first game (the goalkeeper: one yellow). */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> firstEvents(Map<String, Object> body) {
    return (List<Map<String, Object>>)
        detailsPlayers(detailsGames(body).getFirst()).getFirst().get("events");
  }

  /** A copy of {@code template} with another game id. */
  private static Map<String, Object> detailsGame(int gameId, Map<String, Object> template) {
    Map<String, Object> game = new LinkedHashMap<>(template);
    game.put("gameId", gameId);
    return game;
  }

  /** One scrape, {@code n} seconds after {@link ScrapePayloads#FETCHED_AT}, failing one game. */
  private void failGame(int gameId, String reason, int n) throws Exception {
    ingest(
            gameDetailsOnly(
                gameDetailsWith(FETCHED_AT.plusSeconds(n), List.of(gameFailure(gameId, reason)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDetails.outcome").value("STORED"));
  }

  private List<Integer> known() {
    return clubContext.callAs(clubA, () -> knownGames.knownGameIds(SDEROT, SEASON));
  }

  private ResultActions ingest(Object body) throws Exception {
    return mockMvc.perform(
        post(INGEST)
            .header("Authorization", "Bearer " + SERVICE_TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
  }

  private List<Integer> gameIdsOf(String clubId) {
    return mongoTemplate
        .getCollection(DETAILS)
        .find(new Document("clubId", clubId))
        .map(d -> d.getInteger("gameId"))
        .into(new ArrayList<>());
  }

  private Document detailsOf(String clubId, int gameId) {
    return mongoTemplate
        .getCollection(DETAILS)
        .find(new Document("clubId", clubId).append("gameId", gameId))
        .first();
  }

  private Document attemptsOf(int gameId) {
    return mongoTemplate
        .getCollection(ATTEMPTS)
        .find(new Document("clubId", clubA).append("gameId", gameId))
        .first();
  }

  private static List<String> events(Document appearance) {
    return appearance.getList("events", Document.class).stream()
        .map(e -> e.getString("kind") + " " + e.getInteger("minute"))
        .toList();
  }

  private List<Document> raw(String collection) {
    return mongoTemplate.getCollection(collection).find().into(new ArrayList<>());
  }
}
