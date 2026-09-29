package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.CrossClubAccessException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Proves {@link PlayerRepository} against a real MongoDB (see docs/spec.md section 11), through the
 * real club-scoped repository: club isolation for {@link Player}, the partial unique jersey-number
 * index, optimistic locking, and how Spring Data actually persists a player (auditing, defaults,
 * {@code null} fields, no validation on save).
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
class PlayerRepositoryIntegrationTest {

  private static final String CLUB_A = "club-a";
  private static final String CLUB_B = "club-b";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private PlayerRepository playerRepository;
  @Autowired private ClubContext clubContext;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private ApplicationContext applicationContext;

  @AfterEach
  void tearDown() {
    clubContext.clear();
    // Remove documents rather than dropping the collection: dropping would also drop the jersey
    // index created at startup, silently disabling it for every test that runs afterwards.
    mongoTemplate.remove(new Query(), Player.class);
  }

  /**
   * The squad API race-test hooks are only {@code @Import}ed by {@code PlayerApiIntegrationTest};
   * this context, which doesn't import them, must not contain them.
   */
  @Test
  void theRaceTestHooksAreNotRegisteredInOtherContexts() {
    assertThat(applicationContext.getBeanNamesForType(PlayerLoadHook.class)).isEmpty();
    assertThat(applicationContext.getBeanNamesForType(PlayerInsertBarrier.class)).isEmpty();
  }

  // --- saving ------------------------------------------------------------------------------------

