package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.common.ClubContext;
import java.time.Instant;
import java.util.Date;
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
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Concurrent writes to one {@link User} (KAN-24), end to end over HTTP on a real MongoDB: whatever
 * interleaves between a flow's load and its save, no write is silently lost, a deactivation is
 * never undone, and an unresolved conflict is a 409 — never a 204 or a 500.
 *
 * <p>The interleaving is deterministic: {@link UserLoadHook} performs the "concurrent" write on the
 * test thread, right after the flow under test has loaded the user. {@link
 * PasswordResetCodeService} is mocked to accept any code, so no Redis is needed; codes themselves
 * are covered by {@link PasswordResetFlowIntegrationTest}.
 *
 * <p>Every test has a real, active {@code ADMIN} in club-a to call the user-management endpoints
 * with: they re-read their caller first (see {@link ActiveCallerCheck}). Hooks on those flows are
 * armed for the target's id only, so the caller's load doesn't trigger them.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(UserLoadHook.class)
class UserConcurrentWriteIntegrationTest {

  private static final String OLD_PASSWORD = "correct-horse-battery";
  private static final String NEW_PASSWORD = "a-brand-new-passphrase";
  private static final String EMAIL = "coach@example.com";
  private static final String CONFLICT_MESSAGE =
      "The resource was modified concurrently, please retry";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private UserPermissionLevelService permissionLevelService;
  @Autowired private PasswordResetService passwordResetService;
  @Autowired private UserVersionBackfill backfill;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JwtService jwtService;
  @Autowired private ClubContext clubContext;
  @Autowired private UserLoadHook hook;
  @MockitoBean private PasswordResetCodeService codeService;

  /** The caller of every user-management request here. */
  private User admin;

  @BeforeEach
  void setUp() {
    when(codeService.verify(anyString(), anyString())).thenReturn(true);
    admin = insertUser("club-a", "manager@example.com");
    admin.setPermissionLevel(PermissionLevel.ADMIN);
    admin = mongoTemplate.save(admin);
  }

  @AfterEach
  void tearDown() {
    hook.disarm();
    clubContext.clear();
    mongoTemplate.remove(new Query(), User.class);
  }

  // --- reset vs permission change ----------------------------------------------------------------

