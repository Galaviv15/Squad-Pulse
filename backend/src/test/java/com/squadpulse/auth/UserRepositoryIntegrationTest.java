package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squadpulse.common.ClubContext;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
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

  private User saveAs(String clubId, String email) {
    clubContext.setClubId(clubId);
    User user = new User();
    user.setEmail(email);
    user.setPasswordHash("placeholder-hash");
    user.setTitle(Title.CLUB_MANAGER);
    user.setPermissionLevel(PermissionLevel.ADMIN);
    user.setFullName("Dana Levi");
    user.setDateOfBirth(LocalDate.of(1985, 3, 1));
    return userRepository.save(user);
  }
}
