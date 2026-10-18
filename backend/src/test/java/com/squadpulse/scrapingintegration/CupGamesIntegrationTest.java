package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapePayloads.FETCHED_AT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.HADERA_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.KIRYAT_MALACHI_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEAGUE;
import static com.squadpulse.scrapingintegration.ScrapePayloads.MALACHI_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.NIR_RAM_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.NIR_RAM_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.RAHAT_CUP_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.RAHAT_FULL_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT_FULL_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SEASON;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SHIMSHON_CUP_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SHIMSHON_FULL_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.cupCompetitions;
import static com.squadpulse.scrapingintegration.ScrapePayloads.cupGame;
import static com.squadpulse.scrapingintegration.ScrapePayloads.cupGames;
import static com.squadpulse.scrapingintegration.ScrapePayloads.cupGamesOnly;
import static com.squadpulse.scrapingintegration.ScrapePayloads.cupGamesWith;
import static com.squadpulse.scrapingintegration.ScrapePayloads.failed;
import static com.squadpulse.scrapingintegration.ScrapePayloads.failedCompetition;
import static com.squadpulse.scrapingintegration.ScrapePayloads.fixtures;
import static com.squadpulse.scrapingintegration.ScrapePayloads.gameDetailsWith;
import static com.squadpulse.scrapingintegration.ScrapePayloads.gameFailure;
import static com.squadpulse.scrapingintegration.ScrapePayloads.json;
import static com.squadpulse.scrapingintegration.ScrapePayloads.part;
import static com.squadpulse.scrapingintegration.ScrapePayloads.rahatCupGame;
import static com.squadpulse.scrapingintegration.ScrapePayloads.request;
import static com.squadpulse.scrapingintegration.ScrapePayloads.shimshonCupGame;
import static com.squadpulse.scrapingintegration.ScrapePayloads.standings;
import static com.squadpulse.scrapingintegration.ScrapePayloads.stateCup;
import static com.squadpulse.scrapingintegration.ScrapePayloads.tiraGame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.CapturedLogs;
import com.squadpulse.common.ClubContext;
import com.squadpulse.match.KnownGames;
import java.time.Instant;
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
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;

