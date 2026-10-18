package com.squadpulse.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.ClubContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * A given-up game is retried {@link GameDetailAttempts#RETRY_GIVEN_UP_AFTER} after its last counted
 * failure (KAN-79): on a real MongoDB, with a clock the test moves, for both places that decide
 * "given up" — the trigger's known games ({@link KnownGames}) and the fixtures' {@code
 * detailsState} ({@link MatchReadService}).
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
@Import(TestClubMappings.class)
class GameDetailsRetryIntegrationTest {

  private static final int TEAM = 7617;
  private static final int LEAGUE = 1010;
  private static final int SEASON = 28;
  private static final int GAME = 1122603;
  private static final String OWN = "מ.כ. שדרות";
  private static final String OPPONENT = "הפועל ניר רמה\"ש";
  private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
  private static final Duration RETRY = GameDetailAttempts.RETRY_GIVEN_UP_AFTER;

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private ScrapeIngestService ingestService;
  @Autowired private GameDetailsRepository detailsRepository;
  @Autowired private GameDetailAttemptsRepository attemptsRepository;
  @Autowired private LeagueFixturesSnapshotRepository fixturesRepository;
  @Autowired private LeagueStandingsSnapshotRepository standingsRepository;
  @Autowired private CupFixturesSnapshotRepository cupRepository;
  @Autowired private ClubFootballMappings mappings;
  @Autowired private ClubContext clubContext;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private TestClubMappings clubs;

  private final MovableClock clock = new MovableClock();
  private String clubId;
  private KnownGames knownGames;
  private MatchReadService readService;

  @BeforeEach
  void setUp() {
    clubId = clubs.createClub("Sderot", TEAM, LEAGUE, SEASON);
    knownGames = new KnownGames(detailsRepository, attemptsRepository, clubContext, clock);
    readService =
        new MatchReadService(
            mappings,
            fixturesRepository,
            cupRepository,
            standingsRepository,
            knownGames,
            clubContext);
    // The game, played, in the club's fixtures.
    ingestService.ingest(
        TEAM,
        LEAGUE,
        SEASON,
        new ScrapedFixtures(
            T0,
            List.of(
                new ScrapedFixtures.Game(
                    GAME,
                    LocalDate.of(2026, 9, 19),
                    LocalTime.of(18, 0),
                    OPPONENT,
                    OWN,
                    null,
                    1,
                    1),
                // Unplayed, so our team is the one name in every game.
                new ScrapedFixtures.Game(
                    null,
                    LocalDate.of(2026, 10, 3),
                    null,
                    OWN,
                    "הפ' חדרה ש. שוורץ",
                    null,
                    null,
                    null))),
        null,
        null,
        null,
        null);
  }

  @AfterEach
  void tearDown() {
    clubContext.clear();
    for (String collection : List.of("league_fixtures", "game_details", "game_detail_attempts")) {
      mongoTemplate.getCollection(collection).deleteMany(new Document());
    }
    clubs.deleteAllClubs();
  }

  @Test
  void aGivenUpGameIsRetriedThreeDaysAfterItsLastCountedFailure() {
    Instant third = fail(1, "PARSE_ERROR", 2, "PARSE_ERROR", 3, "PARSE_ERROR");

    clock.set(third.plus(RETRY).minusSeconds(1));
    assertGivenUp(true);

    clock.set(third.plus(RETRY).plusSeconds(1));
    assertGivenUp(false);
  }

  @Test
  void aCountedFailureAfterTheRetryGivesItUpForAnotherThreeDays() {
    Instant third = fail(1, "PARSE_ERROR", 2, "PARSE_ERROR", 3, "PARSE_ERROR");
    Instant fourth = third.plus(RETRY).plusSeconds(60);
    clock.set(fourth.plusSeconds(1));

    failAt(fourth, "SOURCE_ERROR");

    assertThat(attempts().getFailedAttempts()).isEqualTo(4);
    assertThat(attempts().getLastCountedAttemptAt()).isEqualTo(fourth);
    assertGivenUp(true);
    clock.set(fourth.plus(RETRY).minusSeconds(1));
    assertGivenUp(true);
    clock.set(fourth.plus(RETRY).plusSeconds(1));
    assertGivenUp(false);
  }

  @Test
  void aTimeoutOnTheRetryDoesntExtendTheGiveUp() {
    Instant third = fail(1, "PARSE_ERROR", 2, "PARSE_ERROR", 3, "PARSE_ERROR");
    Instant retry = third.plus(RETRY).plusSeconds(60);
    clock.set(retry.plusSeconds(1));

    failAt(retry, "TIMEOUT");

    GameDetailAttempts attempts = attempts();
    assertThat(attempts.getFailedAttempts()).isEqualTo(3);
    assertThat(attempts.getLastCountedAttemptAt()).isEqualTo(third);
    assertThat(attempts.getLastAttemptAt()).isEqualTo(retry);
    assertThat(attempts.getLastReason()).isEqualTo(ScrapedGameDetails.FailureReason.TIMEOUT);
    assertGivenUp(false);
  }

  @Test
  void aSuccessfulRetryStoresTheGameAndDeletesItsAttempts() {
    Instant third = fail(1, "PARSE_ERROR", 2, "PARSE_ERROR", 3, "PARSE_ERROR");
    Instant retry = third.plus(RETRY).plusSeconds(60);
    clock.set(retry.plusSeconds(1));

    ingestService.ingest(
        TEAM,
        LEAGUE,
        SEASON,
        null,
        null,
        null,
        new ScrapedGameDetails(retry, List.of(game()), List.of()),
        null);

    clubContext.setClubId(clubId);
    assertThat(attemptsRepository.findAll()).isEmpty();
    assertThat(knownGames.knownGameIds(TEAM, SEASON)).containsExactly(GAME);
    FixturesResponse.Game game = playedGame();
    assertThat(game.detailsState()).isEqualTo(FixturesResponse.DetailsState.AVAILABLE);
    assertThat(game.detailsId()).isNotNull();
    clubContext.clear();
  }

  /** Written before the retry rule (no {@code lastCountedAttemptAt}): never given up. */
  @Test
  void aRecordWithoutALastCountedFailureIsNotGivenUp() {
    mongoTemplate
        .getCollection("game_detail_attempts")
        .insertOne(
            new Document("clubId", clubId)
                .append("teamId", TEAM)
                .append("seasonId", SEASON)
                .append("gameId", GAME)
                .append("failedAttempts", 5)
                .append("lastAttemptAt", java.util.Date.from(T0))
                .append("version", 0L));
    clock.set(T0.plusSeconds(1));

    assertGivenUp(false);
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** Failures at {@code T0 + n} seconds, as {@code (n, reason)} pairs; the last one's instant. */
  private Instant fail(Object... secondsAndReasons) {
    Instant last = null;
    for (int i = 0; i < secondsAndReasons.length; i += 2) {
      last = T0.plusSeconds((Integer) secondsAndReasons[i]);
      failAt(last, (String) secondsAndReasons[i + 1]);
    }
    return last;
  }

  private void failAt(Instant fetchedAt, String reason) {
    ingestService.ingest(
        TEAM,
        LEAGUE,
        SEASON,
        null,
        null,
        null,
        new ScrapedGameDetails(
            fetchedAt,
            List.of(),
            List.of(
                new ScrapedGameDetails.Failure(
                    GAME, ScrapedGameDetails.FailureReason.valueOf(reason), null))),
        null);
  }

  private void assertGivenUp(boolean givenUp) {
    clubContext.setClubId(clubId);
    try {
      assertThat(knownGames.knownGameIds(TEAM, SEASON))
          .as("known games at %s", clock.instant())
          .isEqualTo(givenUp ? List.of(GAME) : List.of());
      assertThat(playedGame().detailsState())
          .as("detailsState at %s", clock.instant())
          .isEqualTo(
              givenUp
                  ? FixturesResponse.DetailsState.UNAVAILABLE
                  : FixturesResponse.DetailsState.PENDING);
    } finally {
      clubContext.clear();
    }
  }

  /** The played game on the fixtures (in the current club's context). */
  private FixturesResponse.Game playedGame() {
    return readService.fixtures().games().stream()
        .filter(game -> game.result() != null)
        .findFirst()
        .orElseThrow();
  }

  private GameDetailAttempts attempts() {
    return clubContext.callAs(
        clubId,
        () ->
            attemptsRepository
                .findByClubIdAndTeamIdAndSeasonIdAndGameId(clubId, TEAM, SEASON, GAME)
                .orElseThrow());
  }

  private static ScrapedGameDetails.Game game() {
    return new ScrapedGameDetails.Game(
        GAME,
        LocalDate.of(2026, 9, 19),
        LocalTime.of(18, 0),
        "ליגה א' דרום",
        3,
        FixturesResponse.Side.AWAY,
        OPPONENT,
        OWN,
        1,
        1,
        List.of(
            new ScrapedGameDetails.Appearance(
                920001, "עומר גבאי", 1, ScrapedGameDetails.Role.STARTER, true, true, List.of())));
  }

  /** A clock the test sets. */
  private static final class MovableClock extends Clock {

    private Instant now = T0;

    void set(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
