package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squadpulse.common.ClubContext;
import com.squadpulse.common.CrossClubAccessException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Proves {@link UserRepository} against a real MongoDB (see docs/spec.md section 11): the global
 * email uniqueness constraint, the deliberately unscoped {@code findByEmail} lookup used by login,
 * and that the standard club-scoped CRUD wiring from {@code ClubScopedRepositoryImpl} applies to
 * {@link User}. The full isolation guarantee itself is covered by {@code
 * ClubScopedRepositoryImplIntegrationTest}.
 *
 * <p>Also proves {@link User}'s optimistic locking (KAN-24) through the real club-scoped
 * repository, and the {@link UserVersionBackfill} for documents stored before it.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
class UserRepositoryIntegrationTest {

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private UserRepository userRepository;
  @Autowired private ClubContext clubContext;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private UserVersionBackfill backfill;

  @AfterEach
  void tearDown() {
    clubContext.clear();
    // Remove documents rather than dropping the collection: dropping would also drop the unique
    // email index created at startup, silently disabling it for every test that runs afterwards.
    mongoTemplate.remove(new Query(), User.class);
  }

  @Test
  void rejectsTheSameEmailInADifferentClub() {
    saveAs("club-a", "coach@example.com");

    assertThatThrownBy(() -> saveAs("club-b", "coach@example.com"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void rejectsTheSameEmailDifferingOnlyInCaseOrWhitespace() {
    saveAs("club-a", "coach@example.com");

    assertThatThrownBy(() -> saveAs("club-b", " Coach@Example.com "))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void findByEmailWorksWithNoClubContextSet() {
    User saved = saveAs("club-b", "manager@example.com");
    clubContext.clear();

    assertThat(clubContext.getClubId()).isEmpty();
    assertThat(userRepository.findByEmail("manager@example.com"))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getId()).isEqualTo(saved.getId());
              assertThat(found.getClubId()).isEqualTo("club-b");
            });
  }

  @Test
  void findByEmailReturnsEmptyForAnUnknownEmailWithNoClubContextSet() {
    saveAs("club-a", "coach@example.com");
    clubContext.clear();

    assertThat(userRepository.findByEmail("nobody@example.com")).isEmpty();
  }

  @Test
  void saveStampsClubIdAndAuditTimestampsAndDefaultsToActive() {
    User saved = saveAs("club-a", "coach@example.com");

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getClubId()).isEqualTo("club-a");
    assertThat(saved.isActive()).isTrue();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void findByIdAndFindAllAreClubScopedForUser() {
    User clubAUser = saveAs("club-a", "a@example.com");
    User clubBUser = saveAs("club-b", "b@example.com");

    clubContext.setClubId("club-a");

    assertThat(userRepository.findById(clubAUser.getId()))
        .hasValueSatisfying(found -> assertThat(found.getEmail()).isEqualTo("a@example.com"));
    assertThat(userRepository.findById(clubBUser.getId())).isEmpty();
    assertThat(userRepository.findAll())
        .extracting(User::getEmail)
        .containsExactly("a@example.com");
  }

  // --- optimistic locking (KAN-24) ---------------------------------------------------------------

  @Test
  void insertCreatesAUserWithVersionZero() {
    clubContext.setClubId("club-a");

    User inserted = userRepository.insert(newUser("coach@example.com"));

    assertThat(inserted.getVersion()).isZero();
    assertThat(rawUser(inserted.getId()).get("version")).isEqualTo(0L);
  }

  @Test
  void aSaveIncrementsTheVersionByOne() {
    User saved = saveAs("club-a", "coach@example.com");
    User loaded = userRepository.findById(saved.getId()).orElseThrow();
    loaded.setFullName("Dana Cohen");

    User updated = userRepository.save(loaded);

    assertThat(updated.getVersion()).isEqualTo(saved.getVersion() + 1);
    assertThat(rawUser(saved.getId()).get("version")).isEqualTo(saved.getVersion() + 1);
  }

  /** Last-write-wins is gone: the second writer fails instead of silently overwriting the first. */
  @Test
  void aSaveFromAStaleCopyFailsAndKeepsTheFirstWrite() {
    User saved = saveAs("club-a", "coach@example.com");
    User first = userRepository.findById(saved.getId()).orElseThrow();
    User second = userRepository.findById(saved.getId()).orElseThrow();

    first.setPermissionLevel(PermissionLevel.VIEW_ONLY);
    userRepository.save(first);
    second.setFullName("Written from a stale copy");

    assertThatThrownBy(() -> userRepository.save(second))
        .isInstanceOf(OptimisticLockingFailureException.class);
    User stored = mongoTemplate.findById(saved.getId(), User.class);
    assertThat(stored.getPermissionLevel()).isEqualTo(PermissionLevel.VIEW_ONLY);
    assertThat(stored.getFullName()).isEqualTo("Dana Levi");
    assertThat(stored.getVersion()).isEqualTo(saved.getVersion() + 1);
  }

  /** The clubId check still runs first: a versioned save doesn't skip it. */
  @Test
  void aVersionedSaveFromAnotherClubIsStillRejected() {
    User saved = saveAs("club-a", "coach@example.com");
    User loaded = userRepository.findById(saved.getId()).orElseThrow();
    clubContext.setClubId("club-b");

    assertThatThrownBy(() -> userRepository.save(loaded))
        .isInstanceOf(CrossClubAccessException.class);
    assertThat(rawUser(saved.getId()).get("version")).isEqualTo(saved.getVersion());
  }

  /** Auditing decides created-vs-modified by the same isNew as the save — still right. */
  @Test
  void anUpdateKeepsCreatedAtAndChangesUpdatedAt() {
    User saved = saveAs("club-a", "coach@example.com");
    Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(saved.getId())),
        new Update().set("createdAt", longAgo).set("updatedAt", longAgo),
        "users");
    User loaded = userRepository.findById(saved.getId()).orElseThrow();
    loaded.setFullName("Dana Cohen");

