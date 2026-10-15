package com.squadpulse.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.TestClubMappings;
import com.squadpulse.common.ClubContext;
import com.squadpulse.match.IngestResult.Outcome;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * {@link ScrapeIngestService} and the snapshot repositories on a real MongoDB (KAN-66): the
 * exceptions a lost write race actually throws, how the service resolves each race (made
 * deterministic by a repository proxy that writes a competing snapshot right after the service
 * loaded its own), real parallel ingests, and dates that can't shift with the JVM's time zone. The
 * HTTP side is in {@code scrapingintegration.ScrapeResultApiIntegrationTest}.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
@Import(TestClubMappings.class)
class ScrapeIngestServiceIntegrationTest {

  private static final int TEAM = 7617;
  private static final int OTHER = 7001;
  private static final int LEAGUE = 1010;
  private static final int SEASON = 28;
  private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private ScrapeIngestService service;
  @Autowired private MatchReadService readService;
  @Autowired private LeagueStandingsSnapshotRepository standingsRepository;
  @Autowired private LeagueFixturesSnapshotRepository fixturesRepository;
  @Autowired private PlayerSeasonStatsSnapshotRepository playerStatsRepository;
  @Autowired private GameDetailsRepository gameDetailsRepository;
  @Autowired private GameDetailAttemptsRepository attemptsRepository;
  @Autowired private ClubFootballMappings mappings;
  @Autowired private ClubContext clubContext;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private TestClubMappings clubs;

  private String clubId;

  @BeforeEach
  void setUp() {
    clubId = clubs.createClub("Sderot", TEAM, LEAGUE, SEASON);
  }

  @AfterEach
  void tearDown() {
    clubContext.clear();
    mongoTemplate.getCollection("league_fixtures").deleteMany(new Document());
    mongoTemplate.getCollection("league_standings").deleteMany(new Document());
    clubs.deleteAllClubs();
  }

  // --- what the database actually throws ---------------------------------------------------------