/**
 * The {@code cupGames} part (KAN-84) end to end on a real MongoDB, through the real {@code
 * /internal/scrape-results} with the service token and the real read endpoints with user tokens:
 * validation (a 400 writes nothing, not even the request's league part), the per-competition ingest
 * (replace, staleness, a failed competition, a duplicate, the empty-list rule, isolation, the
 * league snapshot untouched), the response, the merged {@code GET /matches/fixtures} (order, our
 * side, no result with extra time or penalties, details state, {@code state} / {@code
 * lastUpdatedAt} / {@code teamName}, no football.org.il id), {@code GET /matches/games/{id}}'s
 * result rule, and the known-games list.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + CupGamesIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import({TestAccessTokens.class, TestClubMappings.class})
class CupGamesIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  private static final String INGEST = "/internal/scrape-results";
  private static final String CUPS = "cup_fixtures";

  /** Not played, at the same date and time as the league's 1143937 against Tira. */
  private static final int SAME_SLOT_CUP_GAME = 1150001;

  /** Drawn 1-1 at Rahat, decided on penalties. */
  private static final int PENALTIES_CUP_GAME = 1150002;

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
  @Autowired private TestAccessTokens tokens;
  @Autowired private ClubContext clubContext;
  @Autowired private KnownGames knownGames;

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
    clubContext.clear();
    for (String collection :
        List.of(
            "league_fixtures",
            "league_standings",
            "player_season_stats",
            "game_details",
            "game_detail_attempts",
            CUPS)) {
      mongoTemplate.getCollection(collection).deleteMany(new Document());
    }
    clubs.deleteAllClubs();
  }

  // --- the contract: 400s write nothing ---------------------------------------------------------

  /**
   * Each broken rule is a 400 for the whole request: neither the cup games nor the league fixtures
   * and standings of the same request are written.
   */
  @Test
  void everyBrokenRuleIsA400AndWritesNothingNotEvenTheLeague() throws Exception {
    Map<String, Consumer<Map<String, Object>>> cases = new LinkedHashMap<>();
    cases.put(
        "an unknown competition",
        b -> cupCompetitions(b).getFirst().put("competition", "TOTO_CUP"));
    cases.put(
        "the same competition twice",
        b -> cupCompetitions(b).add(new LinkedHashMap<>(cupCompetitions(b).getFirst())));
    cases.put("part OK without competitions", b -> part(b, "cupGames").remove("competitions"));
    cases.put(
        "part FAILED with competitions",
        b -> {
          part(b, "cupGames").put("status", "FAILED");
          part(b, "cupGames").put("reason", "PARSE_ERROR");
          part(b, "cupGames").remove("fetchedAt");
        });
    cases.put("a null competition", b -> cupCompetitions(b).set(0, null));
    cases.put("competition OK without games", b -> cupCompetitions(b).getFirst().remove("games"));
    cases.put(
        "competition FAILED without a reason",
        b -> {
          cupCompetitions(b).getFirst().put("status", "FAILED");
          cupCompetitions(b).getFirst().remove("games");
        });
    cases.put(
        "competition OK with a reason",
        b -> cupCompetitions(b).getFirst().put("reason", "PARSE_ERROR"));
    cases.put(
        "51 games",
        b -> {
          List<Map<String, Object>> games = cupGames(b, 0);
          games.clear();
          for (int i = 0; i < 51; i++) {
            games.add(
                cupGame(
                    null,
                    "round " + i,
                    "2026-09-01",
                    null,
                    SDEROT_FULL_NAME,
                    RAHAT_FULL_NAME,
                    "HOME",
                    null,
                    null,
                    null,
                    null,
                    null));
          }
        });
    cases.put("a null game", b -> cupGames(b, 0).set(0, null));
    cases.put(
        "home = away after trimming",
        b -> cupGames(b, 0).getFirst().put("awayTeamName", "  " + SDEROT_FULL_NAME + " "));
    cases.put("one score set", b -> cupGames(b, 0).getFirst().put("awayScore", null));
    cases.put("a score of 100", b -> cupGames(b, 0).getFirst().put("homeScore", 100));
    cases.put("a blank round", b -> cupGames(b, 0).getFirst().put("round", "  "));
    cases.put("a null round", b -> cupGames(b, 0).getFirst().put("round", null));
    cases.put("a 101-char round", b -> cupGames(b, 0).getFirst().put("round", "ו".repeat(101)));
    cases.put("51-char extraTime", b -> cupGames(b, 0).getFirst().put("extraTime", "1".repeat(51)));
    cases.put("51-char penalties", b -> cupGames(b, 0).getFirst().put("penalties", "1".repeat(51)));
    cases.put("an empty extraTime", b -> cupGames(b, 0).getFirst().put("extraTime", " "));
    cases.put("a control character in round", b -> cupGames(b, 0).getFirst().put("round", "ו\t"));
    cases.put(
        "a control character in extraTime",
        b -> cupGames(b, 0).getFirst().put("extraTime", "\u00071-1"));
    cases.put(
        "a control character in a name",
        b -> cupGames(b, 0).getFirst().put("homeTeamName", SDEROT_FULL_NAME + "\u001F"));
    cases.put("an invalid date", b -> cupGames(b, 0).getFirst().put("date", "2026-02-30"));
    cases.put("kickoff 24:00", b -> cupGames(b, 0).getFirst().put("kickoffTime", "24:00"));
    cases.put("no ownSide", b -> cupGames(b, 0).getFirst().put("ownSide", null));
    cases.put("gameId 0", b -> cupGames(b, 0).getFirst().put("gameId", 0));
    cases.put("an unknown game property", b -> cupGames(b, 0).getFirst().put("homeTeamId", SDEROT));
    cases.put(
        "fetchedAt more than 5 minutes ahead",
        b -> part(b, "cupGames").put("fetchedAt", Instant.now().plusSeconds(400).toString()));

    for (Map.Entry<String, Consumer<Map<String, Object>>> broken : cases.entrySet()) {
      Map<String, Object> body = leagueAndCup(FETCHED_AT, FETCHED_AT);
      broken.getValue().accept(body);

      String answer =
          ingest(body)
              .andExpect(status().isBadRequest())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(answer).as(broken.getKey()).contains("cupGames");
      assertThat(rawDocuments(CUPS)).as(broken.getKey()).isEmpty();
      assertThat(rawDocuments("league_fixtures")).as(broken.getKey()).isEmpty();
      assertThat(rawDocuments("league_standings")).as(broken.getKey()).isEmpty();
    }
  }

  /**
   * Verification (KAN-84): Jackson 3 in this project rejects an unknown enum value as a 400 naming
   * the field, not silently as {@code null} ({@code READ_UNKNOWN_ENUM_VALUES_AS_NULL} is off by
   * default, and nothing turns it on).
   */
  @Test
  void anUnknownCompetitionIsA400NamingTheField() throws Exception {
    Map<String, Object> body = cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame())));
    cupCompetitions(body).getFirst().put("competition", "TOTO_CUP");

    ingest(body)
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.details[0]").value("cupGames.competitions[0].competition: invalid value"));
  }

  @Test
  void theCupPartAloneIsAValidRequest() throws Exception {
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cupGames.outcome").value("STORED"))
        .andExpect(jsonPath("$.fixtures.outcome").value("NOT_SENT"));
  }

  /** Not a 400 on purpose: extra time / penalties without scores (an unseen format). */
  @Test
  void extraTimeOrPenaltiesWithoutScoresAreAccepted() throws Exception {
    Map<String, Object> game = shimshonCupGame();
    game.put("homeScore", null);
    game.put("awayScore", null);
    game.put("extraTime", " 120' ");
    game.put("penalties", "x");

    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(game))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STORED"));

    Document stored = storedGames(clubA).getFirst();
    assertThat(stored.getString("extraTime")).isEqualTo("120'");
    assertThat(stored.getString("penalties")).isEqualTo("x");
  }

  // --- the ingest
  // ---------------------------------------------------------------------------------

  /**
   * The first store, as raw documents: one per club / team / season / competition, the competition
   * stored as its name (verification: an enum field round-trips as its name in this Spring Data
   * MongoDB version, also read back below through the fixtures read), no league id, no team ids.
   */
  @Test
  void aCompetitionIsStoredAsOneSnapshotWithItsGamesInOrder() throws Exception {
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame(), rahatCupGame()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cupGames.outcome").value("STORED"))
        .andExpect(jsonPath("$.cupGames.reason").isEmpty())
        .andExpect(jsonPath("$.cupGames.competitions[0].competition").value("STATE_CUP"))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STORED"))
        .andExpect(jsonPath("$.clubsUpdated").value(0));

    List<Document> documents = rawDocuments(CUPS);
    assertThat(documents).hasSize(1);
    Document snapshot = documents.getFirst();
    assertThat(snapshot.getString("clubId")).isEqualTo(clubA);
    assertThat(snapshot.getInteger("teamId")).isEqualTo(SDEROT);
    assertThat(snapshot.getInteger("seasonId")).isEqualTo(SEASON);
    assertThat(snapshot.get("competition")).isEqualTo("STATE_CUP");
    assertThat(snapshot.getDate("fetchedAt").toInstant()).isEqualTo(FETCHED_AT);
    assertThat(snapshot.getDate("storedAt")).isNotNull();
    assertThat(snapshot).doesNotContainKey("leagueId");
    List<Document> games = snapshot.getList("games", Document.class);
    assertThat(games).hasSize(2);
    Document rahat = games.get(1);
    assertThat(rahat.getString("key"))
        .isEqualTo("ו\u001F" + RAHAT_FULL_NAME + "\u001F" + SDEROT_FULL_NAME);
    assertThat(rahat.getInteger("gameId")).isEqualTo(RAHAT_CUP_GAME);
    assertThat(rahat.getString("round")).isEqualTo("ו");
    assertThat(rahat.getString("date")).isEqualTo("2026-09-26");
    assertThat(rahat.getString("kickoffTime")).isEqualTo("15:00");
    assertThat(rahat.getString("ownSide")).isEqualTo("AWAY");
    assertThat(rahat.getInteger("homeScore")).isEqualTo(1);
    assertThat(rahat.getInteger("awayScore")).isEqualTo(4);
    assertThat(rahat).doesNotContainKeys("homeTeamId", "awayTeamId");
  }

  /**
   * Verification (KAN-84): {@code auto-index-creation} builds the four-field unique index from the
   * {@code @CompoundIndex}, exactly as for {@code LeagueFixturesSnapshot}.
   */
  @Test
  void theSnapshotIndexIsCreatedAtStartupAndUnique() {
    IndexInfo index =
        mongoTemplate.indexOps(CUPS).getIndexInfo().stream()
            .filter(info -> info.getName().equals("club_team_season_competition_unique"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No cup snapshot index"));
    assertThat(index.isUnique()).isTrue();
    assertThat(index.getIndexFields())
        .extracting(field -> field.getKey())
        .containsExactly("clubId", "teamId", "seasonId", "competition");
  }

  @Test
  void aNewerListReplacesTheWholeSnapshotAndAnOlderOrEqualOneIsStale() throws Exception {
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame(), rahatCupGame()))))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STORED"));

    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT.plusSeconds(60), stateCup(rahatCupGame()))))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STORED"));
    assertThat(storedGames(clubA))
        .extracting(g -> g.getInteger("gameId"))
        .containsExactly(RAHAT_CUP_GAME);

    for (Instant notNewer : List.of(FETCHED_AT.plusSeconds(60), FETCHED_AT.plusSeconds(30))) {
      ingest(cupGamesOnly(cupGamesWith(notNewer, stateCup(shimshonCupGame(), rahatCupGame()))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.cupGames.outcome").value("STORED"))
          .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STALE"))
          .andExpect(jsonPath("$.cupGames.competitions[0].reason").isEmpty());
    }
    assertThat(storedGames(clubA)).hasSize(1);
  }

  @Test
  void aFailedCompetitionKeepsItsStoredGames() throws Exception {
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame()))));

    ingest(
            cupGamesOnly(
                cupGamesWith(
                    FETCHED_AT.plusSeconds(60), failedCompetition("STATE_CUP", "PARSE_ERROR"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cupGames.outcome").value("STORED"))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("FAILURE_RECORDED"))
        .andExpect(jsonPath("$.cupGames.competitions[0].reason").isEmpty());

    assertThat(storedGames(clubA)).hasSize(1);
    assertThat(rawDocuments(CUPS).getFirst().getDate("fetchedAt").toInstant())
        .isEqualTo(FETCHED_AT);

    // Not even over an empty snapshot, where an empty write would pass the empty-list rule.
    tearDownCups();
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup())));
    ingest(
            cupGamesOnly(
                cupGamesWith(
                    FETCHED_AT.plusSeconds(60), failedCompetition("STATE_CUP", "TIMEOUT"))))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("FAILURE_RECORDED"));
    assertThat(rawDocuments(CUPS).getFirst().getDate("fetchedAt").toInstant())
        .isEqualTo(FETCHED_AT);
  }

  private void tearDownCups() {
    mongoTemplate.getCollection(CUPS).deleteMany(new Document());
  }

  @Test
  void aFailedPartIsRecordedWithNoCompetitionsAndKeepsTheStoredGames() throws Exception {
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame()))));

    ingest(cupGamesOnly(failed("SOURCE_ERROR", "team page 500")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cupGames.outcome").value("FAILURE_RECORDED"))
        .andExpect(jsonPath("$.cupGames.reason").isEmpty())
        .andExpect(jsonPath("$.cupGames.competitions").isEmpty());

    assertThat(storedGames(clubA)).hasSize(1);
  }

  @Test
  void anAbsentPartIsNotSentWithNoCompetitions() throws Exception {
    ingest(request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), standings(FETCHED_AT)))
        .andExpect(jsonPath("$.cupGames.outcome").value("NOT_SENT"))
        .andExpect(jsonPath("$.cupGames.reason").isEmpty())
        .andExpect(jsonPath("$.cupGames.competitions").isEmpty());
  }

  /** Independence, both ways: the league failing doesn't stop the cup, nor the reverse. */
  @Test
  void theLeagueAndTheCupAreIndependent() throws Exception {
    Map<String, Object> leagueFailed = leagueAndCup(FETCHED_AT, FETCHED_AT);
    leagueFailed.put("fixtures", failed("TIMEOUT", null));
    ingest(leagueFailed)
        .andExpect(jsonPath("$.fixtures.outcome").value("FAILURE_RECORDED"))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STORED"));
    assertThat(storedGames(clubA)).hasSize(2);
    assertThat(rawDocuments("league_fixtures")).isEmpty();

    Map<String, Object> cupRejected = leagueAndCup(FETCHED_AT, FETCHED_AT.plusSeconds(60));
    cupGames(cupRejected, 0).add(new LinkedHashMap<>(cupGames(cupRejected, 0).getFirst()));
    ingest(cupRejected)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.cupGames.outcome").value("STORED"))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("REJECTED"))
        .andExpect(jsonPath("$.cupGames.competitions[0].reason").value("DUPLICATE_GAME"));
    assertThat(rawDocuments("league_fixtures")).hasSize(1);
    // The stored cup games stay.
    assertThat(storedGames(clubA)).hasSize(2);
    assertThat(rawDocuments(CUPS).getFirst().getDate("fetchedAt").toInstant())
        .isEqualTo(FETCHED_AT);
  }

  @Test
  void theSamePairingInTwoRoundsIsTwoGames() throws Exception {
    Map<String, Object> replay = shimshonCupGame();
    replay.put("round", "ו");
    replay.put("gameId", null);

    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame(), replay))))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STORED"));

    assertThat(storedGames(clubA)).hasSize(2);
  }

  @Test
  void anEmptyListIsStoredOverNothingOrAnEmptySnapshotButNeverOverStoredGames() throws Exception {
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup())))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STORED"));
    assertThat(storedGames(clubA)).isEmpty();

    // Over an empty snapshot: stored (a newer empty one).
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT.plusSeconds(10), stateCup())))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STORED"));
    assertThat(rawDocuments(CUPS).getFirst().getDate("fetchedAt").toInstant())
        .isEqualTo(FETCHED_AT.plusSeconds(10));

    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT.plusSeconds(20), stateCup(shimshonCupGame()))))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STORED"));

    // Over stored games: refused, the games stay.
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT.plusSeconds(30), stateCup())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cupGames.outcome").value("STORED"))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("REJECTED"))
        .andExpect(jsonPath("$.cupGames.competitions[0].reason").value("EMPTY_REPLACES_GAMES"));
    assertThat(storedGames(clubA)).hasSize(1);
    assertThat(rawDocuments(CUPS).getFirst().getDate("fetchedAt").toInstant())
        .isEqualTo(FETCHED_AT.plusSeconds(20));

    // An old empty replay is just stale.
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT.plusSeconds(5), stateCup())))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("STALE"));
  }

  @Test
  void withoutAMappedClubEveryCompetitionIsRejectedNotMapped() throws Exception {
    Map<String, Object> body = cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame())));
    body.put("teamId", 9999);

    ingest(body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cupGames.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.cupGames.reason").value("NOT_MAPPED"))
        .andExpect(jsonPath("$.cupGames.competitions[0].outcome").value("REJECTED"))
        .andExpect(jsonPath("$.cupGames.competitions[0].reason").value("NOT_MAPPED"));
    assertThat(rawDocuments(CUPS)).isEmpty();
  }

  /**
   * A and B, mapped to the same team, league and season, each get their own copy; C, mapped to
   * another team, gets nothing; and A's read never shows C's own cup games.
   */
  @Test
  void eachMappedClubGetsItsOwnCopyAndNoOtherClubSeesIt() throws Exception {
    String clubB = clubs.createClub("Sderot fans", SDEROT, LEAGUE, SEASON);
    String clubC = clubs.createClub("Tira", TIRA, LEAGUE, SEASON);
    String otherSeason = clubs.createClub("Sderot last season", SDEROT, LEAGUE, SEASON - 1);

    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame()))));
    assertThat(rawDocuments(CUPS))
        .extracting(d -> d.getString("clubId"))
        .containsExactlyInAnyOrder(clubA, clubB);

    Map<String, Object> tiras = cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(rahatCupGame())));
    tiras.put("teamId", TIRA);
    ingest(tiras);
    assertThat(storedGames(clubC))
        .extracting(g -> g.getInteger("gameId"))
        .containsExactly(RAHAT_CUP_GAME);
    assertThat(storedGames(clubA))
        .extracting(g -> g.getInteger("gameId"))
        .containsExactly(SHIMSHON_CUP_GAME);

    JsonNode a = fixturesOf(clubA);
    assertThat(a.get("games")).hasSize(1);
    assertThat(a.get("games").get(0).get("opponentName").stringValue())
        .isEqualTo(SHIMSHON_FULL_NAME);
    assertThat(fixturesOf(clubC).get("games").get(0).get("opponentName").stringValue())
        .isEqualTo(RAHAT_FULL_NAME);
    assertThat(fixturesOf(otherSeason).get("state").stringValue()).isEqualTo("NO_DATA");
  }

  /** A cup ingest leaves the league snapshot's document exactly as it was. */
  @Test
  void aCupIngestLeavesTheLeagueSnapshotUntouched() throws Exception {
    ingest(request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), standings(FETCHED_AT)));
    Document before = rawDocuments("league_fixtures").getFirst();

    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT.plusSeconds(60), stateCup(shimshonCupGame()))));
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT.plusSeconds(120), stateCup())));
    ingest(
        cupGamesOnly(
            cupGamesWith(FETCHED_AT.plusSeconds(180), failedCompetition("STATE_CUP", "TIMEOUT"))));

    assertThat(rawDocuments("league_fixtures")).containsExactly(before);
  }

  /** Ids, competition and counts only: never a team name, never a round. */
  @Test
  void noLogLineNamesATeamOrARound() throws Exception {
    ingest(leagueAndCup(FETCHED_AT, FETCHED_AT));
    ingest(
        cupGamesOnly(
            cupGamesWith(
                FETCHED_AT.plusSeconds(60), failedCompetition("STATE_CUP", "PARSE_ERROR"))));
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT.plusSeconds(90), stateCup())));

    String logged = logs.everythingLogged();
    assertThat(logged).contains("Cup games STATE_CUP for team " + SDEROT);
    assertThat(logged)
        .doesNotContain(SDEROT_FULL_NAME, SHIMSHON_FULL_NAME, RAHAT_FULL_NAME, "ליגות א' דרום");
    assertThat(logged).doesNotContain("no h1 match"); // the scraper's detail: DEBUG only
  }

  // --- GET /matches/fixtures
  // ----------------------------------------------------------------------

  /**
   * League and cup merged: by date, then kickoff (unset last within its day, across both), then
   * league before cup at the same date and time.
   */
  @Test
  void theFixturesMergeLeagueAndCupGamesInOrder() throws Exception {
    ingest(request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), standings(FETCHED_AT)));
    ingest(
        cupGamesOnly(
            cupGamesWith(
                FETCHED_AT.plusSeconds(60),
                stateCup(
                    // Unset time first in the list, so the order can't come from the input.
                    cupGame(
                        null,
                        "ז",
                        "2026-10-03",
                        null,
                        SDEROT_FULL_NAME,
                        "הפועל אופקים",
                        "HOME",
                        null,
                        null,
                        null,
                        null,
                        null),
                    cupGame(
                        SAME_SLOT_CUP_GAME,
                        "ז",
                        "2026-10-03",
                        "16:00",
                        "מכבי נתיבות",
                        SDEROT_FULL_NAME,
                        "AWAY",
                        null,
                        null,
                        null,
                        null,
                        null),
                    rahatCupGame(),
                    shimshonCupGame()))));

    JsonNode fixtures = fixturesOf(clubA);
    assertThat(fixtures.get("state").stringValue()).isEqualTo("OK");
    assertThat(fixtures.get("teamName").stringValue()).isEqualTo(SDEROT_NAME);
    // The older of the two snapshots.
    assertThat(fixtures.get("lastUpdatedAt").stringValue()).isEqualTo(FETCHED_AT.toString());
    List<String> order = new ArrayList<>();
    for (JsonNode game : fixtures.get("games")) {
      order.add(
          game.get("date").stringValue()
              + " "
              + (game.get("kickoffTime").isNull() ? "--:--" : game.get("kickoffTime").stringValue())
              + " "
              + game.get("competition").stringValue()
              + " "
              + game.get("opponentName").stringValue());
    }
    assertThat(order)
        .containsExactly(
            "2026-08-27 19:00 STATE_CUP " + SHIMSHON_FULL_NAME,
            "2026-09-05 20:00 LEAGUE " + TIRA_NAME,
            "2026-09-12 19:30 LEAGUE " + KIRYAT_MALACHI_NAME,
            "2026-09-19 18:00 LEAGUE " + NIR_RAM_NAME,
            "2026-09-26 15:00 STATE_CUP " + RAHAT_FULL_NAME,
            "2026-10-03 16:00 LEAGUE " + TIRA_NAME,
            "2026-10-03 16:00 STATE_CUP מכבי נתיבות",
            "2026-10-03 --:-- LEAGUE " + HADERA_NAME,
            "2026-10-03 --:-- STATE_CUP הפועל אופקים");

    JsonNode league = fixtures.get("games").get(1);
    assertThat(league.get("round").isNull()).isTrue();
    assertThat(league.get("extraTime").isNull()).isTrue();
    assertThat(league.get("penalties").isNull()).isTrue();
    assertThat(fixtures.get("games").get(0).get("round").stringValue())
        .isEqualTo("ה' - ליגות א' דרום");
  }

  /**
   * Away at Rahat: our side from the stored ownSide, the opponent is the home name, scores ours.
   */
  @Test
  void anAwayCupGameIsShownFromOurSide() throws Exception {
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(rahatCupGame()))));

    JsonNode game = fixturesOf(clubA).get("games").get(0);
    assertThat(game.get("ownSide").stringValue()).isEqualTo("AWAY");
    assertThat(game.get("opponentName").stringValue()).isEqualTo(RAHAT_FULL_NAME);
    assertThat(game.get("venue").stringValue()).isEqualTo("רהט הפועל רהט");
    assertThat(game.get("ownScore").intValue()).isEqualTo(4);
    assertThat(game.get("opponentScore").intValue()).isEqualTo(1);
    assertThat(game.get("result").stringValue()).isEqualTo("WIN");
    assertThat(game.get("competition").stringValue()).isEqualTo("STATE_CUP");
    assertThat(game.get("round").stringValue()).isEqualTo("ו");
    assertThat(new ArrayList<>(game.propertyNames()))
        .containsExactly(
            "date",
            "kickoffTime",
            "ownSide",
            "opponentName",
            "venue",
            "ownScore",
            "opponentScore",
            "result",
            "detailsId",
            "detailsState",
            "competition",
            "round",
            "extraTime",
            "penalties");
  }

  /**
   * A cup game decided on penalties: both scores shown, no result, and still played — its details
   * state is {@code PENDING}, then {@code AVAILABLE} once stored; one with extra time, given up,
   * {@code UNAVAILABLE}. A played league game keeps its result and details state (regression).
   */
  @Test
  void aCupGameWithExtraTimeOrPenaltiesHasNoResultButIsPlayed() throws Exception {
    ingest(request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), standings(FETCHED_AT)));
    Map<String, Object> penalties = penaltiesGame();
    Map<String, Object> extraTime = shimshonCupGame();
    extraTime.put("extraTime", "2-1");
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(extraTime, penalties))));

    JsonNode games = fixturesOf(clubA).get("games");
    JsonNode onPenalties = gameAgainst(games, RAHAT_FULL_NAME);
    assertThat(onPenalties.get("ownScore").intValue()).isEqualTo(1);
    assertThat(onPenalties.get("opponentScore").intValue()).isEqualTo(1);
    assertThat(onPenalties.get("result").isNull()).isTrue();
    assertThat(onPenalties.get("penalties").stringValue()).isEqualTo("4-5");
    assertThat(onPenalties.get("extraTime").isNull()).isTrue();
    assertThat(onPenalties.get("detailsState").stringValue()).isEqualTo("PENDING");
    JsonNode afterExtraTime = gameAgainst(games, SHIMSHON_FULL_NAME);
    assertThat(afterExtraTime.get("result").isNull()).isTrue();
    assertThat(afterExtraTime.get("extraTime").stringValue()).isEqualTo("2-1");
    assertThat(afterExtraTime.get("detailsState").stringValue()).isEqualTo("PENDING");
    JsonNode malachi = gameAgainst(games, KIRYAT_MALACHI_NAME);
    assertThat(malachi.get("result").stringValue()).isEqualTo("WIN");
    assertThat(malachi.get("detailsState").stringValue()).isEqualTo("PENDING");

    ingest(gameDetailsOnly(gameDetailsWith(FETCHED_AT, List.of(), cupDetails(PENALTIES_CUP_GAME))));
    for (int i = 1; i <= 3; i++) {
      ingest(
          gameDetailsOnly(
              gameDetailsWith(
                  FETCHED_AT.plusSeconds(i),
                  List.of(gameFailure(SHIMSHON_CUP_GAME, "PARSE_ERROR")))));
    }

    games = fixturesOf(clubA).get("games");
    onPenalties = gameAgainst(games, RAHAT_FULL_NAME);
    assertThat(onPenalties.get("detailsState").stringValue()).isEqualTo("AVAILABLE");
    assertThat(onPenalties.get("detailsId").isString()).isTrue();
    assertThat(gameAgainst(games, SHIMSHON_FULL_NAME).get("detailsState").stringValue())
        .isEqualTo("UNAVAILABLE");
    assertThat(gameAgainst(games, KIRYAT_MALACHI_NAME).get("detailsState").stringValue())
        .isEqualTo("PENDING");
  }

  @Test
  void onlyCupGamesAreOkWithoutATeamName() throws Exception {
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame()))));

    JsonNode fixtures = fixturesOf(clubA);
    assertThat(fixtures.get("state").stringValue()).isEqualTo("OK");
    assertThat(fixtures.get("teamName").isNull()).isTrue();
    assertThat(fixtures.get("lastUpdatedAt").stringValue()).isEqualTo(FETCHED_AT.toString());
    assertThat(fixtures.get("games")).hasSize(1);
  }

  /** Only league games: as before KAN-84, each with competition LEAGUE and no round. */
  @Test
  void onlyLeagueGamesAreShownAsBefore() throws Exception {
    ingest(
        request(
            SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT.plusSeconds(60)), standings(FETCHED_AT)));

    JsonNode fixtures = fixturesOf(clubA);
    assertThat(fixtures.get("state").stringValue()).isEqualTo("OK");
    assertThat(fixtures.get("teamName").stringValue()).isEqualTo(SDEROT_NAME);
    assertThat(fixtures.get("lastUpdatedAt").stringValue())
        .isEqualTo(FETCHED_AT.plusSeconds(60).toString());
    assertThat(fixtures.get("games")).hasSize(5);
    for (JsonNode game : fixtures.get("games")) {
      assertThat(game.get("competition").stringValue()).isEqualTo("LEAGUE");
      assertThat(game.get("round").isNull()).isTrue();
      assertThat(game.get("extraTime").isNull()).isTrue();
      assertThat(game.get("penalties").isNull()).isTrue();
    }

    // A cup snapshot fetched before the league: lastUpdatedAt is now the older, the cup's.
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame()))));
    assertThat(fixturesOf(clubA).get("lastUpdatedAt").stringValue())
        .isEqualTo(FETCHED_AT.toString());
  }

  @Test
  void aCupSnapshotOfAnEarlierSeasonIsNotShown() throws Exception {
    ingest(cupGamesOnly(cupGamesWith(FETCHED_AT, stateCup(shimshonCupGame()))));
    clubs.map(clubA, SDEROT, LEAGUE, SEASON + 1);

    JsonNode fixtures = fixturesOf(clubA);
    assertThat(fixtures.get("state").stringValue()).isEqualTo("NO_DATA");
    assertThat(fixtures.get("games")).isEmpty();
    assertThat(rawDocuments(CUPS)).hasSize(1);
  }

  /** No football.org.il id, league or cup, in the raw JSON. */
  @Test
  void theFixturesCarryNoFootballOrgIlId() throws Exception {
    ingest(leagueAndCup(FETCHED_AT, FETCHED_AT));
    ingest(gameDetailsOnly(gameDetailsWith(FETCHED_AT, List.of(), cupDetails(RAHAT_CUP_GAME))));

    String raw = rawFixtures(clubA);
    // Our own opaque ids are random hex: blank them so a digit run in one can't look like a leak.
    String body = raw.replaceAll("\"[0-9a-f-]{24,36}\"", "\"<id>\"");
    assertThat(body).contains("<id>", SHIMSHON_FULL_NAME);
    for (int id :
        List.of(
            SHIMSHON_CUP_GAME,
            RAHAT_CUP_GAME,
            TIRA_GAME,
            MALACHI_GAME,
            NIR_RAM_GAME,
            1143937,
            SDEROT,
            TIRA,
            LEAGUE)) {
      assertThat(body).as(body).doesNotContain(String.valueOf(id));
      assertThat(numbers(ScrapePayloads.JSON.readTree(body))).doesNotContain(id);
    }
    assertThat(numbers(ScrapePayloads.JSON.readTree(body))).doesNotContain(SEASON);
    assertThat(body).doesNotContain("gameId", "teamId", "leagueId", "seasonId");
  }

  // --- GET /matches/games/{id}
  // --------------------------------------------------------------------

  /**
   * A cup game's details: no result if its cup list shows penalties (scores unchanged); as before
   * without them; a league game's as before.
   */
  @Test
  void theGameDetailsHaveNoResultForACupGameDecidedOnPenalties() throws Exception {
    ingest(leagueAndCup(FETCHED_AT, FETCHED_AT));
    Map<String, Object> cup =
        cupGamesOnly(
            cupGamesWith(
                FETCHED_AT.plusSeconds(1),
                stateCup(shimshonCupGame(), rahatCupGame(), penaltiesGame())));
    ingest(cup);
    ingest(
        gameDetailsOnly(
            gameDetailsWith(
                FETCHED_AT,
                List.of(),
                cupDetails(PENALTIES_CUP_GAME),
                cupDetails(RAHAT_CUP_GAME),
                tiraGame())));

    JsonNode games = fixturesOf(clubA).get("games");
    JsonNode penalties = details(gameAgainst(games, RAHAT_FULL_NAME, PENALTIES_CUP_DATE));
    assertThat(penalties.get("result").isNull()).isTrue();
    assertThat(penalties.get("ownScore").intValue()).isEqualTo(1);
    assertThat(penalties.get("opponentScore").intValue()).isEqualTo(1);

    JsonNode plain = details(gameAgainst(games, RAHAT_FULL_NAME, "2026-09-26"));
    assertThat(plain.get("result").stringValue()).isEqualTo("WIN");

    JsonNode league = details(gameAgainst(games, TIRA_NAME, "2026-09-05"));
    assertThat(league.get("result").stringValue()).isEqualTo("LOSS");
  }

  // --- known games
  // --------------------------------------------------------------------------------

  /**
   * {@code knownGameIds} comes from the stored details and the given-up attempts, not from the
   * fixtures: a cup game's id joins it with no change to {@code KnownGames} (no league fixture has
   * these ids).
   */
  @Test
  void storedAndGivenUpCupGamesAreKnownGames() throws Exception {
    ingest(gameDetailsOnly(gameDetailsWith(FETCHED_AT, List.of(), cupDetails(RAHAT_CUP_GAME))));
    for (int i = 1; i <= 3; i++) {
      ingest(
          gameDetailsOnly(
              gameDetailsWith(
                  FETCHED_AT.plusSeconds(i),
                  List.of(gameFailure(SHIMSHON_CUP_GAME, "PARSE_ERROR")))));
    }

    clubContext.setClubId(clubA);
    assertThat(knownGames.knownGameIds(SDEROT, SEASON))
        .containsExactly(SHIMSHON_CUP_GAME, RAHAT_CUP_GAME);
  }

  // --- helpers
  // ------------------------------------------------------------------------------------

  private static final String PENALTIES_CUP_DATE = "2026-10-10";

  /** At Rahat, 1-1, decided on penalties. */
  private static Map<String, Object> penaltiesGame() {
    return cupGame(
        PENALTIES_CUP_GAME,
        "ז",
        PENALTIES_CUP_DATE,
        "17:00",
        RAHAT_FULL_NAME,
        SDEROT_FULL_NAME,
        "AWAY",
        null,
        1,
        1,
        null,
        "4-5");
  }

  /**
   * The details of a cup game we played away, as the game page shows it: the scores of the game
   * with that id in this test's data.
   */
  private static Map<String, Object> cupDetails(int gameId) {
    Map<String, Object> game = new LinkedHashMap<>(tiraGame());
    game.put("gameId", gameId);
    game.put("competition", "גביע המדינה ווינר");
    game.put("homeTeamName", RAHAT_FULL_NAME);
    game.put("awayTeamName", SDEROT_NAME);
    game.put("homeScore", 1);
    game.put("awayScore", gameId == PENALTIES_CUP_GAME ? 1 : 4);
    return game;
  }

  /** League fixtures and standings for Sderot, and the State Cup with two games. */
  private static Map<String, Object> leagueAndCup(Instant leagueAt, Instant cupAt) {
    Map<String, Object> body =
        request(SDEROT, LEAGUE, SEASON, fixtures(leagueAt), standings(leagueAt));
    body.put("cupGames", cupGamesWith(cupAt, stateCup(shimshonCupGame(), rahatCupGame())));
    return body;
  }

  private static Map<String, Object> gameDetailsOnly(Map<String, Object> part) {
    return request(SDEROT, LEAGUE, SEASON, null, null, null, part);
  }

  /** Without a runId: these tests don't run with Redis, so no run status is recorded. */
  private ResultActions ingest(Map<String, Object> body) throws Exception {
    body.put("runId", null);
    return mockMvc.perform(
        post(INGEST)
            .header("Authorization", "Bearer " + SERVICE_TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
  }

  private String rawFixtures(String clubId) throws Exception {
    return mockMvc
        .perform(
            get("/matches/fixtures")
                .header("Authorization", tokens.bearer(clubId, PermissionLevel.VIEW_ONLY)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private JsonNode fixturesOf(String clubId) throws Exception {
    return ScrapePayloads.JSON.readTree(rawFixtures(clubId));
  }

  private JsonNode details(JsonNode fixture) throws Exception {
    assertThat(fixture.get("detailsState").stringValue()).isEqualTo("AVAILABLE");
    return ScrapePayloads.JSON.readTree(
        mockMvc
            .perform(
                get("/matches/games/" + fixture.get("detailsId").stringValue())
                    .header("Authorization", tokens.bearer(clubA, PermissionLevel.VIEW_ONLY)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static JsonNode gameAgainst(JsonNode games, String opponent) {
    for (JsonNode game : games) {
      if (game.get("opponentName").stringValue().equals(opponent)) {
        return game;
      }
    }
    throw new AssertionError("No game against " + opponent);
  }

  private static JsonNode gameAgainst(JsonNode games, String opponent, String date) {
    for (JsonNode game : games) {
      if (game.get("opponentName").stringValue().equals(opponent)
          && game.get("date").stringValue().equals(date)) {
        return game;
      }
    }
    throw new AssertionError("No game against " + opponent + " on " + date);
  }

  private List<Document> rawDocuments(String collection) {
    return mongoTemplate.getCollection(collection).find().into(new ArrayList<>());
  }

  /** The club's State Cup games, as stored. */
  private List<Document> storedGames(String clubId) {
    Document snapshot =
        mongoTemplate
            .getCollection(CUPS)
            .find(new Document("clubId", clubId).append("competition", "STATE_CUP"))
            .first();
    assertThat(snapshot).as("the State Cup snapshot of " + clubId).isNotNull();
    return snapshot.getList("games", Document.class);
  }

  private static List<Integer> numbers(JsonNode node) {
    List<Integer> numbers = new ArrayList<>();
    if (node.isObject()) {
      for (String name : node.propertyNames()) {
        numbers.addAll(numbers(node.get(name)));
      }
    } else if (node.isArray()) {
      node.forEach(child -> numbers.addAll(numbers(child)));
    } else if (node.isNumber()) {
      numbers.add(node.intValue());
    }
    return numbers;
  }
}