    userRepository.save(loaded);

    User stored = mongoTemplate.findById(saved.getId(), User.class);
    assertThat(stored.getCreatedAt()).isEqualTo(longAgo);
    assertThat(stored.getUpdatedAt()).isAfter(longAgo);
  }

  /**
   * Why {@link UserVersionBackfill} exists: a document without a version loads with {@code null},
   * which Spring Data takes to mean "new" — so save tries to insert it again.
   */
  @Test
  void withoutTheBackfillALegacyDocumentCantBeSaved() {
    ObjectId id = insertLegacyUser("club-a", "legacy@example.com");
    clubContext.setClubId("club-a");
    User loaded = userRepository.findById(id.toHexString()).orElseThrow();
    assertThat(loaded.getVersion()).isNull();
    loaded.setFullName("Dana Cohen");

    // A re-insert of the existing _id, not a clash on the unique email index.
    assertThatThrownBy(() -> userRepository.save(loaded))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("index: _id_ ");
    assertThat(mongoTemplate.count(new Query(), User.class)).isEqualTo(1);
    assertThat(rawUser(id.toHexString()).get("fullName")).isEqualTo("Dana Levi");
  }

  @Test
  void afterTheBackfillALegacyDocumentSavesAsAnUpdate() {
    ObjectId id = insertLegacyUser("club-a", "legacy@example.com");
    backfill.backfill();
    clubContext.setClubId("club-a");
    User loaded = userRepository.findById(id.toHexString()).orElseThrow();
    loaded.setFullName("Dana Cohen");

    userRepository.save(loaded);

    assertThat(mongoTemplate.count(new Query(), User.class)).isEqualTo(1);
    Document stored = rawUser(id.toHexString());
    assertThat(stored.get("fullName")).isEqualTo("Dana Cohen");
    assertThat(stored.get("version")).isEqualTo(1L);
  }

  /** Adds version 0 to legacy documents only, touches nothing else, and is idempotent. */
  @Test
  void theBackfillOnlyAddsTheMissingVersionFieldAndIsIdempotent() {
    ObjectId legacyA = insertLegacyUser("club-a", "a@example.com");
    ObjectId legacyB = insertLegacyUser("club-b", "b@example.com");
    User current = saveAs("club-a", "current@example.com");
    User updated = userRepository.save(userRepository.findById(current.getId()).orElseThrow());
    Document legacyABefore = rawUser(legacyA.toHexString());
    Document legacyBBefore = rawUser(legacyB.toHexString());
    Document currentBefore = rawUser(current.getId());

    assertThat(backfill.backfill()).isEqualTo(2);

    legacyABefore.put("version", 0L);
    legacyBBefore.put("version", 0L);
    assertThat(rawUser(legacyA.toHexString())).isEqualTo(legacyABefore);
    assertThat(rawUser(legacyB.toHexString())).isEqualTo(legacyBBefore);
    assertThat(rawUser(current.getId())).isEqualTo(currentBefore);
    assertThat(currentBefore.get("version")).isEqualTo(updated.getVersion()).isEqualTo(1L);

    assertThat(backfill.backfill()).isZero();
  }

  private User saveAs(String clubId, String email) {
    clubContext.setClubId(clubId);
    return userRepository.save(newUser(email));
  }

  private static User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPasswordHash("placeholder-hash");
    user.setTitle(Title.CLUB_MANAGER);
    user.setPermissionLevel(PermissionLevel.ADMIN);
    user.setFullName("Dana Levi");
    user.setDateOfBirth(LocalDate.of(1985, 3, 1));
    return user;
  }

  /** A user as stored before KAN-24: every field, but no {@code version}. */
  private ObjectId insertLegacyUser(String clubId, String email) {
    ObjectId id = new ObjectId();
    mongoTemplate
        .getCollection("users")
        .insertOne(
            new Document("_id", id)
                .append("clubId", clubId)
                .append("email", email)
                .append("passwordHash", "placeholder-hash")
                .append("title", Title.HEAD_COACH.name())
                .append("permissionLevel", PermissionLevel.EDIT_FULL.name())
                .append("fullName", "Dana Levi")
                .append("active", true)
                .append("createdAt", Date.from(Instant.parse("2026-01-01T00:00:00Z")))
                .append("updatedAt", Date.from(Instant.parse("2026-01-01T00:00:00Z")))
                .append("_class", User.class.getName()));
    return id;
  }

  private Document rawUser(String id) {
    return mongoTemplate.getCollection("users").find(new Document("_id", new ObjectId(id))).first();
  }
}
