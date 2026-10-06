package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.redis.testcontainers.RedisContainer;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.EmailSender;
import com.squadpulse.common.TestImages;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Deactivation and re-activation (KAN-37) end to end — security chain, controllers, the club-scoped
 * repositories on a real MongoDB, and refresh-token families on a real Redis. Every session, login
 * and token here is a real one issued over HTTP. {@link EmailSender} is a mock, so a test can see
 * whether forgot-password sent anything.
 *
 * <p>As in {@link AuthFlowIntegrationTest}, {@link #perform} asserts that no {@link ClubContext} is
 * left behind on the test thread after each request.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
class UserActivationIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String UNKNOWN_ID = "000000000000000000000000";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @Container
  static final RedisContainer REDIS_CONTAINER =
      new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
    registry.add("spring.data.redis.host", REDIS_CONTAINER::getRedisHost);
    registry.add("spring.data.redis.port", REDIS_CONTAINER::getRedisPort);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private StringRedisTemplate redis;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private ClubContext clubContext;
  @Autowired private UserRepository userRepository;
  @Autowired private ActiveCallerCheck activeCallerCheck;
  @MockitoBean private EmailSender emailSender;

  /** The generic 401, {@code timestamp} aside, with {@code code} present and null. */
  private static Map<String, Object> generic401Body() {
    Map<String, Object> body = new HashMap<>();
    body.put("status", 401);
    body.put("error", "Unauthorized");
    body.put("code", null);
    body.put("message", "Authentication required");
    body.put("details", List.of());
    return body;
  }

  @AfterEach
  void tearDown() {
    clubContext.clear();
    mongoTemplate.remove(new Query(), User.class);
    mongoTemplate.remove(new Query(), Club.class);
    mongoTemplate.remove(new Query(), "images.files");
    mongoTemplate.remove(new Query(), "images.chunks");
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  // --- deactivation ends everything --------------------------------------------------------------

  @Test
  void deactivationEndsLoginRefreshMeAndForgotPasswordAndShowsInTheStaffList() throws Exception {
    Club club = new Club();
    club.setId("club-a");
    club.setName("Hapoel Example");
    mongoTemplate.insert(club); // /me reads it
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    User coach = insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL);
    String adminToken = accessToken(login("manager@example.com").andReturn());
    MvcResult coachLogin = login("coach@example.com").andReturn();
    String coachToken = accessToken(coachLogin);
    Cookie c1 = refreshCookie(coachLogin);
    // Sanity: before deactivation, the coach's token and cookie both work.
    perform(me(coachToken)).andExpect(status().isOk());

    perform(action(adminToken, "deactivate", coach.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(coach.getId()))
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.permissionLevel").value("EDIT_FULL"))
        .andExpect(jsonPath("$.activated").value(true));

    login("coach@example.com")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid email or password"));
    perform(post("/auth/refresh").cookie(c1)).andExpect(status().isUnauthorized());
    perform(me(coachToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
    clearInvocations(emailSender);
    perform(forgotPassword("coach@example.com")).andExpect(status().isAccepted());
    verify(emailSender, never()).send(anyString(), anyString(), anyString());

    String staff =
        perform(get("/auth/users").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(staff, "$[*].email"))
        .containsExactly("manager@example.com", "coach@example.com"); // deactivated last
    assertThat(JsonPath.<Boolean>read(staff, "$[1].active")).isFalse();

    User stored = stored(coach);
    assertThat(stored.isActive()).isFalse();
    assertThat(stored.getSessionsInvalidatedAt()).isNotNull();
    // Kept: password, level, title, name.
    assertThat(stored.getPasswordHash()).isEqualTo(coach.getPasswordHash());
    assertThat(stored.getPermissionLevel()).isEqualTo(PermissionLevel.EDIT_FULL);
    assertThat(stored.getTitle()).isEqualTo(Title.HEAD_COACH);
    assertThat(stored.getFullName()).isEqualTo("Dana Levi");
  }

  // --- the resurrection test ---------------------------------------------------------------------

  /**
   * The key case: a refresh cookie that was never presented while its user was deactivated must not
   * come back to life on re-activation. Only a fresh login works afterwards.
   */
  @Test
  void aCookieUnusedWhileDeactivatedIsDeadAfterReactivationButAFreshLoginWorks() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    User coach = insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL);
    String adminToken = accessToken(login("manager@example.com").andReturn());
    Cookie c2 = refreshCookie(login("coach@example.com").andReturn());

    perform(action(adminToken, "deactivate", coach.getId())).andExpect(status().isOk());
    perform(action(adminToken, "reactivate", coach.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));

    perform(post("/auth/refresh").cookie(c2)).andExpect(status().isUnauthorized());

    Cookie fresh = refreshCookie(login("coach@example.com").andReturn());
    MvcResult refreshed = perform(post("/auth/refresh").cookie(fresh)).andReturn();
    assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
    perform(post("/auth/refresh").cookie(refreshCookie(refreshed))).andExpect(status().isOk());
  }

  /**
   * A user deactivated directly in the database before this endpoint existed has no {@code
   * sessionsInvalidatedAt}; re-activating them through the API still ends their old sessions.
   */
  @Test
  void reactivatingALegacyDeactivatedUserEndsTheirOldSessions() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    User coach = insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL);
    String adminToken = accessToken(login("manager@example.com").andReturn());
    Cookie c3 = refreshCookie(login("coach@example.com").andReturn());
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(coach.getId())),
        Update.update("active", false),
        User.class);
    assertThat(stored(coach).getSessionsInvalidatedAt()).isNull();

    perform(action(adminToken, "reactivate", coach.getId())).andExpect(status().isOk());

    assertThat(stored(coach).getSessionsInvalidatedAt()).isNotNull();
    perform(post("/auth/refresh").cookie(c3)).andExpect(status().isUnauthorized());
    perform(post("/auth/refresh").cookie(refreshCookie(login("coach@example.com").andReturn())))
        .andExpect(status().isOk());
  }

  // --- idempotent --------------------------------------------------------------------------------

  @Test
  void deactivatingADeactivatedUserIsA200ThatWritesNothing() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    User coach = insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL);
    String adminToken = accessToken(login("manager@example.com").andReturn());
    perform(action(adminToken, "deactivate", coach.getId())).andExpect(status().isOk());
    User before = stored(coach);

    perform(action(adminToken, "deactivate", coach.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(coach.getId()))
        .andExpect(jsonPath("$.active").value(false));

    User after = stored(coach);
    assertThat(after.getVersion()).isEqualTo(before.getVersion());
    assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    assertThat(after.getSessionsInvalidatedAt()).isEqualTo(before.getSessionsInvalidatedAt());
    assertThat(after).usingRecursiveComparison().isEqualTo(before);
  }

  @Test
  void reactivatingAnActiveUserIsA200ThatWritesNothing() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    User coach = insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL);
    String adminToken = accessToken(login("manager@example.com").andReturn());
    User before = stored(coach);

    perform(action(adminToken, "reactivate", coach.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));

    User after = stored(coach);
    assertThat(after.getVersion()).isEqualTo(before.getVersion());
    assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    assertThat(after.getSessionsInvalidatedAt()).isNull();
    assertThat(after).usingRecursiveComparison().isEqualTo(before);
  }

  // --- self, other admins, never-activated users -------------------------------------------------

  @Test
  void anAdminCantDeactivateOrReactivateThemselvesAndNothingChanges() throws Exception {
    User manager = insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    String adminToken = accessToken(login("manager@example.com").andReturn());
    User before = stored(manager);

    for (String action : List.of("deactivate", "reactivate")) {
      perform(action(adminToken, action, manager.getId()))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_ACTIVE_STATUS"))
          .andExpect(
              jsonPath("$.message")
                  .value("You can't deactivate or reactivate yourself; another ADMIN must do it"));
    }

    assertThat(stored(manager)).usingRecursiveComparison().isEqualTo(before);
  }

  @Test
  void anotherAdminCanBeDeactivatedAndKeepsTheirLevel() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    User secondAdmin = insertUser("club-a", "second@example.com", PermissionLevel.ADMIN);
    String adminToken = accessToken(login("manager@example.com").andReturn());

    perform(action(adminToken, "deactivate", secondAdmin.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.permissionLevel").value("ADMIN"));

    User stored = stored(secondAdmin);
    assertThat(stored.isActive()).isFalse();
    assertThat(stored.getPermissionLevel()).isEqualTo(PermissionLevel.ADMIN);
  }

  /** An invited user who never set a password: both work, and {@code activated} stays false. */
  @Test
  void aNeverActivatedUserCanBeDeactivatedAndReactivated() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    User invited = insertUser("club-a", "invited@example.com", PermissionLevel.VIEW_ONLY);
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(invited.getId())),
        new Update().unset("passwordHash"),
        User.class);
    String adminToken = accessToken(login("manager@example.com").andReturn());

    perform(action(adminToken, "deactivate", invited.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.activated").value(false));
    perform(action(adminToken, "reactivate", invited.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.activated").value(false));

    assertThat(stored(invited).getPasswordHash()).isNull();
  }

  /** Re-activation makes the profile editable again; the photo survives the whole cycle. */
  @Test
  void aReactivatedUsersPhotoCanBeChangedAgain() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    User coach = insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL);
    String adminToken = accessToken(login("manager@example.com").andReturn());
    perform(uploadPhoto(adminToken, coach.getId())).andExpect(status().isNoContent());

    perform(action(adminToken, "deactivate", coach.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasPhoto").value(true));
    perform(uploadPhoto(adminToken, coach.getId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_DEACTIVATED"));

    perform(action(adminToken, "reactivate", coach.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasPhoto").value(true));
    perform(uploadPhoto(adminToken, coach.getId())).andExpect(status().isNoContent());
  }

  // --- caller re-check ---------------------------------------------------------------------------

  /**
   * A deactivated admin's still-valid access token can't be used to manage users — in particular,
   * not to deactivate or demote the admin who deactivated them. A read endpoint without the
   * re-check still accepts it until it expires: the documented, accepted window.
   */
  @Test
  void aDeactivatedAdminsStillValidTokenIsRefusedOnEveryUserManagementWrite() throws Exception {
    User a1 = insertUser("club-a", "first@example.com", PermissionLevel.ADMIN);
    User a2 = insertUser("club-a", "second@example.com", PermissionLevel.ADMIN);
    User target = insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL);
    User inactive = insertUser("club-a", "former@example.com", PermissionLevel.VIEW_ONLY);
    String a1Token = accessToken(login("first@example.com").andReturn());
    String a2Token = accessToken(login("second@example.com").andReturn());
    perform(action(a2Token, "deactivate", inactive.getId())).andExpect(status().isOk());
    perform(action(a2Token, "deactivate", a1.getId())).andExpect(status().isOk());
    Map<String, User> before = new HashMap<>();
    for (User user : List.of(a2, target, inactive)) {
      before.put(user.getId(), stored(user));
    }

    List<RequestBuilder> writes =
        List.of(
            action(a1Token, "deactivate", a2.getId()),
            action(a1Token, "deactivate", target.getId()),
            action(a1Token, "reactivate", inactive.getId()),
            patch("/auth/users/" + a2.getId() + "/permission-level")
                .header("Authorization", "Bearer " + a1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissionLevel\": \"VIEW_ONLY\"}"),
            invite(a1Token, "new@example.com"));
    for (RequestBuilder write : writes) {
      assertThat(errorBodyWithoutTimestamp(perform(write).andExpect(status().isUnauthorized())))
          .isEqualTo(generic401Body());
    }

    for (User user : List.of(a2, target, inactive)) {
      assertThat(stored(user)).usingRecursiveComparison().isEqualTo(before.get(user.getId()));
    }
    assertThat(
            mongoTemplate.exists(
                Query.query(Criteria.where("email").is("new@example.com")), User.class))
        .isFalse();
    // The accepted window: a read endpoint doesn't re-check the caller.
    perform(get("/squad/players").header("Authorization", "Bearer " + a1Token))
        .andExpect(status().isOk());
  }

  // --- club isolation ----------------------------------------------------------------------------

  /**
   * Another club's user is indistinguishable from an unknown id, in both directions and for both
   * endpoints — also when that user is already in the requested state, so the no-op can't confirm
   * the id exists — and is left untouched.
   */
  @Test
  void anAdminCantTouchAnotherClubsUserAndItLooksLikeAnUnknownId() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    insertUser("club-b", "manager@other.example.com", PermissionLevel.ADMIN);
    User clubAUser = insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL);
    User clubBUser = insertUser("club-b", "coach@other.example.com", PermissionLevel.EDIT_FULL);
    String clubAToken = accessToken(login("manager@example.com").andReturn());
    String clubBToken = accessToken(login("manager@other.example.com").andReturn());
    User clubABefore = stored(clubAUser);
    User clubBBefore = stored(clubBUser);

    for (String action : List.of("deactivate", "reactivate")) {
      Map<String, Object> unknown =
          errorBodyWithoutTimestamp(
              perform(action(clubAToken, action, UNKNOWN_ID)).andExpect(status().isNotFound()));
      assertThat(unknown).containsEntry("message", "User not found");
      assertThat(
              errorBodyWithoutTimestamp(
                  perform(action(clubAToken, action, clubBUser.getId()))
                      .andExpect(status().isNotFound())))
          .isEqualTo(unknown);
      assertThat(
              errorBodyWithoutTimestamp(
                  perform(action(clubBToken, action, clubAUser.getId()))
                      .andExpect(status().isNotFound())))
          .isEqualTo(unknown);
    }

    assertThat(stored(clubAUser)).usingRecursiveComparison().isEqualTo(clubABefore);
    assertThat(stored(clubBUser)).usingRecursiveComparison().isEqualTo(clubBBefore);
  }

  // --- storage -----------------------------------------------------------------------------------

  /**
   * {@code sessionsInvalidatedAt} round-trips through MongoDB truncated to whole milliseconds — the
   * precision {@code AuthService.invalidatedSince} compares at. Set from a clock with nanoseconds.
   */
  @Test
  void theInvalidationInstantIsStoredWithMillisecondPrecision() {
    User manager = insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN);
    User coach = insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL);
    Instant withNanos = Instant.parse("2026-10-04T12:00:00.123456789Z");
    UserActivationService service =
        new UserActivationService(
            userRepository, activeCallerCheck, Clock.fixed(withNanos, ZoneOffset.UTC));

    clubContext.callAs(
        "club-a",
        () ->
            service.deactivate(
                coach.getId(),
                new AuthenticatedUser(manager.getId(), "club-a", PermissionLevel.ADMIN)));

    assertThat(stored(coach).getSessionsInvalidatedAt())
        .isEqualTo(Instant.parse("2026-10-04T12:00:00.123Z"));
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** Performs the request and checks that it left no clubId behind on this thread. */
  private ResultActions perform(RequestBuilder request) throws Exception {
    ResultActions result = mockMvc.perform(request);
    assertThat(clubContext.getClubId()).as("ClubContext after the request").isEmpty();
    return result;
  }

  private ResultActions login(String email) throws Exception {
    return perform(
        post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)));
  }

  private static RequestBuilder action(String accessToken, String action, String userId) {
    return post("/auth/users/" + userId + "/" + action)
        .header("Authorization", "Bearer " + accessToken);
  }

  private static RequestBuilder me(String accessToken) {
    return get("/auth/users/me").header("Authorization", "Bearer " + accessToken);
  }

  private static RequestBuilder forgotPassword(String email) {
    return post("/auth/forgot-password")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\"}".formatted(email));
  }

  private static RequestBuilder invite(String accessToken, String email) {
    return post("/auth/users/invite")
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"email": "%s", "fullName": "Noa Cohen", "title": "ANALYST",
             "permissionLevel": "VIEW_ONLY"}
            """
                .formatted(email));
  }

  private static RequestBuilder uploadPhoto(String accessToken, String userId) {
    return multipart(HttpMethod.PUT, "/users/" + userId + "/photo")
        .file(new MockMultipartFile("file", "photo", "image/png", TestImages.png()))
        .header("Authorization", "Bearer " + accessToken);
  }

  /** Every field of an error body except {@code timestamp}, which differs between any two calls. */
  private static Map<String, Object> errorBodyWithoutTimestamp(ResultActions result)
      throws Exception {
    Map<String, Object> fields =
        new HashMap<>(
            JsonPath.<Map<String, Object>>read(
                result.andReturn().getResponse().getContentAsString(), "$"));
    assertThat(fields.remove("timestamp")).as("timestamp").isNotNull();
    return fields;
  }

  private User stored(User user) {
    return mongoTemplate.findById(user.getId(), User.class);
  }

  private User insertUser(String clubId, String email, PermissionLevel permissionLevel) {
    User user = new User();
    user.setClubId(clubId);
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(PASSWORD));
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(permissionLevel);
    user.setFullName("Dana Levi");
    return mongoTemplate.insert(user);
  }

  private static Cookie refreshCookie(MvcResult result) {
    Cookie cookie = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
    assertThat(cookie).as("refresh cookie").isNotNull();
    return new Cookie(cookie.getName(), cookie.getValue());
  }

  private static String accessToken(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }
}
