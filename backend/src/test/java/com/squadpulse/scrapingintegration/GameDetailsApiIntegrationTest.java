package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapePayloads.BENCH_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.FETCHED_AT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.GK_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.GK_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.KIRYAT_MALACHI_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEAGUE;
import static com.squadpulse.scrapingintegration.ScrapePayloads.MALACHI_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.NIR_RAM_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SCORER_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SCORER_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SEASON;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SUB_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA_GAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.fixtures;
import static com.squadpulse.scrapingintegration.ScrapePayloads.gameDetailsWith;
import static com.squadpulse.scrapingintegration.ScrapePayloads.gameFailure;
import static com.squadpulse.scrapingintegration.ScrapePayloads.json;
import static com.squadpulse.scrapingintegration.ScrapePayloads.malachiGame;
import static com.squadpulse.scrapingintegration.ScrapePayloads.playerStatsWith;
import static com.squadpulse.scrapingintegration.ScrapePayloads.request;
import static com.squadpulse.scrapingintegration.ScrapePayloads.standings;
import static com.squadpulse.scrapingintegration.ScrapePayloads.statsRow;
import static com.squadpulse.scrapingintegration.ScrapePayloads.tiraGame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.auth.TestClubMappings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Game details as the club sees them (KAN-79), end to end on a real MongoDB behind the real
 * security chains: who may read them, the details state on {@code GET /matches/fixtures}, the
 * details response (order, events, links, a masked name, a dangling link), club and mapping
 * isolation, and that no football.org.il id reaches either response.
 *
 * <p>Clubs A and C are mapped to the same team (Sderot), B to another (Tira). Sderot's stored game
 * is the 1-4 loss at Tira; its 1-1 at Nir Ram is given up; its 2-1 over Kiryat Malachi is pending.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + GameDetailsApiIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import({TestAccessTokens.class, TestClubMappings.class})
class GameDetailsApiIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  private static final String GAMES = "/matches/games/";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int TIRA_OWN_GAME = 777001;

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private TestAccessTokens tokens;
  @Autowired private TestClubMappings clubs;

  private String clubA;
  private String clubB;
  private String clubC;

  @BeforeEach
  void setUp() throws Exception {
    clubA = clubs.createClub("Sderot", SDEROT, LEAGUE, SEASON);
    clubB = clubs.createClub("Tira", TIRA, LEAGUE, SEASON);
    clubC = clubs.createClub("Sderot fans", SDEROT, LEAGUE, SEASON);
    ingest(SDEROT, fixtures(FETCHED_AT), standings(FETCHED_AT), null, null);
    ingest(SDEROT, null, null, null, gameDetailsWith(FETCHED_AT, List.of(), tiraGame()));
    for (int i = 1; i <= 3; i++) {
      ingest(
          SDEROT,
          null,
          null,
          null,
          gameDetailsWith(
              FETCHED_AT.plusSeconds(i), List.of(gameFailure(NIR_RAM_GAME, "PARSE_ERROR"))));
    }
    Map<String, Object> tirasOwn = new LinkedHashMap<>(tiraGame());
    tirasOwn.put("gameId", TIRA_OWN_GAME);
    ingest(TIRA, null, null, null, gameDetailsWith(FETCHED_AT, List.of(), tirasOwn));
  }

  @AfterEach
  void tearDown() {
    for (String collection :
        List.of(
            "league_fixtures",
            "league_standings",
            "player_season_stats",
            "player_stats_links",
            "players",
            "game_details",
            "game_detail_attempts")) {
      mongoTemplate.getCollection(collection).deleteMany(new Document());
    }
    clubs.deleteAllClubs();
  }

  // --- who may call it --------------------------------------------------------------------------

  @Test
  void readingNeedsViewOnlyAndNeitherAnAnonymousCallerNorTheScrapersTokenGetsIn() throws Exception {
    String id = detailsId(clubA, TIRA_NAME);
    for (PermissionLevel level : PermissionLevel.values()) {
      mockMvc
          .perform(get(GAMES + id).header("Authorization", tokens.bearer(clubA, level)))
          .andExpect(status().isOk());
    }
    mockMvc.perform(get(GAMES + id)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(GAMES + id).header("Authorization", "Bearer " + SERVICE_TOKEN))
        .andExpect(status().isUnauthorized());
  }

  // --- the fixtures -----------------------------------------------------------------------------

  @Test
  void eachFixtureSaysWhetherItsDetailsAreThere() throws Exception {
    JsonNode games = JSON.readTree(fixturesBody(clubA)).get("games");

    JsonNode tira = gameAgainst(games, TIRA_NAME, "AWAY");
    assertThat(tira.get("detailsState").stringValue()).isEqualTo("AVAILABLE");
    assertThat(tira.get("detailsId").stringValue()).isEqualTo(rawDetailsId(clubA, TIRA_GAME));

    JsonNode nirRam = gameAgainst(games, ScrapePayloads.NIR_RAM_NAME, "AWAY");
    assertThat(nirRam.get("detailsState").stringValue()).isEqualTo("UNAVAILABLE");
    assertThat(nirRam.get("detailsId").isNull()).isTrue();

    JsonNode malachi = gameAgainst(games, KIRYAT_MALACHI_NAME, "HOME");
    assertThat(malachi.get("detailsState").stringValue()).isEqualTo("PENDING");
    assertThat(malachi.get("detailsId").isNull()).isTrue();

    // Not played yet: one with a game id, one unscheduled (no id).
    for (JsonNode unplayed :
        List.of(
            gameAgainst(games, TIRA_NAME, "HOME"),
            gameAgainst(games, ScrapePayloads.HADERA_NAME, "HOME"))) {
      assertThat(unplayed.get("detailsState").isNull()).isTrue();
      assertThat(unplayed.get("detailsId").isNull()).isTrue();
    }
  }

  // --- the details ------------------------------------------------------------------------------

  @Test
  void theDetailsShowOurSideOrderedByRoleWithLinksAndEventsByMinute() throws Exception {
    ingest(
        SDEROT,
        null,
        null,
        playerStatsWith(
            FETCHED_AT, statsRow(SCORER_ID, "אזולאי נועם"), statsRow(GK_ID, "גבאי עומר")),
        null);
    String scorer = createPlayer(clubA, "נועם אזולאי");
    String goalkeeper = createPlayer(clubA, "עומר גבאי");
    link(clubA, statsRowId(clubA, "אזולאי נועם"), scorer);
    link(clubA, statsRowId(clubA, "גבאי עומר"), goalkeeper);
    // The goalkeeper's player is deleted behind the guard's back: a dangling link counts as none.
    mongoTemplate.getCollection("players").deleteOne(new Document("_id", new ObjectId(goalkeeper)));

    String id = detailsId(clubA, TIRA_NAME);
    JsonNode details = JSON.readTree(detailsBody(clubA, id).getResponse().getContentAsString());

    assertThat(details.get("id").stringValue()).isEqualTo(id);
    assertThat(details.get("date").stringValue()).isEqualTo("2026-09-05");
    assertThat(details.get("kickoffTime").stringValue()).isEqualTo("20:00");
    assertThat(details.get("competition").stringValue()).isEqualTo("ליגה א' דרום");
    assertThat(details.get("round").intValue()).isEqualTo(1);
    assertThat(details.get("ownSide").stringValue()).isEqualTo("AWAY");
    assertThat(details.get("opponentName").stringValue()).isEqualTo(TIRA_NAME);
    assertThat(details.get("ownScore").intValue()).isEqualTo(1);
    assertThat(details.get("opponentScore").intValue()).isEqualTo(4);
    assertThat(details.get("result").stringValue()).isEqualTo("LOSS");
    assertThat(details.get("lastUpdatedAt").stringValue()).isEqualTo(FETCHED_AT.toString());
    assertThat(new ArrayList<>(details.propertyNames()))
        .containsExactly(
            "id",
            "date",
            "kickoffTime",
            "competition",
            "round",
            "ownSide",
            "opponentName",
            "ownScore",
            "opponentScore",
            "result",
            "extraTime",
            "penalties",
            "decidedBy",
            "competitionType",
            "cupRound",
            "lastUpdatedAt",
            "players");
    // KAN-86: a league game is in no cup snapshot; both are written as JSON null.
    assertThat(details.get("competitionType").isNull()).isTrue();
    assertThat(details.get("cupRound").isNull()).isTrue();

    JsonNode players = details.get("players");
    assertThat(players).hasSize(12);
    assertThat(players.findValuesAsString("role"))
        .containsExactly(
            "STARTER",
            "STARTER",
            "STARTER",
            "STARTER",
            "STARTER",
            "STARTER",
            "STARTER",
            "STARTER",
            "STARTER",
            "STARTER",
            "SUBSTITUTE",
            "UNUSED");
    JsonNode gk = players.get(0);
    assertThat(new ArrayList<>(gk.propertyNames()))
        .containsExactly(
            "name", "linkedPlayer", "shirtNumber", "role", "goalkeeper", "captain", "events");
    assertThat(gk.get("name").stringValue()).isEqualTo(GK_NAME);
    assertThat(gk.get("linkedPlayer").isNull()).isTrue();
    assertThat(gk.get("goalkeeper").booleanValue()).isTrue();
    assertThat(gk.get("captain").booleanValue()).isTrue();
    JsonNode scorerRow = players.get(1);
    assertThat(scorerRow.get("name").stringValue()).isEqualTo(SCORER_NAME);
    assertThat(scorerRow.get("linkedPlayer").get("id").stringValue()).isEqualTo(scorer);
    assertThat(scorerRow.get("linkedPlayer").get("fullName").stringValue())
        .isEqualTo("נועם אזולאי");
    assertThat(scorerRow.get("linkedPlayer").get("active").booleanValue()).isTrue();
    assertThat(scorerRow.get("shirtNumber").intValue()).isEqualTo(9);
    assertThat(events(scorerRow)).containsExactly("GOAL 38", "RED 40");
    assertThat(players.get(2).get("name").isNull()).isTrue();
    assertThat(players.get(10).get("name").stringValue()).isEqualTo(SUB_NAME);
    assertThat(events(players.get(10))).containsExactly("SUB_ON 57", "SUB_OFF 93", "YELLOW null");
    assertThat(players.get(11).get("name").stringValue()).isEqualTo(BENCH_NAME);
    assertThat(events(players.get(11))).containsExactly("YELLOW 88");
  }

  @Test
  void aHomeGameIsShownFromOurSideToo() throws Exception {
    // Sent with the substitute first: the response lists the starter first.
    Map<String, Object> malachi = malachiGame();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> players = (List<Map<String, Object>>) malachi.get("players");
    players.add(players.removeFirst());
    ingest(
        SDEROT, null, null, null, gameDetailsWith(FETCHED_AT.plusSeconds(10), List.of(), malachi));

    JsonNode details =
        JSON.readTree(
            detailsBody(clubA, detailsId(clubA, KIRYAT_MALACHI_NAME))
                .getResponse()
                .getContentAsString());

    assertThat(details.get("ownSide").stringValue()).isEqualTo("HOME");
    assertThat(details.get("opponentName").stringValue()).isEqualTo(KIRYAT_MALACHI_NAME);
    assertThat(details.get("ownScore").intValue()).isEqualTo(2);
    assertThat(details.get("opponentScore").intValue()).isEqualTo(1);
    assertThat(details.get("result").stringValue()).isEqualTo("WIN");
    assertThat(details.get("players").findValuesAsString("role"))
        .containsExactly("STARTER", "SUBSTITUTE");
    assertThat(events(details.get("players").get(0))).containsExactly("GOAL 91", "GOAL 97");
  }

  // --- isolation --------------------------------------------------------------------------------

  @Test
  void aClubSeesOnlyItsOwnDetailsOfItsCurrentMapping() throws Exception {
    String ownId = rawDetailsId(clubA, TIRA_GAME);
    String sameTeamsId = rawDetailsId(clubC, TIRA_GAME);
    String otherTeamsId = rawDetailsId(clubB, TIRA_OWN_GAME);
    assertThat(sameTeamsId).isNotNull().isNotEqualTo(ownId);

    detailsBody(clubA, ownId);
    for (String foreign : List.of(sameTeamsId, otherTeamsId, "no-such-id")) {
      mockMvc
          .perform(
              get(GAMES + foreign)
                  .header("Authorization", tokens.bearer(clubA, PermissionLevel.ADMIN)))
          .andExpect(status().isNotFound());
    }
    detailsBody(clubC, sameTeamsId);
    detailsBody(clubB, otherTeamsId);

    // The mapping moves on: last season's record is no longer shown; back again, it is.
    clubs.map(clubA, SDEROT, LEAGUE, SEASON + 1);
    mockMvc
        .perform(
            get(GAMES + ownId).header("Authorization", tokens.bearer(clubA, PermissionLevel.ADMIN)))
        .andExpect(status().isNotFound());
    clubs.map(clubA, SDEROT, LEAGUE, SEASON);
    detailsBody(clubA, ownId);

    String unmapped = clubs.createClub("Unmapped");
    mockMvc
        .perform(
            get(GAMES + ownId)
                .header("Authorization", tokens.bearer(unmapped, PermissionLevel.ADMIN)))
        .andExpect(status().isNotFound());
  }

  @Test
  void neitherReadCarriesAFootballOrgIlId() throws Exception {
    ingest(
        SDEROT, null, null, playerStatsWith(FETCHED_AT, statsRow(SCORER_ID, "אזולאי נועם")), null);
    link(clubA, statsRowId(clubA, "אזולאי נועם"), createPlayer(clubA, "נועם אזולאי"));
    String fixtures = fixturesBody(clubA);
    String details =
        detailsBody(clubA, detailsId(clubA, TIRA_NAME)).getResponse().getContentAsString();

    for (String raw : List.of(fixtures, details)) {
      // Our own opaque ids (UUIDs, ObjectIds) are random hex: blank them so a digit run in one
      // can't look like a leak.
      String body = raw.replaceAll("\"[0-9a-f-]{24,36}\"", "\"<id>\"");
      assertThat(body).contains("<id>");
      for (int id :
          List.of(
              TIRA_GAME,
              MALACHI_GAME,
              NIR_RAM_GAME,
              1143937,
              SDEROT,
              TIRA,
              LEAGUE,
              GK_ID,
              SCORER_ID,
              920003,
              920004,
              920005)) {
        assertThat(body).as(body).doesNotContain(String.valueOf(id));
        assertThat(numbers(JSON.readTree(body))).doesNotContain(id);
      }
      // The season id (28) is too short to look for as text: no number in the response is it.
      assertThat(numbers(JSON.readTree(body))).doesNotContain(SEASON);
      assertThat(body).doesNotContain("gameId", "playerId", "teamId", "leagueId", "seasonId");
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  private void ingest(
      int teamId,
      Map<String, Object> fixtures,
      Map<String, Object> standings,
      Map<String, Object> playerStats,
      Map<String, Object> gameDetails)
      throws Exception {
    mockMvc
        .perform(
            post("/internal/scrape-results")
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        request(
                            teamId,
                            LEAGUE,
                            SEASON,
                            fixtures,
                            standings,
                            playerStats,
                            gameDetails))))
        .andExpect(status().isOk());
  }

  private String fixturesBody(String clubId) throws Exception {
    return mockMvc
        .perform(
            get("/matches/fixtures")
                .header("Authorization", tokens.bearer(clubId, PermissionLevel.VIEW_ONLY)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private MvcResult detailsBody(String clubId, String id) throws Exception {
    return details(clubId, id).andExpect(status().isOk()).andReturn();
  }

  private ResultActions details(String clubId, String id) throws Exception {
    return mockMvc.perform(
        get(GAMES + id).header("Authorization", tokens.bearer(clubId, PermissionLevel.VIEW_ONLY)));
  }

  /** The {@code detailsId} of the fixture against {@code opponent} that has one. */
  private String detailsId(String clubId, String opponent) throws Exception {
    for (JsonNode game : JSON.readTree(fixturesBody(clubId)).get("games")) {
      if (game.get("opponentName").stringValue().equals(opponent)
          && !game.get("detailsId").isNull()) {
        return game.get("detailsId").stringValue();
      }
    }
    throw new AssertionError("No details against " + opponent);
  }

  private static JsonNode gameAgainst(JsonNode games, String opponent, String ownSide) {
    for (JsonNode game : games) {
      if (game.get("opponentName").stringValue().equals(opponent)
          && game.get("ownSide").stringValue().equals(ownSide)) {
        return game;
      }
    }
    throw new AssertionError("No game against " + opponent + " " + ownSide);
  }

  private String rawDetailsId(String clubId, int gameId) {
    Document found =
        mongoTemplate
            .getCollection("game_details")
            .find(new Document("clubId", clubId).append("gameId", gameId))
            .first();
    return found == null ? null : found.getString("_id");
  }

  private String createPlayer(String clubId, String fullName) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fullName", fullName);
    body.put("primaryPosition", "ST");
    body.put("dateOfBirth", "1995-05-20");
    String response =
        mockMvc
            .perform(
                post("/squad/players")
                    .header("Authorization", tokens.bearer(clubId, PermissionLevel.EDIT_FULL))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(response).get("id").stringValue();
  }

  private String statsRowId(String clubId, String name) throws Exception {
    String body =
        mockMvc
            .perform(
                get("/matches/player-stats")
                    .header("Authorization", tokens.bearer(clubId, PermissionLevel.VIEW_ONLY)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (JsonNode row : JSON.readTree(body).get("players")) {
      if (row.get("name").stringValue().equals(name)) {
        return row.get("id").stringValue();
      }
    }
    throw new AssertionError("No stats row " + name);
  }

  private void link(String clubId, String rowId, String playerId) throws Exception {
    mockMvc
        .perform(
            put("/matches/player-stats/" + rowId + "/link")
                .header("Authorization", tokens.bearer(clubId, PermissionLevel.EDIT_FULL))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("playerId", playerId))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkedPlayer.id").value(playerId));
  }

  private static List<String> events(JsonNode appearance) {
    List<String> events = new ArrayList<>();
    for (JsonNode event : appearance.get("events")) {
      events.add(
          event.get("kind").stringValue()
              + " "
              + (event.get("minute").isNull() ? "null" : event.get("minute").intValue()));
    }
    return events;
  }

  /** Every number in the tree. */
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
