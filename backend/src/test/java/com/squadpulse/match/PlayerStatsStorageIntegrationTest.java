package com.squadpulse.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.squadpulse.common.ClubContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The two KAN-72 collections on a real MongoDB: the unique indexes {@code auto-index-creation}
 * builds from the {@code @CompoundIndex} annotations exist, reject a duplicate, and surface as the
 * {@link DuplicateKeyException} (with a {@link MongoWriteException} cause naming the index) that
 * {@code ScrapeIngestService.storeIfNewer} and {@link PlayerStatsLinkService} catch.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
class PlayerStatsStorageIntegrationTest {

  private static final String CLUB_A = "club-a";
  private static final String CLUB_B = "club-b";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private PlayerSeasonStatsSnapshotRepository snapshotRepository;
  @Autowired private PlayerStatsLinkRepository linkRepository;
  @Autowired private ClubContext clubContext;
  @Autowired private MongoTemplate mongoTemplate;

  @AfterEach
  void tearDown() {
    clubContext.clear();
    mongoTemplate.remove(new Query(), PlayerSeasonStatsSnapshot.class);
    mongoTemplate.remove(new Query(), PlayerStatsLink.class);
  }

  // --- the indexes exist ------------------------------------------------------------------------

  @Test
  void theSnapshotIndexIsUniqueOnClubTeamAndSeason() {
    IndexInfo index =
        index(PlayerSeasonStatsSnapshot.class, PlayerSeasonStatsSnapshot.SNAPSHOT_INDEX);

    assertThat(index.isUnique()).isTrue();
    assertThat(index.isIndexForFields(List.of("clubId", "teamId", "seasonId"))).isTrue();
  }

  @Test
  void theLinkIndexesAreUniqueOnClubAndEachSide() {
    IndexInfo source = index(PlayerStatsLink.class, PlayerStatsLink.SOURCE_PLAYER_INDEX);
    IndexInfo player = index(PlayerStatsLink.class, PlayerStatsLink.PLAYER_INDEX);

    assertThat(source.isUnique()).isTrue();
    assertThat(source.isIndexForFields(List.of("clubId", "sourcePlayerId"))).isTrue();
    assertThat(player.isUnique()).isTrue();
    assertThat(player.isIndexForFields(List.of("clubId", "playerId"))).isTrue();
  }

  // --- and reject a duplicate -------------------------------------------------------------------

  @Test
  void aSecondSnapshotForTheSameClubTeamAndSeasonIsADuplicateKey() {
    clubContext.setClubId(CLUB_A);
    snapshotRepository.save(snapshot(28));

    DuplicateKeyException e = catchDuplicateKey(() -> snapshotRepository.save(snapshot(28)));

    assertThat(violatedIndex(e)).contains(PlayerSeasonStatsSnapshot.SNAPSHOT_INDEX);
    snapshotRepository.save(snapshot(29));
    clubContext.setClubId(CLUB_B);
    snapshotRepository.save(snapshot(28));
    assertThat(mongoTemplate.count(new Query(), PlayerSeasonStatsSnapshot.class)).isEqualTo(3);
  }

  @Test
  void aSecondLinkOfTheSameScrapedPlayerIsADuplicateKey() {
    clubContext.setClubId(CLUB_A);
    linkRepository.save(link(900001, "player-1"));

    DuplicateKeyException e =
        catchDuplicateKey(() -> linkRepository.save(link(900001, "player-2")));

    assertThat(violatedIndex(e)).contains(PlayerStatsLink.SOURCE_PLAYER_INDEX);
    clubContext.setClubId(CLUB_B);
    linkRepository.save(link(900001, "player-1"));
  }

  @Test
  void aSecondLinkOfTheSamePlayerIsADuplicateKey() {
    clubContext.setClubId(CLUB_A);
    linkRepository.save(link(900001, "player-1"));

    DuplicateKeyException e =
        catchDuplicateKey(() -> linkRepository.save(link(900002, "player-1")));

    assertThat(violatedIndex(e)).contains(PlayerStatsLink.PLAYER_INDEX);
    assertThat(mongoTemplate.count(new Query(), PlayerStatsLink.class)).isEqualTo(1);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private IndexInfo index(Class<?> entity, String name) {
    return mongoTemplate.indexOps(entity).getIndexInfo().stream()
        .filter(info -> info.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No index " + name));
  }

  private static PlayerSeasonStatsSnapshot snapshot(int seasonId) {
    PlayerSeasonStatsSnapshot snapshot = new PlayerSeasonStatsSnapshot();
    snapshot.setTeamId(7617);
    snapshot.setSeasonId(seasonId);
    snapshot.setFetchedAt(Instant.parse("2026-10-01T10:00:00Z"));
    snapshot.setStoredAt(Instant.parse("2026-10-01T10:00:01Z"));
    snapshot.setPlayers(List.of());
    return snapshot;
  }

  private static PlayerStatsLink link(int sourcePlayerId, String playerId) {
    PlayerStatsLink link = new PlayerStatsLink();
    link.setSourcePlayerId(sourcePlayerId);
    link.setPlayerId(playerId);
    link.setLinkedAt(Instant.parse("2026-10-01T10:00:00Z"));
    return link;
  }

  private static DuplicateKeyException catchDuplicateKey(Runnable write) {
    Throwable thrown = catchThrowable(write::run);
    assertThat(thrown).isInstanceOf(DuplicateKeyException.class);
    return (DuplicateKeyException) thrown;
  }

  /**
   * The index named by the driver's write error, the way {@link PlayerStatsLinkService} reads it.
   */
  private static String violatedIndex(DuplicateKeyException e) {
    assertThat(e.getCause()).isInstanceOf(MongoWriteException.class);
    MongoWriteException cause = (MongoWriteException) e.getCause();
    assertThat(cause.getError().getCategory()).isEqualTo(ErrorCategory.DUPLICATE_KEY);
    return cause.getError().getMessage();
  }
}