  @Test
  void aPermissionChangeDuringAResetIsKeptAndTheResetStillSucceeds() throws Exception {
    User user = insertUser("club-a", EMAIL);
    hook.onNextLoad(
        () ->
            clubContext.callAs(
                "club-a",
                () ->
                    permissionLevelService.changePermissionLevel(
                        user.getId(), PermissionLevel.ADMIN, adminCaller())));

    resetPassword(EMAIL).andExpect(status().isNoContent());

    assertThat(hook.timesFired()).isEqualTo(1);
    User stored = stored(user);
    assertThat(passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash())).isTrue();
    assertThat(stored.getSessionsInvalidatedAt()).isNotNull();
    assertThat(stored.getPermissionLevel()).isEqualTo(PermissionLevel.ADMIN);
    assertThat(stored.getVersion()).isEqualTo(user.getVersion() + 2);
  }

  @Test
  void aResetDuringAPermissionChangeIsKeptAndTheChangeStillSucceeds() throws Exception {
    User user = insertUser("club-a", EMAIL);
    hook.onNextLoadOf(
        user.getId(), () -> passwordResetService.resetPassword(EMAIL, "123456", NEW_PASSWORD));

    changePermissionLevel(user.getId(), PermissionLevel.ADMIN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissionLevel").value("ADMIN"));

    assertThat(hook.timesFired()).isEqualTo(1);
    User stored = stored(user);
    assertThat(passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash())).isTrue();
    assertThat(stored.getSessionsInvalidatedAt()).isNotNull();
    assertThat(stored.getPermissionLevel()).isEqualTo(PermissionLevel.ADMIN);
    assertThat(stored.getVersion()).isEqualTo(user.getVersion() + 2);
  }

  // --- reset vs deactivation ---------------------------------------------------------------------

  /**
   * The case KAN-24 exists for: a user deactivated while their reset is in flight stays
   * deactivated, and keeps their old password — the reset fails like any other invalid code.
   */
  @Test
  void aDeactivationDuringAResetIsNeverUndone() throws Exception {
    User user = insertUser("club-a", EMAIL);
    hook.onNextLoad(
        () ->
            clubContext.callAs(
                "club-a",
                () -> {
                  User toDeactivate = userRepository.findById(user.getId()).orElseThrow();
                  toDeactivate.setActive(false);
                  return userRepository.save(toDeactivate);
                }));

    resetPassword(EMAIL)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid or expired code"));

    assertThat(hook.timesFired()).isEqualTo(1);
    User stored = stored(user);
    assertThat(stored.isActive()).isFalse();
    assertThat(passwordEncoder.matches(OLD_PASSWORD, stored.getPasswordHash())).isTrue();
    assertThat(stored.getSessionsInvalidatedAt()).isNull();
    assertThat(stored.getVersion()).isEqualTo(user.getVersion() + 1);
  }

  // --- retries exhausted -------------------------------------------------------------------------

  @Test
  void aResetThatConflictsOnEveryAttemptIs409AndChangesNothing() throws Exception {
    User user = insertUser("club-a", EMAIL);
    hook.onEveryLoad(() -> bumpVersion(user));

    resetPassword(EMAIL)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(CONFLICT_MESSAGE));

    User stored = stored(user);
    assertThat(passwordEncoder.matches(OLD_PASSWORD, stored.getPasswordHash())).isTrue();
    assertThat(stored.getSessionsInvalidatedAt()).isNull();
  }

  @Test
  void aPermissionChangeThatConflictsOnEveryAttemptIs409AndChangesNothing() throws Exception {
    User user = insertUser("club-a", EMAIL);
    hook.onEveryLoadOf(user.getId(), () -> bumpVersion(user));

    changePermissionLevel(user.getId(), PermissionLevel.ADMIN)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(CONFLICT_MESSAGE));

    assertThat(stored(user).getPermissionLevel()).isEqualTo(PermissionLevel.EDIT_FULL);
  }

  // --- permission change vs deletion -------------------------------------------------------------

  @Test
  void aUserDeletedDuringAPermissionChangeIs404() throws Exception {
    User user = insertUser("club-a", EMAIL);
    hook.onNextLoadOf(
        user.getId(),
        () ->
            clubContext.callAs(
                "club-a",
                () -> {
                  userRepository.deleteById(user.getId());
                  return null;
                }));

    changePermissionLevel(user.getId(), PermissionLevel.ADMIN)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("User not found"));

    assertThat(mongoTemplate.findById(user.getId(), User.class)).isNull();
  }

  // --- legacy documents --------------------------------------------------------------------------

  @Test
  void aResetWorksOnABackfilledLegacyUserWithoutCreatingADuplicate() throws Exception {
    ObjectId id = insertLegacyUser("club-a", EMAIL);
    assertThat(backfill.backfill()).isEqualTo(1);

    resetPassword(EMAIL).andExpect(status().isNoContent());

    assertThat(mongoTemplate.count(new Query(), User.class)).isEqualTo(2); // with the admin
    User stored = mongoTemplate.findById(id.toHexString(), User.class);
    assertThat(passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash())).isTrue();
    assertThat(stored.getVersion()).isEqualTo(1L);
  }

  @Test
  void aPermissionChangeWorksOnABackfilledLegacyUserWithoutCreatingADuplicate() throws Exception {
    ObjectId id = insertLegacyUser("club-a", EMAIL);
    assertThat(backfill.backfill()).isEqualTo(1);

    changePermissionLevel(id.toHexString(), PermissionLevel.VIEW_ONLY).andExpect(status().isOk());

    assertThat(mongoTemplate.count(new Query(), User.class)).isEqualTo(2); // with the admin
    User stored = mongoTemplate.findById(id.toHexString(), User.class);
    assertThat(stored.getPermissionLevel()).isEqualTo(PermissionLevel.VIEW_ONLY);
    assertThat(stored.getVersion()).isEqualTo(1L);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private ResultActions resetPassword(String email) throws Exception {
    return mockMvc.perform(
        post("/auth/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"email\":\"%s\",\"code\":\"123456\",\"newPassword\":\"%s\"}"
                    .formatted(email, NEW_PASSWORD)));
  }

  private ResultActions changePermissionLevel(String userId, PermissionLevel level)
      throws Exception {
    return mockMvc.perform(
        patch("/auth/users/" + userId + "/permission-level")
            .header("Authorization", "Bearer " + jwtService.issue(admin).value())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"permissionLevel\": \"%s\"}".formatted(level)));
  }

  private AuthenticatedUser adminCaller() {
    return new AuthenticatedUser(admin.getId(), "club-a", PermissionLevel.ADMIN);
  }

  /** A write that changes nothing but still moves the version on, as any save does. */
  private void bumpVersion(User user) {
    clubContext.callAs(
        user.getClubId(),
        () -> userRepository.save(userRepository.findById(user.getId()).orElseThrow()));
  }

  private User stored(User user) {
    return mongoTemplate.findById(user.getId(), User.class);
  }

  private User insertUser(String clubId, String email) {
    User user = new User();
    user.setClubId(clubId);
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(OLD_PASSWORD));
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(PermissionLevel.EDIT_FULL);
    user.setFullName("Dana Levi");
    return mongoTemplate.insert(user);
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
                .append("passwordHash", passwordEncoder.encode(OLD_PASSWORD))
                .append("title", Title.HEAD_COACH.name())
                .append("permissionLevel", PermissionLevel.EDIT_FULL.name())
                .append("fullName", "Dana Levi")
                .append("active", true)
                .append("createdAt", Date.from(Instant.parse("2026-01-01T00:00:00Z")))
                .append("updatedAt", Date.from(Instant.parse("2026-01-01T00:00:00Z")))
                .append("_class", User.class.getName()));
    return id;
  }
}
