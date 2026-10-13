package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapePayloads.COHEN_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.COHEN_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.FETCHED_AT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEAGUE;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEVI_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEVI_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.MIZRAHI_ID;
import static com.squadpulse.scrapingintegration.ScrapePayloads.MIZRAHI_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SEASON;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA;
import static com.squadpulse.scrapingintegration.ScrapePayloads.json;
import static com.squadpulse.scrapingintegration.ScrapePayloads.playerStats;
import static com.squadpulse.scrapingintegration.ScrapePayloads.playerStatsWith;
import static com.squadpulse.scrapingintegration.ScrapePayloads.request;
import static com.squadpulse.scrapingintegration.ScrapePayloads.statsRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.TestImages;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scraped player statistics as the club sees them, and their links to its own players (KAN-72), end
 * to end on a real MongoDB behind the real security chains: who may call what, the read's states,
 * links (set, replace, unlink from either side, the unique rules under a race), club isolation,
 * links outliving a player's absence from a scrape, a dangling link, suggestions, and the refused
 * delete of a linked player.
 *
 * <p>Clubs A and C are mapped to the same team (Sderot), B to another (Tira): links and opaque ids
 * are per club.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + PlayerStatsApiIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import({TestAccessTokens.class, TestClubMappings.class})
class PlayerStatsApiIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  private static final String STATS = "/matches/player-stats";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int TIRA_PLAYER_ID = 910001;

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
    ingestStats(SDEROT, playerStats(FETCHED_AT));
    ingestStats(TIRA, playerStatsWith(FETCHED_AT, statsRow(TIRA_PLAYER_ID, "חדד רון")));
  }

  @AfterEach
  void tearDown() {
    for (String collection :
        List.of("player_season_stats", "player_stats_links", "players", "images.files")) {
      mongoTemplate.getCollection(collection).deleteMany(new Document());
    }
    mongoTemplate.getCollection("images.chunks").deleteMany(new Document());
    clubs.deleteAllClubs();
  }

  // --- who may call it --------------------------------------------------------------------------

  @Test
  void readingNeedsViewOnlyAndEveryLinkWriteEditFull() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    String player = createPlayer(clubA, "דניאל כהן");

    for (PermissionLevel level : PermissionLevel.values()) {
      mockMvc
          .perform(get(STATS).header("Authorization", tokens.bearer(clubA, level)))
          .andExpect(status().isOk());
    }
    for (PermissionLevel level : List.of(PermissionLevel.VIEW_ONLY, PermissionLevel.EDIT_PARTIAL)) {
      link(clubA, row, player, level).andExpect(status().isForbidden());
      unlink(clubA, row, level).andExpect(status().isForbidden());
      unlinkPlayer(clubA, player, level).andExpect(status().isForbidden());
    }
    assertThat(rawLinks()).isEmpty();

    for (PermissionLevel level : List.of(PermissionLevel.EDIT_FULL, PermissionLevel.ADMIN)) {
      link(clubA, row, player, level).andExpect(status().isOk());
      unlink(clubA, row, level).andExpect(status().isNoContent());
      unlinkPlayer(clubA, player, level).andExpect(status().isNoContent());
    }
  }

  @Test
  void neitherAnAnonymousCallerNorTheScrapersTokenGetsIn() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    String player = createPlayer(clubA, "דניאל כהן");
    String linkBody = json(Map.of("playerId", player));
    List<MockHttpServletRequestBuilder> requests =
        List.of(
            get(STATS),
            put(STATS + "/" + row + "/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkBody),
            delete(STATS + "/" + row + "/link"),
            delete("/squad/players/" + player + "/stats-link"));
    for (MockHttpServletRequestBuilder request : requests) {
      mockMvc.perform(request).andExpect(status().isUnauthorized());
    }
    for (MockHttpServletRequestBuilder request :
        List.of(
            get(STATS),
            put(STATS + "/" + row + "/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkBody),
            delete(STATS + "/" + row + "/link"),
            delete("/squad/players/" + player + "/stats-link"))) {
      mockMvc
          .perform(request.header("Authorization", "Bearer " + SERVICE_TOKEN))
          .andExpect(status().isUnauthorized());
    }
    assertThat(rawLinks()).isEmpty();
  }

  // --- the read ---------------------------------------------------------------------------------

  @Test
  void anUnmappedClubGetsNotMappedAndOneWithoutDataNoData() throws Exception {
    String unmapped = clubs.createClub("Unmapped");
    String noData = clubs.createClub("Hadera", ScrapePayloads.HADERA, LEAGUE, SEASON);

    for (String[] expected : new String[][] {{unmapped, "NOT_MAPPED"}, {noData, "NO_DATA"}}) {
      JsonNode node = read(expected[0]);
      assertThat(node.get("state").stringValue()).isEqualTo(expected[1]);
      assertThat(node.get("lastUpdatedAt").isNull()).isTrue();
      assertThat(node.get("players").isEmpty()).isTrue();
    }
  }

  @Test
  void theClubsStatsAreShownInPageOrderWithWhenTheyWereScraped() throws Exception {
    JsonNode node = read(clubA);

    assertThat(node.get("state").stringValue()).isEqualTo("OK");
    assertThat(Instant.parse(node.get("lastUpdatedAt").stringValue())).isEqualTo(FETCHED_AT);
    assertThat(names(node)).containsExactly(COHEN_NAME, LEVI_NAME, MIZRAHI_NAME);
    JsonNode cohen = node.get("players").get(0);
    assertThat(fieldNames(cohen))
        .containsExactly(
            "id",
            "name",
            "games",
            "goals",
            "yellowCardsLeagueAndCup",
            "yellowCardsToto",
            "redCards",
            "starts",
            "cameOnAsSub",
            "subbedOff",
            "minutes",
            "linkedPlayer",
            "suggestedPlayer");
    assertThat(cohen.get("games").intValue()).isEqualTo(10);
    assertThat(cohen.get("minutes").intValue()).isEqualTo(712);
    assertThat(cohen.get("linkedPlayer").isNull()).isTrue();
    assertThat(cohen.get("suggestedPlayer").isNull()).isTrue();
  }

  /** Not a football.org.il id of any kind: no player_id, team, league or season, anywhere. */
  @Test
  void noResponseCarriesAnyFootballOrgIlId() throws Exception {
    String player = createPlayer(clubA, "דניאל כהן");
    String linked =
        link(clubA, rowId(clubA, COHEN_NAME), player, PermissionLevel.EDIT_FULL)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    for (String body : List.of(readBody(clubA), linked)) {
      for (int sourceId : List.of(COHEN_ID, LEVI_ID, MIZRAHI_ID)) {
        assertThat(body).doesNotContain(String.valueOf(sourceId));
      }
      JsonNode node = JSON.readTree(body);
      assertThat(allValues(node))
          .doesNotContain(
              String.valueOf(SDEROT),
              String.valueOf(LEAGUE),
              String.valueOf(SEASON),
              String.valueOf(COHEN_ID));
      assertThat(allFieldNames(node))
          .doesNotContain("playerId", "sourcePlayerId", "teamId", "leagueId", "seasonId");
    }
  }

  // --- links ------------------------------------------------------------------------------------

  @Test
  void aLinkIsShownAndLinkingTheSamePairAgainChangesNothing() throws Exception {
    String player = createPlayer(clubA, "דניאל כהן");
    String row = rowId(clubA, COHEN_NAME);

    link(clubA, row, player, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(row))
        .andExpect(jsonPath("$.name").value(COHEN_NAME))
        .andExpect(jsonPath("$.linkedPlayer.id").value(player))
        .andExpect(jsonPath("$.linkedPlayer.fullName").value("דניאל כהן"))
        .andExpect(jsonPath("$.linkedPlayer.active").value(true))
        .andExpect(jsonPath("$.suggestedPlayer").isEmpty());
    List<Document> links = rawLinks();
    assertThat(links).hasSize(1);
    assertThat(links.getFirst().getInteger("sourcePlayerId")).isEqualTo(COHEN_ID);
    assertThat(links.getFirst().getString("playerId")).isEqualTo(player);
    assertThat(links.getFirst().getString("clubId")).isEqualTo(clubA);

    link(clubA, row, player, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkedPlayer.id").value(player));
    assertThat(rawLinks()).isEqualTo(links);

    JsonNode cohen = rowNamed(read(clubA), COHEN_NAME);
    assertThat(cohen.get("linkedPlayer").get("id").stringValue()).isEqualTo(player);
  }

  @Test
  void aPlayerLinkedToAnotherScrapedPlayerIsA409() throws Exception {
    String player = createPlayer(clubA, "דניאל כהן");
    link(clubA, rowId(clubA, COHEN_NAME), player, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk());

    link(clubA, rowId(clubA, LEVI_NAME), player, PermissionLevel.EDIT_FULL)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PLAYER_ALREADY_LINKED"))
        .andExpect(
            jsonPath("$.message").value("This player is already linked to another scraped player"));

    assertThat(rawLinks()).hasSize(1);
  }

  @Test
  void relinkingAScrapedPlayerFreesItsOldPlayer() throws Exception {
    String first = createPlayer(clubA, "דניאל כהן");
    String second = createPlayer(clubA, "דני כהן");
    String cohen = rowId(clubA, COHEN_NAME);
    link(clubA, cohen, first, PermissionLevel.EDIT_FULL).andExpect(status().isOk());

    link(clubA, cohen, second, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkedPlayer.id").value(second));

    assertThat(rawLinks()).hasSize(1);
    link(clubA, rowId(clubA, LEVI_NAME), first, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk());
    assertThat(rawLinks()).hasSize(2);
  }

  @Test
  void unlinkingIsIdempotentFromEitherSide() throws Exception {
    String player = createPlayer(clubA, "דניאל כהן");
    String row = rowId(clubA, COHEN_NAME);

    unlink(clubA, row, PermissionLevel.EDIT_FULL).andExpect(status().isNoContent());
    link(clubA, row, player, PermissionLevel.EDIT_FULL).andExpect(status().isOk());
    unlink(clubA, row, PermissionLevel.EDIT_FULL).andExpect(status().isNoContent());
    assertThat(rawLinks()).isEmpty();

    link(clubA, row, player, PermissionLevel.EDIT_FULL).andExpect(status().isOk());
    unlinkPlayer(clubA, player, PermissionLevel.EDIT_FULL).andExpect(status().isNoContent());
    unlinkPlayer(clubA, player, PermissionLevel.EDIT_FULL).andExpect(status().isNoContent());
    assertThat(rawLinks()).isEmpty();
  }

  @Test
  void unknownIdsAre404s() throws Exception {
    String player = createPlayer(clubA, "דניאל כהן");
    String row = rowId(clubA, COHEN_NAME);
    String noSuchPlayer = new ObjectId().toHexString();

    link(clubA, "no-such-row", player, PermissionLevel.EDIT_FULL)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player statistics not found"));
    link(clubA, row, noSuchPlayer, PermissionLevel.EDIT_FULL)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
    unlink(clubA, "no-such-row", PermissionLevel.EDIT_FULL).andExpect(status().isNotFound());
    unlinkPlayer(clubA, noSuchPlayer, PermissionLevel.EDIT_FULL).andExpect(status().isNotFound());

    // No data, or no mapping at all: no row can be found.
    String unmapped = clubs.createClub("Unmapped");
    String unmappedPlayer = createPlayer(unmapped, "דניאל כהן");
    link(unmapped, row, unmappedPlayer, PermissionLevel.EDIT_FULL).andExpect(status().isNotFound());
    assertThat(rawLinks()).isEmpty();
  }

  @Test
  void aBlankOrMissingPlayerIdIsA400() throws Exception {
    String row = rowId(clubA, COHEN_NAME);
    for (String body : List.of("{}", "{\"playerId\":\" \"}")) {
      mockMvc
          .perform(
              put(STATS + "/" + row + "/link")
                  .header("Authorization", tokens.bearer(clubA, PermissionLevel.EDIT_FULL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.details[0]").value("playerId: must not be blank"));
    }
  }

  @Test
  void aReleasedPlayerMayBeLinked() throws Exception {
    String player = createPlayer(clubA, "דניאל כהן");
    release(clubA, player);

    link(clubA, rowId(clubA, COHEN_NAME), player, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkedPlayer.active").value(false));
  }

  // --- isolation --------------------------------------------------------------------------------

  @Test
  void aClubCantLinkToAnotherClubsPlayerNorSeeOrRemoveItsLinks() throws Exception {
    String playerB = createPlayer(clubB, "רון חדד");
    String playerC = createPlayer(clubC, "דניאל כהן");
    String rowB = rowId(clubB, "חדד רון");
    String rowC = rowId(clubC, COHEN_NAME);
    link(clubB, rowB, playerB, PermissionLevel.EDIT_FULL).andExpect(status().isOk());
    link(clubC, rowC, playerC, PermissionLevel.EDIT_FULL).andExpect(status().isOk());

    // A's own row, B's player: not found, never forbidden.
    link(clubA, rowId(clubA, COHEN_NAME), playerB, PermissionLevel.EDIT_FULL)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
    // B's and C's rows aren't in A's snapshot.
    String playerA = createPlayer(clubA, "דניאל כהן");
    link(clubA, rowB, playerA, PermissionLevel.EDIT_FULL).andExpect(status().isNotFound());
    link(clubA, rowC, playerA, PermissionLevel.EDIT_FULL).andExpect(status().isNotFound());
    unlink(clubA, rowB, PermissionLevel.EDIT_FULL).andExpect(status().isNotFound());
    unlink(clubA, rowC, PermissionLevel.EDIT_FULL).andExpect(status().isNotFound());
    unlinkPlayer(clubA, playerB, PermissionLevel.EDIT_FULL).andExpect(status().isNotFound());
    unlinkPlayer(clubA, playerC, PermissionLevel.EDIT_FULL).andExpect(status().isNotFound());

    assertThat(rawLinks()).hasSize(2);
    JsonNode a = read(clubA);
    a.get("players").forEach(row -> assertThat(row.get("linkedPlayer").isNull()).isTrue());
    assertThat(readBody(clubA)).doesNotContain(playerB).doesNotContain(playerC);
  }

  @Test
  void twoClubsOfTheSameTeamHaveTheirOwnIdsAndLinks() throws Exception {
    assertThat(read(clubA).get("players").findValuesAsString("id"))
        .doesNotContainAnyElementsOf(read(clubC).get("players").findValuesAsString("id"));

    String playerA = createPlayer(clubA, "דניאל כהן");
    link(clubA, rowId(clubA, COHEN_NAME), playerA, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk());

    assertThat(rowNamed(read(clubC), COHEN_NAME).get("linkedPlayer").isNull()).isTrue();
    assertThat(readBody(clubC)).doesNotContain(playerA);
  }

  // --- races ------------------------------------------------------------------------------------

  /** Ten scraped players linked to one player at once: exactly one wins, never a 500. */
  @Test
  @SuppressWarnings("unchecked")
  void parallelLinksToOnePlayerLetExactlyOneWin() throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      rows.add(statsRow(920000 + i, "שחקן מספר " + i));
    }
    ingestStats(
        SDEROT, playerStatsWith(FETCHED_AT.plus(1, ChronoUnit.HOURS), rows.toArray(Map[]::new)));
    String player = createPlayer(clubA, "יחיד במינו");
    List<String> rowIds = read(clubA).get("players").findValuesAsString("id");
    assertThat(rowIds).hasSize(10);

    List<MockHttpServletResponse> responses =
        inParallel(rowIds.stream().map(rowId -> link(clubA, rowId, player)).toList());

    assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(200, 409);
    assertThat(responses).filteredOn(r -> r.getStatus() == 200).hasSize(1);
    for (MockHttpServletResponse response : responses) {
      if (response.getStatus() == 409) {
        assertThat(JSON.readTree(response.getContentAsString()).get("code").stringValue())
            .isEqualTo("PLAYER_ALREADY_LINKED");
      }
    }
    assertThat(rawLinks()).hasSize(1);
  }

  /** A double click: the same pair linked ten times at once, with no link before. All succeed. */
  @Test
  void parallelLinksOfTheSamePairAllSucceed() throws Exception {
    String player = createPlayer(clubA, "דניאל כהן");
    String row = rowId(clubA, COHEN_NAME);

    List<MockHttpServletResponse> responses =
        inParallel(java.util.Collections.nCopies(10, link(clubA, row, player)));

    assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(200);
    assertThat(rawLinks()).hasSize(1);
    assertThat(rawLinks().getFirst().getString("playerId")).isEqualTo(player);
  }

  // --- a player's absence from a scrape --------------------------------------------------------

  @Test
  void aLinkOutlivesThePlayersAbsenceAndShowsAgainWhenTheyReturn() throws Exception {
    String player = createPlayer(clubA, "יוסף לוי");
    link(clubA, rowId(clubA, LEVI_NAME), player, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk());

    ingestStats(
        SDEROT,
        playerStatsWith(
            FETCHED_AT.plus(1, ChronoUnit.HOURS),
            statsRow(COHEN_ID, COHEN_NAME),
            statsRow(MIZRAHI_ID, MIZRAHI_NAME)));
    assertThat(names(read(clubA))).containsExactly(COHEN_NAME, MIZRAHI_NAME);
    assertThat(rawLinks()).hasSize(1);
    // Still linked, so never suggested for another row.
    assertThat(readBody(clubA)).doesNotContain(player);

    ingestStats(SDEROT, playerStats(FETCHED_AT.plus(2, ChronoUnit.HOURS)));
    assertThat(rowNamed(read(clubA), LEVI_NAME).get("linkedPlayer").get("id").stringValue())
        .isEqualTo(player);
  }

  @Test
  void aPlayerWhoseScrapedPlayerIsGoneCanStillBeUnlinked() throws Exception {
    String player = createPlayer(clubA, "יוסף לוי");
    link(clubA, rowId(clubA, LEVI_NAME), player, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk());
    ingestStats(
        SDEROT,
        playerStatsWith(FETCHED_AT.plus(1, ChronoUnit.HOURS), statsRow(COHEN_ID, COHEN_NAME)));

    unlinkPlayer(clubA, player, PermissionLevel.EDIT_FULL).andExpect(status().isNoContent());

    assertThat(rawLinks()).isEmpty();
    remove(clubA, player).andExpect(status().isNoContent());
  }

  // --- a dangling link --------------------------------------------------------------------------

  /** A link whose player was deleted behind the guard's back counts as no link. */
  @Test
  void aLinkToADeletedPlayerCountsAsNone() throws Exception {
    String deleted = createPlayer(clubA, "דניאל כהן");
    String row = rowId(clubA, COHEN_NAME);
    link(clubA, row, deleted, PermissionLevel.EDIT_FULL).andExpect(status().isOk());
    mongoTemplate.getCollection("players").deleteOne(new Document("_id", new ObjectId(deleted)));
    String twin = createPlayer(clubA, "כהן דניאל");

    JsonNode cohen = rowNamed(read(clubA), COHEN_NAME);
    assertThat(cohen.get("linkedPlayer").isNull()).isTrue();
    assertThat(cohen.get("suggestedPlayer").get("id").stringValue()).isEqualTo(twin);

    link(clubA, row, twin, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkedPlayer.id").value(twin));
    assertThat(rawLinks()).hasSize(1);
    assertThat(rawLinks().getFirst().getString("playerId")).isEqualTo(twin);
  }

  // --- suggestions ------------------------------------------------------------------------------

  @Test
  void theReadSuggestsUnambiguousUnlinkedPlayersOnly() throws Exception {
    String cohen = createPlayer(clubA, "דניאל כהן");
    String levi = createPlayer(clubA, "יוסף לוי");
    release(clubA, levi);
    String mizrahi = createPlayer(clubA, "אבירם מזרחי");
    createPlayer(clubA, "עומר פרץ");

    JsonNode node = read(clubA);
    assertThat(rowNamed(node, COHEN_NAME).get("suggestedPlayer").get("id").stringValue())
        .isEqualTo(cohen);
    // Released players can be suggested; three words against two is a partial match.
    JsonNode leviSuggestion = rowNamed(node, LEVI_NAME).get("suggestedPlayer");
    assertThat(leviSuggestion.get("id").stringValue()).isEqualTo(levi);
    assertThat(leviSuggestion.get("active").booleanValue()).isFalse();
    assertThat(rowNamed(node, MIZRAHI_NAME).get("suggestedPlayer").get("id").stringValue())
        .isEqualTo(mizrahi);

    // A linked player is never suggested, and a linked row gets no suggestion.
    link(clubA, rowId(clubA, MIZRAHI_NAME), cohen, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk());
    node = read(clubA);
    assertThat(rowNamed(node, COHEN_NAME).get("suggestedPlayer").isNull()).isTrue();
    assertThat(rowNamed(node, MIZRAHI_NAME).get("suggestedPlayer").isNull()).isTrue();
    assertThat(rowNamed(node, LEVI_NAME).get("suggestedPlayer").get("id").stringValue())
        .isEqualTo(levi);
  }

  // --- deleting a linked player -----------------------------------------------------------------

  @Test
  void aLinkedPlayerCantBeDeletedAndKeepsTheirPhotoUntilUnlinked() throws Exception {
    String player = createPlayer(clubA, "דניאל כהן");
    uploadPhoto(clubA, player);
    link(clubA, rowId(clubA, COHEN_NAME), player, PermissionLevel.EDIT_FULL)
        .andExpect(status().isOk());

    remove(clubA, player)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PLAYER_STATS_LINKED"))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "This player is linked to scraped statistics; remove the link before"
                        + " deleting"));

    assertThat(rawPlayer(player)).isNotNull();
    assertThat(photoFiles(player)).hasSize(1);
    mockMvc
        .perform(
            get("/squad/players/" + player + "/photo")
                .header("Authorization", tokens.bearer(clubA, PermissionLevel.VIEW_ONLY)))
        .andExpect(status().isOk());

    unlinkPlayer(clubA, player, PermissionLevel.EDIT_FULL).andExpect(status().isNoContent());
    remove(clubA, player).andExpect(status().isNoContent());
    assertThat(rawPlayer(player)).isNull();
    assertThat(photoFiles(player)).isEmpty();
  }

  // --- helpers ----------------------------------------------------------------------------------

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

  private void release(String clubId, String playerId) throws Exception {
    mockMvc
        .perform(
            post("/squad/players/" + playerId + "/release")
                .header("Authorization", tokens.bearer(clubId, PermissionLevel.EDIT_FULL))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isOk());
  }

  private void uploadPhoto(String clubId, String playerId) throws Exception {
    mockMvc
        .perform(
            multipart(HttpMethod.PUT, "/squad/players/" + playerId + "/photo")
                .file(new MockMultipartFile("file", "photo", "image/png", TestImages.png()))
                .header("Authorization", tokens.bearer(clubId, PermissionLevel.EDIT_FULL)))
        .andExpect(status().isNoContent());
  }

  private ResultActions link(String clubId, String rowId, String playerId, PermissionLevel level)
      throws Exception {
    return mockMvc.perform(
        put(STATS + "/" + rowId + "/link")
            .header("Authorization", tokens.bearer(clubId, level))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("playerId", playerId))));
  }

  /** A link request as an EDIT_FULL user, not sent yet. */
  private Callable<MockHttpServletResponse> link(String clubId, String rowId, String playerId) {
    return () -> link(clubId, rowId, playerId, PermissionLevel.EDIT_FULL).andReturn().getResponse();
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

  private ResultActions unlink(String clubId, String rowId, PermissionLevel level)
      throws Exception {
    return mockMvc.perform(
        delete(STATS + "/" + rowId + "/link")
            .header("Authorization", tokens.bearer(clubId, level)));
  }

  private ResultActions unlinkPlayer(String clubId, String playerId, PermissionLevel level)
      throws Exception {
    return mockMvc.perform(
        delete("/squad/players/" + playerId + "/stats-link")
            .header("Authorization", tokens.bearer(clubId, level)));
  }

  private ResultActions remove(String clubId, String playerId) throws Exception {
    return mockMvc.perform(
        delete("/squad/players/" + playerId)
            .header("Authorization", tokens.bearer(clubId, PermissionLevel.ADMIN)));
  }

  private String readBody(String clubId) throws Exception {
    return mockMvc
        .perform(
            get(STATS).header("Authorization", tokens.bearer(clubId, PermissionLevel.VIEW_ONLY)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private JsonNode read(String clubId) throws Exception {
    return JSON.readTree(readBody(clubId));
  }

  private String rowId(String clubId, String name) throws Exception {
    return rowNamed(read(clubId), name).get("id").stringValue();
  }

  private static JsonNode rowNamed(JsonNode response, String name) {
    for (JsonNode row : response.get("players")) {
      if (row.get("name").stringValue().equals(name)) {
        return row;
      }
    }
    throw new AssertionError("No row named " + name);
  }

  private static List<String> names(JsonNode response) {
    return response.get("players").findValuesAsString("name");
  }

  private List<Document> rawLinks() {
    return mongoTemplate.getCollection("player_stats_links").find().into(new ArrayList<>());
  }

  private Document rawPlayer(String id) {
    return mongoTemplate
        .getCollection("players")
        .find(new Document("_id", new ObjectId(id)))
        .first();
  }

  private List<Document> photoFiles(String playerId) {
    return mongoTemplate
        .getCollection("images.files")
        .find(new Document("metadata.ownerId", playerId))
        .into(new ArrayList<>());
  }

  private static List<String> fieldNames(JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  private static List<String> allFieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    if (node.isObject()) {
      for (String name : node.propertyNames()) {
        names.add(name);
        names.addAll(allFieldNames(node.get(name)));
      }
    } else if (node.isArray()) {
      node.forEach(child -> names.addAll(allFieldNames(child)));
    }
    return names;
  }

  /** Every scalar value in the tree, as text. */
  private static List<String> allValues(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node.isObject()) {
      for (String name : node.propertyNames()) {
        values.addAll(allValues(node.get(name)));
      }
    } else if (node.isArray()) {
      node.forEach(child -> values.addAll(allValues(child)));
    } else if (!node.isNull()) {
      values.add(node.asString());
    }
    return values;
  }
}
