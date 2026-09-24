package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Proves the real Spring Data query-building behavior against a real MongoDB (see docs/spec.md
 * section 03, section 11): club A can never see, fetch, or delete club B's records through the
 * scoped repository layer, and vice versa.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
class ClubScopedRepositoryImplIntegrationTest {

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private TestClubScopedEntityRepository repository;
  @Autowired private ClubContext clubContext;
  @Autowired private MongoTemplate mongoTemplate;

  @AfterEach
  void tearDown() {
    clubContext.clear();
    mongoTemplate.dropCollection(TestClubScopedEntity.class);
  }

  @Test
  void findAllNeverReturnsAnotherClubsRecords() {
    insertAs("club-a", "a1");
    insertAs("club-a", "a2");
    insertAs("club-b", "b1");

    clubContext.setClubId("club-a");

    assertThat(repository.findAll())
        .extracting(TestClubScopedEntity::getName)
        .containsExactlyInAnyOrder("a1", "a2");
  }

  @Test
  void findByIdOnAnotherClubsRecordReturnsEmptyRatherThanTheRecord() {
    TestClubScopedEntity clubBRecord = insertAs("club-b", "b1");

    clubContext.setClubId("club-a");

    assertThat(repository.findById(clubBRecord.getId())).isEmpty();
  }

  @Test
  void findByIdOnOwnClubsRecordReturnsIt() {
    TestClubScopedEntity clubARecord = insertAs("club-a", "a1");

    clubContext.setClubId("club-a");

    assertThat(repository.findById(clubARecord.getId()))
        .hasValueSatisfying(found -> assertThat(found.getName()).isEqualTo("a1"));
  }

  @Test
  void existsByIdOnAnotherClubsRecordReturnsFalse() {
    TestClubScopedEntity clubBRecord = insertAs("club-b", "b1");

    clubContext.setClubId("club-a");

    assertThat(repository.existsById(clubBRecord.getId())).isFalse();
  }

  @Test
  void deleteByIdNeverDeletesAnotherClubsRecord() {
    TestClubScopedEntity clubBRecord = insertAs("club-b", "b1");
    clubContext.setClubId("club-a");

    repository.deleteById(clubBRecord.getId());

    clubContext.setClubId("club-b");
    assertThat(repository.findById(clubBRecord.getId())).isPresent();
  }

  @Test
  void countOnlyCountsTheCurrentClubsRecords() {
    insertAs("club-a", "a1");
    insertAs("club-b", "b1");
    insertAs("club-b", "b2");

    clubContext.setClubId("club-b");

    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void saveAutoStampsClubIdFromContextWhenNotAlreadySet() {
    clubContext.setClubId("club-a");

    TestClubScopedEntity saved = repository.save(new TestClubScopedEntity("unstamped"));

    assertThat(saved.getClubId()).isEqualTo("club-a");
  }

  @Test
  void saveRejectsAnEntityAlreadyTaggedForAnotherClub() {
    clubContext.setClubId("club-a");
    TestClubScopedEntity crossClubEntity = new TestClubScopedEntity("cross-club");
    crossClubEntity.setClubId("club-b");

    assertThatThrownBy(() -> repository.save(crossClubEntity))
        .isInstanceOf(CrossClubAccessException.class);
  }

  @Test
  void findAllThrowsRatherThanReturningEveryClubsDataWhenContextIsUnset() {
    insertAs("club-a", "a1");
    insertAs("club-b", "b1");

    assertThatThrownBy(() -> repository.findAll()).isInstanceOf(MissingClubContextException.class);
  }

  @Test
  void saveRejectsOverwritingAnotherClubsDocumentEvenWithNoClubIdOnTheIncomingEntity() {
    insertWithId("shared-id", "club-b", "original");
    clubContext.setClubId("club-a");

    TestClubScopedEntity forgedUpdate = new TestClubScopedEntity("shared-id", "overwritten");
    // clubId deliberately left null: nothing on the in-memory object itself claims club B.

    assertThatThrownBy(() -> repository.save(forgedUpdate))
        .isInstanceOf(CrossClubAccessException.class);
    assertThat(mongoTemplate.findById("shared-id", TestClubScopedEntity.class).getName())
        .isEqualTo("original");
  }

  @Test
  void saveRejectsOverwritingAnotherClubsDocumentEvenWhenClubIdIsForgedToTheCallersOwnClub() {
    insertWithId("shared-id", "club-b", "original");
    clubContext.setClubId("club-a");

    TestClubScopedEntity forgedUpdate = new TestClubScopedEntity("shared-id", "overwritten");
    forgedUpdate.setClubId("club-a");

    assertThatThrownBy(() -> repository.save(forgedUpdate))
        .isInstanceOf(CrossClubAccessException.class);
    assertThat(mongoTemplate.findById("shared-id", TestClubScopedEntity.class).getName())
        .isEqualTo("original");
  }

  @Test
  void saveAllowsAGenuineUpdateOfTheCallersOwnDocument() {
    insertWithId("own-id", "club-a", "before");
    clubContext.setClubId("club-a");

    TestClubScopedEntity update = new TestClubScopedEntity("own-id", "after");

    repository.save(update);

    assertThat(mongoTemplate.findById("own-id", TestClubScopedEntity.class).getName())
        .isEqualTo("after");
  }

  @Test
  void saveAllowsAGenuineNewInsertWithAClientSuppliedId() {
    clubContext.setClubId("club-a");

    TestClubScopedEntity inserted =
        repository.save(new TestClubScopedEntity("brand-new-id", "fresh"));

    assertThat(inserted.getClubId()).isEqualTo("club-a");
    assertThat(mongoTemplate.findById("brand-new-id", TestClubScopedEntity.class).getName())
        .isEqualTo("fresh");
  }

  private TestClubScopedEntity insertAs(String clubId, String name) {
    TestClubScopedEntity entity = new TestClubScopedEntity(name);
    entity.setClubId(clubId);
    return mongoTemplate.save(entity);
  }

  private TestClubScopedEntity insertWithId(String id, String clubId, String name) {
    TestClubScopedEntity entity = new TestClubScopedEntity(id, name);
    entity.setClubId(clubId);
    return mongoTemplate.save(entity);
  }
}