  @Test
  void aSecondFirstInsertIsADuplicateKeyAndAStaleSaveAnOptimisticLockFailure() {
    standingsRepository.save(standingsSnapshot(T0));
    assertThatThrownBy(() -> standingsRepository.save(standingsSnapshot(T0)))
        .isInstanceOf(DuplicateKeyException.class);

    LeagueStandingsSnapshot first =
        standingsRepository.findByLeagueIdAndSeasonId(LEAGUE, SEASON).orElseThrow();
    LeagueStandingsSnapshot second =
        standingsRepository.findByLeagueIdAndSeasonId(LEAGUE, SEASON).orElseThrow();
    standingsRepository.save(first);
    assertThatThrownBy(() -> standingsRepository.save(second))
        .isInstanceOf(OptimisticLockingFailureException.class);

    clubContext.setClubId(clubId);
    fixturesRepository.save(fixturesSnapshot(T0));
    assertThatThrownBy(() -> fixturesRepository.save(fixturesSnapshot(T0)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  // --- races, resolved
  // -----------------------------------------------------------------------------

  @Test
  void aNewerScrapeWinsAFirstInsertRace() {
    ScrapeIngestService racing = racingStandings(T0.plusSeconds(10));

    IngestResult result =
        racing.ingest(TEAM, LEAGUE, SEASON, null, standings(T0.plusSeconds(20)), null, null);

    assertThat(result.standings().outcome()).isEqualTo(Outcome.STORED);
    assertThat(storedStandings().getFetchedAt()).isEqualTo(T0.plusSeconds(20));
  }

  @Test
  void anOlderScrapeLosingAFirstInsertRaceIsStale() {
    ScrapeIngestService racing = racingStandings(T0.plusSeconds(20));

    IngestResult result =
        racing.ingest(TEAM, LEAGUE, SEASON, null, standings(T0.plusSeconds(10)), null, null);

    assertThat(result.standings().outcome()).isEqualTo(Outcome.STALE);
    assertThat(storedStandings().getFetchedAt()).isEqualTo(T0.plusSeconds(20));
  }

  @Test
  void anUpdateRaceIsReReadAndComparedAgain() {
    standingsRepository.save(standingsSnapshot(T0));

    IngestResult newer =
        racingStandings(T0.plusSeconds(10))
            .ingest(TEAM, LEAGUE, SEASON, null, standings(T0.plusSeconds(20)), null, null);
    assertThat(newer.standings().outcome()).isEqualTo(Outcome.STORED);
    assertThat(storedStandings().getFetchedAt()).isEqualTo(T0.plusSeconds(20));

    IngestResult older =
        racingStandings(T0.plusSeconds(40))
            .ingest(TEAM, LEAGUE, SEASON, null, standings(T0.plusSeconds(30)), null, null);
    assertThat(older.standings().outcome()).isEqualTo(Outcome.STALE);
    assertThat(storedStandings().getFetchedAt()).isEqualTo(T0.plusSeconds(40));
  }

  @Test
  void aFixturesInsertRaceIsResolvedTheSameWay() {
    LeagueFixturesSnapshotRepository racingRepository =
        afterFirstLoad(
            LeagueFixturesSnapshotRepository.class,
            fixturesRepository,
            "findByClubIdAndTeamIdAndSeasonId",
            () -> fixturesRepository.save(fixturesSnapshot(T0.plusSeconds(20))));
    ScrapeIngestService racing =
        new ScrapeIngestService(
            standingsRepository,
            racingRepository,
            playerStatsRepository,
            gameDetailsRepository,
            attemptsRepository,
            mappings,
            clubContext);

    IngestResult result =
        racing.ingest(TEAM, LEAGUE, SEASON, fixtures(T0.plusSeconds(10)), null, null, null);

    assertThat(result.fixtures().outcome()).isEqualTo(Outcome.STALE);
    assertThat(result.clubsUpdated()).isZero();
    clubContext.setClubId(clubId);
    assertThat(
            fixturesRepository
                .findByClubIdAndTeamIdAndSeasonId(clubId, TEAM, SEASON)
                .orElseThrow()
                .getFetchedAt())
        .isEqualTo(T0.plusSeconds(20));
  }

  @Test
  void inParallelTheNewerScrapeAlwaysWins() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 10; round++) {
        tearDownSnapshots();
        Instant older = T0.plusSeconds(round * 100L);
        Instant newer = older.plusSeconds(50);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestResult>> results = new ArrayList<>();
        for (Instant fetchedAt : round % 2 == 0 ? List.of(older, newer) : List.of(newer, older)) {
          results.add(
              executor.submit(
                  () -> {
                    start.await();
                    return service.ingest(
                        TEAM,
                        LEAGUE,
                        SEASON,
                        fixtures(fetchedAt),
                        standings(fetchedAt),
                        null,
                        null);
                  }));
        }
        start.countDown();
        for (Future<IngestResult> result : results) {
          assertThat(result.get().standings().outcome()).isIn(Outcome.STORED, Outcome.STALE);
          assertThat(result.get().fixtures().outcome()).isIn(Outcome.STORED, Outcome.STALE);
        }
        assertThat(storedStandings().getFetchedAt()).isEqualTo(newer);
        clubContext.setClubId(clubId);
        assertThat(
                fixturesRepository
                    .findByClubIdAndTeamIdAndSeasonId(clubId, TEAM, SEASON)
                    .orElseThrow()
                    .getFetchedAt())
            .isEqualTo(newer);
        clubContext.clear();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  // --- dates -------------------------------------------------------------------------------------

  /**
   * Spring Data's own {@code LocalDate} conversion goes through the JVM's default zone (to a BSON
   * date at local midnight), so a server outside Israel would store another instant — and, read
   * elsewhere, possibly another day. The games store ISO strings instead, which can't shift.
   */
  @Test
  void datesAndTimesAreStoredAsIsraelLocalStringsWhateverTheJvmZone() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
    try {
      assertThat(mongoTemplate.getConverter().convertToMongoType(LocalDate.of(2026, 9, 5)))
          .isEqualTo(java.util.Date.from(Instant.parse("2026-09-05T04:00:00Z")));

      service.ingest(TEAM, LEAGUE, SEASON, fixtures(T0), null, null, null);

      Document stored = mongoTemplate.getCollection("league_fixtures").find().first();
      Document game = stored.getList("games", Document.class).get(0);
      assertThat(game.get("date")).isEqualTo("2026-09-05");
      assertThat(game.get("kickoffTime")).isEqualTo("23:30");
      FixturesResponse read = clubContext.callAs(clubId, readService::fixtures);
      assertThat(read.games().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 5));
      assertThat(read.games().get(0).kickoffTime()).isEqualTo(LocalTime.of(23, 30));
    } finally {
      TimeZone.setDefault(original);
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  /**
   * A service whose standings repository writes a competing snapshot right after its first load.
   */
  private ScrapeIngestService racingStandings(Instant competitorFetchedAt) {
    LeagueStandingsSnapshotRepository racingRepository =
        afterFirstLoad(
            LeagueStandingsSnapshotRepository.class,
            standingsRepository,
            "findByLeagueIdAndSeasonId",
            () -> {
              LeagueStandingsSnapshot competitor =
                  standingsRepository
                      .findByLeagueIdAndSeasonId(LEAGUE, SEASON)
                      .orElseGet(() -> standingsSnapshot(competitorFetchedAt));
              competitor.setFetchedAt(competitorFetchedAt);
              standingsRepository.save(competitor);
            });
    return new ScrapeIngestService(
        racingRepository,
        fixturesRepository,
        playerStatsRepository,
        gameDetailsRepository,
        attemptsRepository,
        mappings,
        clubContext);
  }

  private static <R> R afterFirstLoad(
      Class<R> type, R real, String loadMethod, Runnable concurrentWrite) {
    boolean[] fired = {false};
    InvocationHandler handler =
        (proxy, method, args) -> {
          Object result;
          try {
            result = method.invoke(real, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
          if (method.getName().equals(loadMethod) && !fired[0]) {
            fired[0] = true;
            concurrentWrite.run();
          }
          return result;
        };
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  private void tearDownSnapshots() {
    mongoTemplate.getCollection("league_fixtures").deleteMany(new Document());
    mongoTemplate.getCollection("league_standings").deleteMany(new Document());
  }

  private LeagueStandingsSnapshot storedStandings() {
    return standingsRepository.findByLeagueIdAndSeasonId(LEAGUE, SEASON).orElseThrow();
  }

  private static ScrapedStandings standings(Instant fetchedAt) {
    return new ScrapedStandings(
        fetchedAt,
        1,
        List.of(
            new ScrapedStandings.Row(1, OTHER, "Tira", 1, 1, 0, 0, 2, 1, 3),
            new ScrapedStandings.Row(2, TEAM, "Sderot", 1, 0, 0, 1, 1, 2, 0)));
  }

  private static ScrapedFixtures fixtures(Instant fetchedAt) {
    return new ScrapedFixtures(
        fetchedAt,
        List.of(
            new ScrapedFixtures.Game(
                1, LocalDate.of(2026, 9, 5), LocalTime.of(23, 30), "Tira", "Sderot", null, 2, 1),
            new ScrapedFixtures.Game(
                null, LocalDate.of(2026, 10, 1), null, "Sderot", "Tira", null, null, null),
            new ScrapedFixtures.Game(
                null, LocalDate.of(2026, 10, 8), null, "Hadera", "Sderot", null, null, null)));
  }

  private static LeagueStandingsSnapshot standingsSnapshot(Instant fetchedAt) {
    LeagueStandingsSnapshot snapshot = new LeagueStandingsSnapshot();
    snapshot.setLeagueId(LEAGUE);
    snapshot.setSeasonId(SEASON);
    snapshot.setRound(1);
    snapshot.setFetchedAt(fetchedAt);
    snapshot.setStoredAt(fetchedAt);
    snapshot.setRows(List.of());
    return snapshot;
  }

  private static LeagueFixturesSnapshot fixturesSnapshot(Instant fetchedAt) {
    LeagueFixturesSnapshot snapshot = new LeagueFixturesSnapshot();
    snapshot.setTeamId(TEAM);
    snapshot.setLeagueId(LEAGUE);
    snapshot.setSeasonId(SEASON);
    snapshot.setTeamName("Sderot");
    snapshot.setFetchedAt(fetchedAt);
    snapshot.setStoredAt(fetchedAt);
    snapshot.setGames(List.of());
    return snapshot;
  }
}
