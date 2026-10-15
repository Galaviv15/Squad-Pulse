package com.squadpulse.scrapingintegration;

import static com.squadpulse.scrapingintegration.ScrapePayloads.FETCHED_AT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.HADERA_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.KIRYAT_MALACHI;
import static com.squadpulse.scrapingintegration.ScrapePayloads.KIRYAT_MALACHI_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.LEAGUE;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SDEROT_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.SEASON;
import static com.squadpulse.scrapingintegration.ScrapePayloads.TIRA_NAME;
import static com.squadpulse.scrapingintegration.ScrapePayloads.fixtures;
import static com.squadpulse.scrapingintegration.ScrapePayloads.fixturesWith;
import static com.squadpulse.scrapingintegration.ScrapePayloads.game;
import static com.squadpulse.scrapingintegration.ScrapePayloads.json;
import static com.squadpulse.scrapingintegration.ScrapePayloads.request;
import static com.squadpulse.scrapingintegration.ScrapePayloads.row;
import static com.squadpulse.scrapingintegration.ScrapePayloads.standings;
import static com.squadpulse.scrapingintegration.ScrapePayloads.standingsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.CapturedLogs;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;

/**
 * The scraper ingest and the match reads end to end on a real MongoDB (KAN-66), behind both real
 * security chains: the service token, club isolation and the shared standings' safety rules, the
 * ingest outcomes, validation, staleness, and what the reads show.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + ScrapeResultApiIntegrationTest.SERVICE_TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import({TestAccessTokens.class, TestClubMappings.class})
class ScrapeResultApiIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  private static final String INGEST = "/internal/scrape-results";

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
  @Autowired private ApplicationContext applicationContext;

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
    // Documents only: dropping a collection would drop its unique index too.
    mongoTemplate.getCollection("league_fixtures").deleteMany(new Document());
    mongoTemplate.getCollection("league_standings").deleteMany(new Document());
    clubs.deleteAllClubs();
  }

  // --- security ----------------------------------------------------------------------------------

  @Test
  void theIngestRefusesEveryCallerButTheScraperAndWritesNothing() throws Exception {
    String oneCharacterOff =
        SERVICE_TOKEN.substring(0, SERVICE_TOKEN.length() - 1)
            + (SERVICE_TOKEN.endsWith("f") ? "e" : "f");
    List<String> headers =
        List.of(
            "Bearer wrong-token",
            "Bearer " + oneCharacterOff,
            "Bearer " + SERVICE_TOKEN + "x",
            SERVICE_TOKEN,
            "Basic " + SERVICE_TOKEN,
            tokens.bearer(clubA, PermissionLevel.ADMIN));
    mockMvc
        .perform(post(INGEST).contentType(MediaType.APPLICATION_JSON).content(json(full())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
    for (String header : headers) {
      mockMvc
          .perform(
              post(INGEST)
                  .header("Authorization", header)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(full())))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.status").value(401))
          .andExpect(jsonPath("$.message").value("Authentication required"));
    }
    assertNothingStored();
  }

  @Test
  void theServiceTokenOpensNoUserEndpoint() throws Exception {
    for (String path :
        List.of("/matches/fixtures", "/matches/league-table", "/auth/users/me", "/squad/players")) {
      mockMvc
          .perform(get(path).header("Authorization", "Bearer " + SERVICE_TOKEN))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void theTokenIsNeverLogged() throws Exception {
    ingest(full()).andExpect(status().isOk());
    mockMvc
        .perform(post(INGEST).header("Authorization", "Bearer " + SERVICE_TOKEN + "x"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/matches/fixtures").header("Authorization", "Bearer " + SERVICE_TOKEN))
        .andExpect(status().isUnauthorized());
    ingest(Map.of("teamId", "not a number")).andExpect(status().isBadRequest());

    assertThat(logs.everythingLogged()).doesNotContain(SERVICE_TOKEN);
  }

  @Test
  void eachChainRunsOnlyItsOwnAuthenticationFilter() {
    FilterChainProxy proxy = applicationContext.getBean(FilterChainProxy.class);

    assertThat(filterNames(proxy, INGEST))
        .contains("ServiceTokenAuthenticationFilter")
        .doesNotContain("JwtAuthenticationFilter");
    // "/internal/**" claims "/internal" itself too.
    for (String path : List.of("/internal/anything/else", "/internal")) {
      assertThat(filterNames(proxy, path))
          .as(path)
          .contains("ServiceTokenAuthenticationFilter")
          .doesNotContain("JwtAuthenticationFilter");
    }
    for (String path :
        List.of("/matches/fixtures", "/squad/players", "/auth/login", "/internal-not")) {
      assertThat(filterNames(proxy, path))
          .as(path)
          .contains("JwtAuthenticationFilter")
          .doesNotContain("ServiceTokenAuthenticationFilter");
    }
  }

  /** Not a servlet filter of its own, which would run on every path, outside any chain. */
  @Test
  void theServiceTokenFilterIsNotABean() {
    assertThat(applicationContext.getBeansOfType(ServiceTokenAuthenticationFilter.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(Filter.class).values())
        .noneMatch(ServiceTokenAuthenticationFilter.class::isInstance);
  }

  // --- isolation and the shared standings --------------------------------------------------------

  @Test
  void twoClubsInOneLeagueShareTheTableButNotTheirFixtures() throws Exception {
    String clubB = clubs.createClub("Kiryat Malachi", KIRYAT_MALACHI, LEAGUE, SEASON);

    ingest(full())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"))
        .andExpect(jsonPath("$.clubsUpdated").value(1));

    List<Document> fixtures = rawDocuments("league_fixtures");
    assertThat(fixtures).hasSize(1);
    assertThat(fixtures.get(0).getString("clubId")).isEqualTo(clubA);

    readFixtures(clubB)
        .andExpect(jsonPath("$.state").value("NO_DATA"))
        .andExpect(jsonPath("$.games").isEmpty());
    readTable(clubA)
        .andExpect(jsonPath("$.state").value("OK"))
        .andExpect(jsonPath("$.rows[2].teamName").value(SDEROT_NAME))
        .andExpect(jsonPath("$.rows[2].ownTeam").value(true))
        .andExpect(jsonPath("$.rows[1].ownTeam").value(false));
    readTable(clubB)
        .andExpect(jsonPath("$.state").value("OK"))
        .andExpect(jsonPath("$.rows[1].teamName").value(KIRYAT_MALACHI_NAME))
        .andExpect(jsonPath("$.rows[1].ownTeam").value(true))
        .andExpect(jsonPath("$.rows[2].ownTeam").value(false));
  }

  @Test
  void aClubInAnotherLeagueNeverSeesThatTable() throws Exception {
    String other = clubs.createClub("Elsewhere", 9001, LEAGUE + 1, SEASON);
    ingest(full()).andExpect(status().isOk());

    readTable(other)
        .andExpect(jsonPath("$.state").value("NO_DATA"))
        .andExpect(jsonPath("$.rows").isEmpty());
  }

  /** No read takes an id from the request: an added {@code leagueId} etc. changes nothing. */
  @Test
  void readsIgnoreAnyIdInTheRequest() throws Exception {
    String other = clubs.createClub("Elsewhere", 9001, LEAGUE + 1, SEASON);
    ingest(full()).andExpect(status().isOk());
    ingest(
            request(
                9001,
                LEAGUE + 1,
                SEASON,
                null,
                standingsWith(
                    FETCHED_AT,
                    row(1, 9001, "Elsewhere FC", 1, 1, 0, 0, 2, 0, 3),
                    row(2, 9002, "Other FC", 1, 0, 0, 1, 0, 2, 0))))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"));

    String query = "?leagueId=" + (LEAGUE + 1) + "&seasonId=" + SEASON + "&teamId=9001";
    String expectedTable = readTable(clubA).andReturn().getResponse().getContentAsString();
    String withIds =
        mockMvc
            .perform(get("/matches/league-table" + query).header("Authorization", viewer(clubA)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(withIds).isEqualTo(expectedTable).contains(SDEROT_NAME).doesNotContain("Other FC");

    mockMvc
        .perform(get("/matches/fixtures" + query).header("Authorization", viewer(other)))
        .andExpect(jsonPath("$.state").value("NO_DATA"));
  }

  @Test
  void neitherReadResponseCarriesAnyId() throws Exception {
    ingest(full()).andExpect(status().isOk());

    for (String body :
        List.of(
            readFixtures(clubA).andReturn().getResponse().getContentAsString(),
            readTable(clubA).andReturn().getResponse().getContentAsString())) {
      assertThat(fieldNames(ScrapePayloads.JSON.readTree(body)))
          .isNotEmpty()
          .doesNotContain("teamId", "leagueId", "seasonId", "gameId", "homeTeamId", "awayTeamId");
    }
  }

  @Test
  void standingsOfALeagueNoClubIsMappedToAreRejected() throws Exception {
    ingest(request(SDEROT, LEAGUE + 7, SEASON, null, standings(FETCHED_AT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.standings.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.standings.reason").value("NOT_MAPPED"))
        .andExpect(jsonPath("$.fixtures.outcome").value("NOT_SENT"));
    assertNothingStored();
  }

  @Test
  void fixturesOfATeamNoClubIsMappedToAreRejected() throws Exception {
    // Same league and season as club A, another team: the standings are stored, the fixtures not.
    ingest(request(TIRA_TEAM, LEAGUE, SEASON, tiraFixtures(), standings(FETCHED_AT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.fixtures.reason").value("NOT_MAPPED"))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"))
        .andExpect(jsonPath("$.clubsUpdated").value(0));
    assertThat(rawDocuments("league_fixtures")).isEmpty();
  }

  // --- ingest ------------------------------------------------------------------------------------

  @Test
  void aFullScrapeIsStoredAsSent() throws Exception {
    ingest(full())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.fixtures.reason").isEmpty())
        .andExpect(jsonPath("$.standings.outcome").value("STORED"))
        .andExpect(jsonPath("$.clubsUpdated").value(1))
        .andExpect(jsonPath("$.warnings").isEmpty());

    Document fixtures = rawDocuments("league_fixtures").get(0);
    assertThat(fixtures.getInteger("teamId")).isEqualTo(SDEROT);
    assertThat(fixtures.getInteger("leagueId")).isEqualTo(LEAGUE);
    assertThat(fixtures.getInteger("seasonId")).isEqualTo(SEASON);
    assertThat(fixtures.getString("teamName")).isEqualTo(SDEROT_NAME);
    assertThat(fixtures.getDate("fetchedAt").toInstant()).isEqualTo(FETCHED_AT);
    assertThat(fixtures.getDate("storedAt")).isNotNull();
    assertThat(fixtures.get("runId")).isNull();
    List<Document> games = fixtures.getList("games", Document.class);
    assertThat(games).hasSize(5);
    Document first = games.get(0);
    assertThat(first.getInteger("gameId")).isEqualTo(1122601);
    assertThat(first.getString("date")).isEqualTo("2026-09-05");
    assertThat(first.getString("kickoffTime")).isEqualTo("20:00");
    assertThat(first.getString("homeTeamName")).isEqualTo(TIRA_NAME);
    assertThat(first.getInteger("homeTeamId")).isEqualTo(ScrapePayloads.TIRA);
    assertThat(first.getInteger("awayTeamId")).isEqualTo(SDEROT);
    assertThat(first.getInteger("homeScore")).isEqualTo(4);
    assertThat(first.getInteger("awayScore")).isEqualTo(1);
    Document unscheduled = games.get(3);
    assertThat(unscheduled.containsKey("gameId") ? unscheduled.get("gameId") : null).isNull();
    assertThat(unscheduled.get("kickoffTime")).isNull();
    assertThat(unscheduled.get("venue")).isNull();
    assertThat(unscheduled.get("homeScore")).isNull();
    assertThat(unscheduled.getInteger("awayTeamId")).isEqualTo(ScrapePayloads.HADERA);

    Document standings = rawDocuments("league_standings").get(0);
    assertThat(standings.get("clubId")).isNull();
    assertThat(standings.getInteger("round")).isEqualTo(3);
    List<Document> rows = standings.getList("rows", Document.class);
    assertThat(rows).hasSize(5);
    assertThat(rows.get(4).getInteger("points")).isEqualTo(-1);
    assertThat(rows.get(2).getInteger("goalsFor")).isEqualTo(4);
    assertThat(rows.get(2).getInteger("goalsAgainst")).isEqualTo(6);
  }

  @Test
  void namesAreResolvedAgainstTheStoredStandingsWhenTheScrapeHasNone() throws Exception {
    ingest(request(SDEROT, LEAGUE, SEASON, null, standings(FETCHED_AT))).andExpect(status().isOk());
    ingest(request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), null))
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.standings.outcome").value("NOT_SENT"))
        .andExpect(jsonPath("$.warnings").isEmpty());

    List<Document> games = rawDocuments("league_fixtures").get(0).getList("games", Document.class);
    assertThat(games).allSatisfy(g -> assertThat(g.getInteger("homeTeamId")).isNotNull());
    assertThat(games).allSatisfy(g -> assertThat(g.getInteger("awayTeamId")).isNotNull());
  }

  @Test
  void anUnknownOpponentKeepsANullIdAndIsReported() throws Exception {
    Map<String, Object> body = full();
    ScrapePayloads.games(body)
        .add(game(null, "2026-10-10", null, "Unknown FC", SDEROT_NAME, null, null, null));

    ingest(body)
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.warnings[0].code").value("UNRESOLVED_TEAM_NAMES"))
        .andExpect(jsonPath("$.warnings[0].count").value(1));

    Document unknown =
        rawDocuments("league_fixtures").get(0).getList("games", Document.class).get(5);
    assertThat(unknown.get("homeTeamId")).isNull();
    assertThat(unknown.getInteger("awayTeamId")).isEqualTo(SDEROT);
  }

  @Test
  void withoutAnyStandingsOurTeamIsTheNameInEveryGame() throws Exception {
    ingest(request(SDEROT, LEAGUE, SEASON, fixtures(FETCHED_AT), null))
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.warnings[0].code").value("UNRESOLVED_TEAM_NAMES"))
        .andExpect(jsonPath("$.warnings[0].count").value(5));

    Document stored = rawDocuments("league_fixtures").get(0);
    assertThat(stored.getString("teamName")).isEqualTo(SDEROT_NAME);
    readFixtures(clubA)
        .andExpect(jsonPath("$.teamName").value(SDEROT_NAME))
        .andExpect(jsonPath("$.games[0].opponentName").value(TIRA_NAME));
  }

  @Test
  void withoutStandingsAnAmbiguousOwnTeamIsRejected() throws Exception {
    // One game only: both of its names appear in every game.
    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON,
                fixturesWith(
                    FETCHED_AT,
                    game(1, "2026-09-05", "20:00", TIRA_NAME, SDEROT_NAME, null, null, null)),
                null))
        .andExpect(jsonPath("$.fixtures.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.fixtures.reason").value("OWN_TEAM_UNKNOWN"));
    assertNothingStored();
  }

  @Test
  void aGameWithoutOurTeamRejectsTheFixtures() throws Exception {
    Map<String, Object> body = full();
    ScrapePayloads.games(body)
        .add(game(null, "2026-10-10", null, TIRA_NAME, HADERA_NAME, null, null, null));

    ingest(body)
        .andExpect(jsonPath("$.fixtures.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.fixtures.reason").value("INVALID"))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"));
    assertThat(rawDocuments("league_fixtures")).isEmpty();
  }

  @Test
  void aTeamMissingFromTheStandingsIsStoredButReportedEverywhere() throws Exception {
    clubs.map(clubA, 9999, LEAGUE, SEASON);

    ingest(request(9999, LEAGUE, SEASON, fixtures(FETCHED_AT), standings(FETCHED_AT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"))
        .andExpect(jsonPath("$.warnings[0].code").value("TEAM_NOT_IN_STANDINGS"))
        .andExpect(jsonPath("$.warnings[0].count").isEmpty());

    assertThat(logs.warningsOrAbove())
        .anySatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .contains("Team 9999 is not in the standings of league " + LEAGUE));
    readFixtures(clubA)
        .andExpect(jsonPath("$.state").value("OK"))
        .andExpect(jsonPath("$.mappingProblem").value("TEAM_NOT_IN_STANDINGS"));
    readTable(clubA)
        .andExpect(jsonPath("$.state").value("OK"))
        .andExpect(jsonPath("$.mappingProblem").value("TEAM_NOT_IN_STANDINGS"))
        .andExpect(jsonPath("$.rows[?(@.ownTeam == true)]").isEmpty());
  }

  @Test
  void aDuplicatePairingRejectsOnlyTheFixturesAndKeepsTheOldSnapshot() throws Exception {
    ingest(full()).andExpect(status().isOk());
    Document before = rawDocuments("league_fixtures").get(0);

    Instant later = FETCHED_AT.plus(1, ChronoUnit.HOURS);
    Map<String, Object> body = request(SDEROT, LEAGUE, SEASON, fixtures(later), standings(later));
    ScrapePayloads.games(body)
        .add(game(null, "2027-05-01", null, TIRA_NAME, SDEROT_NAME, null, null, null));

    ingest(body)
        .andExpect(jsonPath("$.fixtures.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.fixtures.reason").value("DUPLICATE_GAME"))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"));
    assertThat(rawDocuments("league_fixtures").get(0)).isEqualTo(before);
    assertThat(rawDocuments("league_standings").get(0).getDate("fetchedAt").toInstant())
        .isEqualTo(later);
  }

  @Test
  void aFailedPartIsRecordedNotWrittenAndItsDetailNotLoggedAtInfo() throws Exception {
    ingest(full()).andExpect(status().isOk());
    Document fixturesBefore = rawDocuments("league_fixtures").get(0);
    Document standingsBefore = rawDocuments("league_standings").get(0);

    String detail = "cf-ray secret-looking-detail-123";
    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON,
                ScrapePayloads.failed("SOURCE_BLOCKED", detail),
                ScrapePayloads.failed("TIMEOUT", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("FAILURE_RECORDED"))
        .andExpect(jsonPath("$.standings.outcome").value("FAILURE_RECORDED"))
        .andExpect(jsonPath("$.clubsUpdated").value(0));

    assertThat(rawDocuments("league_fixtures").get(0)).isEqualTo(fixturesBefore);
    assertThat(rawDocuments("league_standings").get(0)).isEqualTo(standingsBefore);
    assertThat(logs.events())
        .anySatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .contains(
                        "Scraper reported fixtures failed (SOURCE_BLOCKED) for team " + SDEROT));
    assertThat(logs.events())
        .filteredOn(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO))
        .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(detail));
  }

  @Test
  void anOlderScrapeOrAReplayChangesNothing() throws Exception {
    Instant newer = FETCHED_AT.plus(2, ChronoUnit.HOURS);
    ingest(request(SDEROT, LEAGUE, SEASON, fixtures(newer), standings(newer)))
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"));
    List<Document> fixturesBefore = rawDocuments("league_fixtures");
    List<Document> standingsBefore = rawDocuments("league_standings");

    for (Instant fetchedAt : List.of(FETCHED_AT, newer)) {
      ingest(request(SDEROT, LEAGUE, SEASON, fixtures(fetchedAt), standings(fetchedAt)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.fixtures.outcome").value("STALE"))
          .andExpect(jsonPath("$.standings.outcome").value("STALE"))
          .andExpect(jsonPath("$.clubsUpdated").value(0));
    }
    assertThat(rawDocuments("league_fixtures")).isEqualTo(fixturesBefore);
    assertThat(rawDocuments("league_standings")).isEqualTo(standingsBefore);
  }

  @Test
  void aNewerScrapeReplacesTheWholeSnapshot() throws Exception {
    ingest(full()).andExpect(status().isOk());
    Instant later = FETCHED_AT.plus(3, ChronoUnit.HOURS);

    ingest(
            request(
                SDEROT,
                LEAGUE,
                SEASON,
                fixturesWith(
                    later,
                    game(1122601, "2026-09-05", "20:00", TIRA_NAME, SDEROT_NAME, "טירה", 4, 1)),
                null))
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"));

    Document stored = rawDocuments("league_fixtures").get(0);
    assertThat(stored.getList("games", Document.class)).hasSize(1);
    assertThat(stored.getDate("fetchedAt").toInstant()).isEqualTo(later);
    assertThat(stored.getLong("version")).isEqualTo(1L);
  }

  // --- validation --------------------------------------------------------------------------------

  @Test
  void typeErrorsAndMissingFieldsAre400s() throws Exception {
    Map<String, Object> badType = full();
    badType.put("teamId", "abc");
    expect400(badType, "teamId: invalid value");

    Map<String, Object> badDate = full();
    ScrapePayloads.games(badDate).get(0).put("date", "05/09/2026");
    expect400(badDate, "fixtures.games[0].date: invalid value");

    Map<String, Object> badTime = full();
    ScrapePayloads.games(badTime).get(0).put("kickoffTime", "20:00:30");
    expect400(badTime, "fixtures.games[0].kickoffTime: invalid value");

    Map<String, Object> unknownStatus = full();
    ScrapePayloads.part(unknownStatus, "fixtures").put("status", "PARTIAL");
    expect400(unknownStatus, "fixtures.status: invalid value");

    Map<String, Object> missingTeam = full();
    missingTeam.remove("teamId");
    expect400(missingTeam, "teamId: must not be null");

    Map<String, Object> missingName = full();
    ScrapePayloads.rows(missingName).get(0).remove("teamName");
    expect400(missingName, "standings.rows[0].teamName: must not be blank");

    mockMvc
        .perform(
            post(INGEST)
                .header("Authorization", "Bearer " + SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
        .andExpect(status().isBadRequest());
    assertNothingStored();
  }

  @Test
  void everyNumericRangeIsEnforced() throws Exception {
    List<Map<String, Object>> invalid = new ArrayList<>();
    invalid.add(with(full(), b -> b.put("leagueId", 0)));
    invalid.add(with(full(), b -> ScrapePayloads.games(b).get(0).put("gameId", -1)));
    invalid.add(with(full(), b -> ScrapePayloads.games(b).get(0).put("homeScore", 100)));
    invalid.add(with(full(), b -> ScrapePayloads.games(b).get(0).put("awayScore", -1)));
    invalid.add(with(full(), b -> ScrapePayloads.rows(b).get(0).put("position", 6)));
    invalid.add(with(full(), b -> ScrapePayloads.rows(b).get(0).put("goalsFor", 1000)));
    invalid.add(with(full(), b -> ScrapePayloads.rows(b).get(0).put("points", -100)));
    invalid.add(with(full(), b -> ScrapePayloads.part(b, "standings").put("round", 0)));
    invalid.add(with(full(), b -> ScrapePayloads.rows(b).subList(1, 5).clear()));
    invalid.add(with(full(), b -> ScrapePayloads.games(b).clear()));
    invalid.add(with(full(), b -> b.put("runId", "x".repeat(101))));
    invalid.add(
        with(full(), b -> ScrapePayloads.games(b).get(0).put("homeTeamName", "x".repeat(101))));
    invalid.add(with(full(), b -> ScrapePayloads.games(b).get(0).put("venue", " ")));
    invalid.add(with(full(), b -> ScrapePayloads.games(b).get(0).put("awayTeamName", TIRA_NAME)));
    invalid.add(with(full(), b -> ScrapePayloads.rows(b).get(1).put("teamId", SDEROT)));
    invalid.add(with(full(), b -> ScrapePayloads.rows(b).get(1).put("teamName", SDEROT_NAME)));
    for (Map<String, Object> body : invalid) {
      ingest(body).andExpect(status().isBadRequest());
    }
    assertNothingStored();
  }

  @Test
  void crossFieldRulesAre400s() throws Exception {
    expect400(
        with(full(), b -> ScrapePayloads.rows(b).get(0).put("won", 3)),
        "standings.rows[0].recordConsistent: won + drawn + lost must equal played");
    expect400(
        with(full(), b -> ScrapePayloads.games(b).get(0).put("awayScore", null)),
        "fixtures.games[0].scoreComplete: homeScore and awayScore must both be set or both be"
            + " null");
    expect400(
        request(SDEROT, LEAGUE, SEASON, null, null),
        "anyPartPresent: fixtures, standings, playerStats or gameDetails must be present");
    expect400(
        request(SDEROT, LEAGUE, SEASON, ScrapePayloads.failed(null, "x"), null),
        "fixtures.consistentWithStatus: status OK needs fetchedAt and games and no reason or"
            + " detail; FAILED needs a reason and no fetchedAt or games");
    expect400(
        request(SDEROT, LEAGUE, SEASON, ScrapePayloads.failed("TIMEOUT", "x".repeat(501)), null),
        "fixtures.detail: size must be between 0 and 500");
    assertNothingStored();
  }

  @Test
  void anUnknownPropertyAnywhereIsA400() throws Exception {
    expect400(with(full(), b -> b.put("clubId", "abc")), "clubId: invalid value");
    expect400(
        with(full(), b -> ScrapePayloads.games(b).get(0).put("homeGoals", 1)),
        "fixtures.games[0].homeGoals: invalid value");
    expect400(
        with(full(), b -> ScrapePayloads.rows(b).get(0).put("goalDifference", 5)),
        "standings.rows[0].goalDifference: invalid value");
    assertNothingStored();
  }

  @Test
  void aFetchedAtMoreThanFiveMinutesAheadIsA400() throws Exception {
    Instant future = Instant.now().plus(10, ChronoUnit.MINUTES);
    expect400(
        request(SDEROT, LEAGUE, SEASON, fixtures(future), null),
        "fixtures.fetchedAt: must not be in the future");
    expect400(
        request(SDEROT, LEAGUE, SEASON, null, standings(future)),
        "standings.fetchedAt: must not be in the future");
    assertNothingStored();

    Instant slightlyAhead = Instant.now().plus(1, ChronoUnit.MINUTES);
    ingest(request(SDEROT, LEAGUE, SEASON, fixtures(slightlyAhead), standings(slightlyAhead)))
        .andExpect(status().isOk());
  }

  @Test
  void aControlCharacterInAnyStringIsA400() throws Exception {
    String control = ": must not contain control characters";
    expect400(
        with(full(), b -> ScrapePayloads.games(b).get(0).put("homeTeamName", "Tira\u001FFC")),
        "fixtures.games[0].homeTeamName" + control);
    // At the end too: trimming strips spaces only, never a control character.
    expect400(
        with(full(), b -> ScrapePayloads.games(b).get(1).put("awayTeamName", "Kiryat\u001F")),
        "fixtures.games[1].awayTeamName" + control);
    expect400(
        with(full(), b -> ScrapePayloads.rows(b).get(0).put("teamName", "Tira\nFC")),
        "standings.rows[0].teamName" + control);
    expect400(
        with(full(), b -> ScrapePayloads.games(b).get(0).put("venue", "Tira\tStadium")),
        "fixtures.games[0].venue" + control);
    expect400(with(full(), b -> b.put("runId", "run\n1")), "runId" + control);
    expect400(
        request(SDEROT, LEAGUE, SEASON, ScrapePayloads.failed("TIMEOUT", "a\rb"), null),
        "fixtures.detail" + control);
    assertNothingStored();
  }

  @Test
  void hebrewNamesWithQuotesDotsAndCommasAreAccepted() throws Exception {
    String name = "הפ' מחנה יהודה מאיה,אלירן";
    Map<String, Object> body = full();
    ScrapePayloads.rows(body).get(4).put("teamName", name);
    ScrapePayloads.games(body).get(3).put("awayTeamName", " " + name + " ");

    ingest(body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"))
        .andExpect(jsonPath("$.warnings").isEmpty());
    Document game = rawDocuments("league_fixtures").get(0).getList("games", Document.class).get(3);
    assertThat(game.getString("awayTeamName")).isEqualTo(name);
    assertThat(game.getInteger("awayTeamId")).isEqualTo(ScrapePayloads.HADERA);
    assertThat(game.getString("homeTeamName")).isEqualTo(SDEROT_NAME);
  }

  /** A stale standings part is older than the stored table, so names resolve against the latter. */
  @Test
  void namesResolveAgainstTheNewestTableWhenTheScrapesOwnIsStale() throws Exception {
    Instant newer = FETCHED_AT.plus(2, ChronoUnit.HOURS);
    Map<String, Object> newerTable = standings(newer);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> newerRows = (List<Map<String, Object>>) newerTable.get("rows");
    newerRows.add(row(6, 7010, "New FC", 3, 0, 0, 3, 0, 5, 0));
    ingest(request(SDEROT, LEAGUE, SEASON, null, newerTable))
        .andExpect(jsonPath("$.standings.outcome").value("STORED"));

    Map<String, Object> body = full();
    ScrapePayloads.games(body)
        .add(game(null, "2026-10-17", null, "New FC", SDEROT_NAME, null, null, null));
    ingest(body)
        .andExpect(jsonPath("$.standings.outcome").value("STALE"))
        .andExpect(jsonPath("$.fixtures.outcome").value("STORED"))
        .andExpect(jsonPath("$.warnings").isEmpty());

    Document game = rawDocuments("league_fixtures").get(0).getList("games", Document.class).get(5);
    assertThat(game.getInteger("homeTeamId")).isEqualTo(7010);
  }

  // --- reads -------------------------------------------------------------------------------------

  @Test
  void anUnmappedClubGetsNotMapped() throws Exception {
    String unmapped = clubs.createClub("Unmapped");
    ingest(full()).andExpect(status().isOk());

    readFixtures(unmapped)
        .andExpect(jsonPath("$.state").value("NOT_MAPPED"))
        .andExpect(jsonPath("$.lastUpdatedAt").isEmpty())
        .andExpect(jsonPath("$.teamName").isEmpty())
        .andExpect(jsonPath("$.mappingProblem").isEmpty())
        .andExpect(jsonPath("$.games").isEmpty());
    readTable(unmapped)
        .andExpect(jsonPath("$.state").value("NOT_MAPPED"))
        .andExpect(jsonPath("$.round").isEmpty())
        .andExpect(jsonPath("$.rows").isEmpty());
  }

  @Test
  void aMappedClubWithoutDataGetsNoData() throws Exception {
    readFixtures(clubA)
        .andExpect(jsonPath("$.state").value("NO_DATA"))
        .andExpect(jsonPath("$.games").isEmpty());
    readTable(clubA)
        .andExpect(jsonPath("$.state").value("NO_DATA"))
        .andExpect(jsonPath("$.lastUpdatedAt").isEmpty());
  }

  @Test
  void fixturesAreShownFromOurSideSortedWithUnsetTimesLast() throws Exception {
    ingest(full()).andExpect(status().isOk());

    readFixtures(clubA)
        .andExpect(jsonPath("$.state").value("OK"))
        .andExpect(jsonPath("$.lastUpdatedAt").value(FETCHED_AT.toString()))
        .andExpect(jsonPath("$.teamName").value(SDEROT_NAME))
        .andExpect(jsonPath("$.mappingProblem").isEmpty())
        // Away at Tira, 4-1 to them.
        .andExpect(jsonPath("$.games[0].date").value("2026-09-05"))
        .andExpect(jsonPath("$.games[0].kickoffTime").value("20:00"))
        .andExpect(jsonPath("$.games[0].ownSide").value("AWAY"))
        .andExpect(jsonPath("$.games[0].opponentName").value(TIRA_NAME))
        .andExpect(jsonPath("$.games[0].venue").value("טירה"))
        .andExpect(jsonPath("$.games[0].ownScore").value(1))
        .andExpect(jsonPath("$.games[0].opponentScore").value(4))
        .andExpect(jsonPath("$.games[0].result").value("LOSS"))
        // At home to Kiryat Malachi, 2-1.
        .andExpect(jsonPath("$.games[1].ownSide").value("HOME"))
        .andExpect(jsonPath("$.games[1].ownScore").value(2))
        .andExpect(jsonPath("$.games[1].opponentScore").value(1))
        .andExpect(jsonPath("$.games[1].result").value("WIN"))
        .andExpect(jsonPath("$.games[2].result").value("DRAW"))
        // 3 October: the 16:00 game before the one without a time, though sent after it.
        .andExpect(jsonPath("$.games[3].date").value("2026-10-03"))
        .andExpect(jsonPath("$.games[3].kickoffTime").value("16:00"))
        .andExpect(jsonPath("$.games[3].result").isEmpty())
        .andExpect(jsonPath("$.games[4].date").value("2026-10-03"))
        .andExpect(jsonPath("$.games[4].kickoffTime").isEmpty())
        .andExpect(jsonPath("$.games[4].opponentName").value(HADERA_NAME))
        .andExpect(jsonPath("$.games[4].venue").isEmpty())
        .andExpect(jsonPath("$.games[4].ownScore").isEmpty())
        .andExpect(jsonPath("$.games[4].result").isEmpty());
  }

  @Test
  void theTableIsByPositionWithADerivedGoalDifference() throws Exception {
    Map<String, Object> body = full();
    java.util.Collections.reverse(ScrapePayloads.rows(body));
    ingest(body).andExpect(status().isOk());

    readTable(clubA)
        .andExpect(jsonPath("$.state").value("OK"))
        .andExpect(jsonPath("$.lastUpdatedAt").value(FETCHED_AT.toString()))
        .andExpect(jsonPath("$.round").value(3))
        .andExpect(jsonPath("$.mappingProblem").isEmpty())
        .andExpect(jsonPath("$.rows[0].position").value(1))
        .andExpect(jsonPath("$.rows[0].goalDifference").value(5))
        .andExpect(jsonPath("$.rows[2].teamName").value(SDEROT_NAME))
        .andExpect(jsonPath("$.rows[2].played").value(3))
        .andExpect(jsonPath("$.rows[2].won").value(1))
        .andExpect(jsonPath("$.rows[2].drawn").value(1))
        .andExpect(jsonPath("$.rows[2].lost").value(1))
        .andExpect(jsonPath("$.rows[2].goalsFor").value(4))
        .andExpect(jsonPath("$.rows[2].goalsAgainst").value(6))
        .andExpect(jsonPath("$.rows[2].goalDifference").value(-2))
        .andExpect(jsonPath("$.rows[2].points").value(4))
        .andExpect(jsonPath("$.rows[2].ownTeam").value(true))
        .andExpect(jsonPath("$.rows[4].points").value(-1));
  }

  @Test
  void aNewSeasonHidesTheOldSnapshotsWithoutDeletingThem() throws Exception {
    ingest(full()).andExpect(status().isOk());

    clubs.map(clubA, SDEROT, LEAGUE, SEASON + 1);

    readFixtures(clubA).andExpect(jsonPath("$.state").value("NO_DATA"));
    readTable(clubA).andExpect(jsonPath("$.state").value("NO_DATA"));
    assertThat(rawDocuments("league_fixtures")).hasSize(1);
    assertThat(rawDocuments("league_standings")).hasSize(1);
  }

  @Test
  void viewOnlyMayReadButNotAnAnonymousCaller() throws Exception {
    for (String path : List.of("/matches/fixtures", "/matches/league-table")) {
      mockMvc.perform(get(path).header("Authorization", viewer(clubA))).andExpect(status().isOk());
      mockMvc
          .perform(get(path))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.message").value("Authentication required"));
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static final int TIRA_TEAM = ScrapePayloads.TIRA;

  /** Tira's own list: every game has Tira on one side. */
  private static Map<String, Object> tiraFixtures() {
    return fixturesWith(
        FETCHED_AT,
        game(1122601, "2026-09-05", "20:00", TIRA_NAME, SDEROT_NAME, "טירה", 4, 1),
        game(1122610, "2026-09-12", "19:00", HADERA_NAME, TIRA_NAME, null, 0, 3));
  }

  private static Map<String, Object> full() {
    return ScrapePayloads.full();
  }

  private static Map<String, Object> with(
      Map<String, Object> body, java.util.function.Consumer<Map<String, Object>> change) {
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

  private void expect400(Object body, String detail) throws Exception {
    ingest(body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details[0]").value(detail));
  }

  private ResultActions readFixtures(String clubId) throws Exception {
    return read("/matches/fixtures", clubId);
  }

  private ResultActions readTable(String clubId) throws Exception {
    return read("/matches/league-table", clubId);
  }

  private ResultActions read(String path, String clubId) throws Exception {
    MockHttpServletRequestBuilder request = get(path).header("Authorization", viewer(clubId));
    return mockMvc.perform(request).andExpect(status().isOk());
  }

  private String viewer(String clubId) {
    return tokens.bearer(clubId, PermissionLevel.VIEW_ONLY);
  }

  private List<Document> rawDocuments(String collection) {
    return mongoTemplate.getCollection(collection).find().into(new ArrayList<>());
  }

  private void assertNothingStored() {
    assertThat(rawDocuments("league_fixtures")).isEmpty();
    assertThat(rawDocuments("league_standings")).isEmpty();
  }

  private static List<String> filterNames(FilterChainProxy proxy, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setServletPath(path);
    for (SecurityFilterChain chain : proxy.getFilterChains()) {
      if (chain.matches(request)) {
        return chain.getFilters().stream().map(f -> f.getClass().getSimpleName()).toList();
      }
    }
    throw new AssertionError("No chain matches " + path);
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    if (node.isObject()) {
      node.properties()
          .forEach(
              entry -> {
                names.add(entry.getKey());
                names.addAll(fieldNames(entry.getValue()));
              });
    } else if (node.isArray()) {
      node.forEach(child -> names.addAll(fieldNames(child)));
    }
    return names;
  }
}