  @Test
  void saveStampsClubIdDefaultsAndAuditTimestamps() {
    Player saved = saveAs(CLUB_A, newPlayer("Eran Zahavi", 7));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getClubId()).isEqualTo(CLUB_A);
    assertThat(saved.getMedicalStatus()).isEqualTo(MedicalStatus.FIT);
    assertThat(saved.isActive()).isTrue();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
    Document stored = rawPlayer(saved.getId());
    assertThat(stored.get("clubId")).isEqualTo(CLUB_A);
    assertThat(stored.get("medicalStatus")).isEqualTo("FIT");
    assertThat(stored.get("active")).isEqualTo(true);
    assertThat(stored.get("fullName")).isEqualTo("Eran Zahavi");
  }

  /** Verify 4: a new player ({@code version == null}) is inserted with version 0. */
  @Test
  void saveInsertsANewPlayerWithVersionZero() {
    Player player = newPlayer("Eran Zahavi", 7);
    assertThat(player.getVersion()).isNull();

    Player saved = saveAs(CLUB_A, player);

    assertThat(saved.getVersion()).isZero();
    assertThat(rawPlayer(saved.getId()).get("version")).isEqualTo(0L);
  }

  @Test
  void anUpdateIncrementsTheVersionAndKeepsCreatedAt() {
    Player saved = saveAs(CLUB_A, newPlayer("Eran Zahavi", 7));
    Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(saved.getId())),
        new Update().set("createdAt", longAgo).set("updatedAt", longAgo),
        "players");
    Player loaded = playerRepository.findById(saved.getId()).orElseThrow();
    loaded.setMedicalStatus(MedicalStatus.INJURED);

    Player updated = playerRepository.save(loaded);

    assertThat(updated.getVersion()).isEqualTo(1L);
    Player stored = mongoTemplate.findById(saved.getId(), Player.class);
    assertThat(stored.getVersion()).isEqualTo(1L);
    assertThat(stored.getMedicalStatus()).isEqualTo(MedicalStatus.INJURED);
    assertThat(stored.getCreatedAt()).isEqualTo(longAgo);
    assertThat(stored.getUpdatedAt()).isAfter(longAgo);
  }

  /** Verify 2: Spring Data omits a {@code null} field on write rather than storing {@code null}. */
  @Test
  void aNullJerseyNumberIsNotWrittenAtAll() {
    Player saved = saveAs(CLUB_A, newPlayer("No Number", null));

    assertThat(rawPlayer(saved.getId())).doesNotContainKey("jerseyNumber");
  }

  /** An update replaces the whole document, so clearing a number removes the stored field. */
  @Test
  void clearingAJerseyNumberRemovesTheFieldAndFreesTheNumber() {
    Player saved = saveAs(CLUB_A, newPlayer("Eran Zahavi", 7));
    Player loaded = playerRepository.findById(saved.getId()).orElseThrow();
    loaded.setJerseyNumber(null);

    playerRepository.save(loaded);

    assertThat(rawPlayer(saved.getId())).doesNotContainKey("jerseyNumber");
    assertThat(saveAs(CLUB_A, newPlayer("New Seven", 7)).getJerseyNumber()).isEqualTo(7);
  }

  /**
   * Verify 3: Bean Validation does not run on save — nothing registers a validating entity
   * callback, so the repository stores an invalid player as-is. Constraints must be checked on the
   * request. If persistence-level validation is ever enabled, this test fails on purpose.
   */
  @Test
  void theRepositoryDoesNotRunBeanValidation() {
    Player invalid = newPlayer("   ", 0);
    invalid.setPrimaryPosition(null);
    invalid.setHeightCm(500);

    Player saved = saveAs(CLUB_A, invalid);

    Document stored = rawPlayer(saved.getId());
    assertThat(stored.get("fullName")).isEqualTo("");
    assertThat(stored.get("jerseyNumber")).isEqualTo(0);
    assertThat(stored.get("heightCm")).isEqualTo(500);
    assertThat(stored).doesNotContainKey("primaryPosition");
  }

  // --- club isolation ----------------------------------------------------------------------------

  @Test
  void anotherClubCannotReadAPlayer() {
    Player clubAPlayer = saveAs(CLUB_A, newPlayer("Eran Zahavi", 7));

    clubContext.setClubId(CLUB_B);

    assertThat(playerRepository.findById(clubAPlayer.getId())).isEmpty();
    assertThat(playerRepository.existsById(clubAPlayer.getId())).isFalse();
    assertThat(playerRepository.findAll()).isEmpty();
    assertThat(playerRepository.count()).isZero();
  }

  @Test
  void eachClubOnlySeesItsOwnPlayers() {
    saveAs(CLUB_A, newPlayer("A One", 1));
    saveAs(CLUB_A, newPlayer("A Two", 2));
    saveAs(CLUB_B, newPlayer("B One", 1));

    clubContext.setClubId(CLUB_A);

    assertThat(playerRepository.findAll())
        .extracting(Player::getFullName)
        .containsExactlyInAnyOrder("A One", "A Two");
    assertThat(playerRepository.count()).isEqualTo(2);
  }

  @Test
  void anotherClubCannotUpdateAPlayer() {
    Player clubAPlayer = saveAs(CLUB_A, newPlayer("Eran Zahavi", 7));
    Document before = rawPlayer(clubAPlayer.getId());
    Player loaded = playerRepository.findById(clubAPlayer.getId()).orElseThrow();
    loaded.setFullName("Hijacked");
    loaded.setClubId(null);

    clubContext.setClubId(CLUB_B);

    assertThatThrownBy(() -> playerRepository.save(loaded))
        .isInstanceOf(CrossClubAccessException.class);
    assertThat(rawPlayer(clubAPlayer.getId())).isEqualTo(before);
  }

  /** A save with A's id but no version is an insert attempt — still rejected by the club check. */
  @Test
  void anotherClubCannotOverwriteAPlayerWithAFreshObjectCarryingItsId() {
    Player clubAPlayer = saveAs(CLUB_A, newPlayer("Eran Zahavi", 7));
    Document before = rawPlayer(clubAPlayer.getId());
    Player forged = newPlayer("Hijacked", 9);
    forged.setId(clubAPlayer.getId());
    forged.setClubId(CLUB_B);

    clubContext.setClubId(CLUB_B);

    assertThatThrownBy(() -> playerRepository.save(forged))
        .isInstanceOf(CrossClubAccessException.class);
    assertThat(rawPlayer(clubAPlayer.getId())).isEqualTo(before);
  }

  /** Cross-club deletes are silent no-ops, as for every club-scoped repository. */
  @Test
  void anotherClubCannotDeleteAPlayer() {
    Player clubAPlayer = saveAs(CLUB_A, newPlayer("Eran Zahavi", 7));
    Document before = rawPlayer(clubAPlayer.getId());

    clubContext.setClubId(CLUB_B);
    playerRepository.deleteById(clubAPlayer.getId());
    playerRepository.delete(clubAPlayer);

    assertThat(rawPlayer(clubAPlayer.getId())).isEqualTo(before);
  }

  @Test
  void aClubCanPermanentlyDeleteItsOwnPlayer() {
    Player saved = saveAs(CLUB_A, newPlayer("Created By Mistake", 7));

    playerRepository.deleteById(saved.getId());

    assertThat(rawPlayer(saved.getId())).isNull();
  }

  // --- jersey number index -----------------------------------------------------------------------

  /** Verify 1: the annotation creates the index with its name, uniqueness and partial filter. */
  @Test
  void theJerseyIndexIsUniqueAndPartial() {
    IndexInfo index =
        mongoTemplate.indexOps(Player.class).getIndexInfo().stream()
            .filter(info -> info.getName().equals(Player.JERSEY_NUMBER_INDEX))
            .findFirst()
            .orElseThrow();

    assertThat(index.getName()).isEqualTo("clubId_jerseyNumber_active_unique");
    assertThat(index.isUnique()).isTrue();
    assertThat(index.isSparse()).isFalse();
    assertThat(index.isIndexForFields(List.of("clubId", "jerseyNumber"))).isTrue();
    assertThat(Document.parse(index.getPartialFilterExpression()))
        .isEqualTo(
            new Document("jerseyNumber", new Document("$type", "number")).append("active", true));
  }

  @Test
  void twoActivePlayersInTheSameClubCannotShareANumber() {
    saveAs(CLUB_A, newPlayer("First Seven", 7));

    assertThatThrownBy(() -> saveAs(CLUB_A, newPlayer("Second Seven", 7)))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining(Player.JERSEY_NUMBER_INDEX);
    clubContext.setClubId(CLUB_A);
    assertThat(playerRepository.count()).isEqualTo(1);
  }

  @Test
  void changingANumberToOneAlreadyTakenFails() {
    saveAs(CLUB_A, newPlayer("Seven", 7));
    Player nine = saveAs(CLUB_A, newPlayer("Nine", 9));
    Player loaded = playerRepository.findById(nine.getId()).orElseThrow();
    loaded.setJerseyNumber(7);

    assertThatThrownBy(() -> playerRepository.save(loaded))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining(Player.JERSEY_NUMBER_INDEX);
    assertThat(rawPlayer(nine.getId()).get("jerseyNumber")).isEqualTo(9);
  }

  @Test
  void theSameNumberInDifferentClubsIsAllowed() {
    saveAs(CLUB_A, newPlayer("A Seven", 7));

    assertThat(saveAs(CLUB_B, newPlayer("B Seven", 7)).getId()).isNotNull();
  }

  @Test
  void aReleasedPlayerDoesNotBlockTheirNumber() {
    Player released = newPlayer("Released Seven", 7);
    released.setActive(false);
    saveAs(CLUB_A, released);

    assertThat(saveAs(CLUB_A, newPlayer("Active Seven", 7)).getId()).isNotNull();
  }

  @Test
  void releasingAPlayerFreesTheirNumber() {
    Player seven = saveAs(CLUB_A, newPlayer("Old Seven", 7));
    Player loaded = playerRepository.findById(seven.getId()).orElseThrow();
    loaded.setActive(false);
    playerRepository.save(loaded);

    Player newSeven = saveAs(CLUB_A, newPlayer("New Seven", 7));

    assertThat(newSeven.getId()).isNotNull();
    assertThat(rawPlayer(seven.getId()).get("jerseyNumber")).isEqualTo(7);
  }

  @Test
  void reactivatingAPlayerWhoseNumberWasTakenFails() {
    Player seven = saveAs(CLUB_A, newPlayer("Old Seven", 7));
    Player loaded = playerRepository.findById(seven.getId()).orElseThrow();
    loaded.setActive(false);
    Player released = playerRepository.save(loaded);
    saveAs(CLUB_A, newPlayer("New Seven", 7));
    released.setActive(true);

    assertThatThrownBy(() -> playerRepository.save(released))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining(Player.JERSEY_NUMBER_INDEX);
    assertThat(rawPlayer(seven.getId()).get("active")).isEqualTo(false);
  }

  @Test
  void severalPlayersWithoutANumberDoNotCollide() {
    saveAs(CLUB_A, newPlayer("No Number One", null));
    saveAs(CLUB_A, newPlayer("No Number Two", null));
    insertRawPlayer(CLUB_A, "Explicit Null One");
    insertRawPlayer(CLUB_A, "Explicit Null Two");

    clubContext.setClubId(CLUB_A);
    assertThat(playerRepository.count()).isEqualTo(4);
    assertThat(playerRepository.findAll()).extracting(Player::getJerseyNumber).containsOnlyNulls();
  }

  /**
   * Why the filter tests the type: with {@code $exists: true} instead, two documents storing an
   * explicit {@code jerseyNumber: null} would collide. Shown on a scratch collection, not {@code
   * players}.
   */
  @Test
  void anExistsFilterWouldMakeExplicitNullNumbersCollide() {
    MongoCollection<Document> probe = mongoTemplate.getCollection("players_exists_filter_probe");
    try {
      probe.createIndex(
          new Document("clubId", 1).append("jerseyNumber", 1),
          new IndexOptions()
              .unique(true)
              .partialFilterExpression(
                  new Document("jerseyNumber", new Document("$exists", true))
                      .append("active", true)));
      probe.insertOne(
          new Document("clubId", CLUB_A).append("jerseyNumber", null).append("active", true));

      assertThatThrownBy(
              () ->
                  probe.insertOne(
                      new Document("clubId", CLUB_A)
                          .append("jerseyNumber", null)
                          .append("active", true)))
          .isInstanceOf(MongoWriteException.class)
          .hasMessageContaining("E11000");
    } finally {
      probe.drop();
    }
  }

  // --- optimistic locking ------------------------------------------------------------------------

  @Test
  void aSaveFromAStaleCopyFailsAndKeepsTheFirstWrite() {
    Player saved = saveAs(CLUB_A, newPlayer("Eran Zahavi", 7));
    Player first = playerRepository.findById(saved.getId()).orElseThrow();
    Player second = playerRepository.findById(saved.getId()).orElseThrow();

    first.setMedicalStatus(MedicalStatus.INJURED);
    playerRepository.save(first);
    second.setFullName("Written from a stale copy");

    assertThatThrownBy(() -> playerRepository.save(second))
        .isInstanceOf(OptimisticLockingFailureException.class);
    Player stored = mongoTemplate.findById(saved.getId(), Player.class);
    assertThat(stored.getMedicalStatus()).isEqualTo(MedicalStatus.INJURED);
    assertThat(stored.getFullName()).isEqualTo("Eran Zahavi");
    assertThat(stored.getVersion()).isEqualTo(1L);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private Player saveAs(String clubId, Player player) {
    clubContext.setClubId(clubId);
    return playerRepository.save(player);
  }

  private static Player newPlayer(String fullName, Integer jerseyNumber) {
    Player player = new Player();
    player.setFullName(fullName);
    player.setPrimaryPosition(Position.ST);
    player.setJerseyNumber(jerseyNumber);
    player.setDateOfBirth(LocalDate.of(1995, 5, 20));
    return player;
  }

  /**
   * A player stored with an explicit {@code jerseyNumber: null}, which Spring Data never writes.
   */
  private void insertRawPlayer(String clubId, String fullName) {
    mongoTemplate
        .getCollection("players")
        .insertOne(
            new Document("_id", new ObjectId())
                .append("clubId", clubId)
                .append("fullName", fullName)
                .append("primaryPosition", Position.CB.name())
                .append("jerseyNumber", null)
                .append("dateOfBirth", Date.from(Instant.parse("1995-05-20T00:00:00Z")))
                .append("medicalStatus", MedicalStatus.FIT.name())
                .append("active", true)
                .append("version", 0L)
                .append("_class", Player.class.getName()));
  }

  private Document rawPlayer(String id) {
    return mongoTemplate
        .getCollection("players")
        .find(new Document("_id", new ObjectId(id)))
        .first();
  }
}
