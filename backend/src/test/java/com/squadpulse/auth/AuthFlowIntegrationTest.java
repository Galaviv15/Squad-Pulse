package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.redis.testcontainers.RedisContainer;
import com.squadpulse.common.ClubContext;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end auth flows through the whole application — security chain, controllers, the
 * club-scoped repositories on a real MongoDB, and refresh-token families on a real Redis.
 *
 * <p>MockMvc runs the filter chain on the test thread, so asserting that {@link ClubContext} is
 * empty after each request (see {@link #perform}) proves the JWT filter's cleanup for real.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
class AuthFlowIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

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

  @AfterEach
  void tearDown() {
    clubContext.clear();
    // Remove documents rather than dropping the collection, which would drop the unique email
    // index.
    mongoTemplate.remove(new Query(), User.class);
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Test
  void loginThenRefreshRotatesTheCookieAndReplayingTheOldOneKillsTheSession() throws Exception {
    insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL, true);

    Cookie first = refreshCookie(login("coach@example.com", PASSWORD).andReturn());
    MvcResult refreshed = perform(post("/auth/refresh").cookie(first)).andReturn();
    assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
    Cookie second = refreshCookie(refreshed);
    assertThat(second.getValue()).isNotEqualTo(first.getValue());

    // The rotated-away token is replayed (e.g. by a thief)...
    perform(post("/auth/refresh").cookie(first)).andExpect(status().isUnauthorized());
    // ...which revoked the whole family, so the legitimate holder is logged out too.
    perform(post("/auth/refresh").cookie(second)).andExpect(status().isUnauthorized());
  }

  @Test
  void theAccessTokenFromLoginAuthenticatesRequests() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN, true);
    String accessToken = accessToken(login("manager@example.com", PASSWORD).andReturn());

    perform(invite(accessToken, "new@example.com")).andExpect(status().isCreated());
    perform(invite("not-a-token", "other@example.com")).andExpect(status().isUnauthorized());
  }

  @Test
  void anInvitedUserLandsInTheAdminsOwnClubWithNoPasswordAndCantLogInYet() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN, true);
    String accessToken = accessToken(login("manager@example.com", PASSWORD).andReturn());

    // A client-supplied clubId must be ignored.
    perform(
            post("/auth/users/invite")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": " New.Coach@Example.com ", "fullName": "Noa Cohen",
                     "title": "ASSISTANT_COACH", "permissionLevel": "VIEW_ONLY",
                     "dateOfBirth": "1990-06-15", "clubId": "club-b"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("new.coach@example.com"));

    User invited =
        mongoTemplate.findOne(
            Query.query(Criteria.where("email").is("new.coach@example.com")), User.class);
    assertThat(invited.getClubId()).isEqualTo("club-a");
    assertThat(invited.getPasswordHash()).isNull();
    assertThat(invited.isActive()).isTrue();
    assertThat(invited.getTitle()).isEqualTo(Title.ASSISTANT_COACH);
    assertThat(invited.getPermissionLevel()).isEqualTo(PermissionLevel.VIEW_ONLY);
    assertThat(invited.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 6, 15));

    login("new.coach@example.com", "").andExpect(status().isBadRequest());
    login("new.coach@example.com", "any-password").andExpect(status().isUnauthorized());
  }

  @Test
  void aNonAdminCantInvite() throws Exception {
    insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL, true);
    String accessToken = accessToken(login("coach@example.com", PASSWORD).andReturn());

    perform(invite(accessToken, "new@example.com")).andExpect(status().isForbidden());

    assertThat(mongoTemplate.count(new Query(), User.class)).isEqualTo(1);
  }

  @Test
  void invitingATakenEmailFromAnotherClubIsAConflict() throws Exception {
    insertUser("club-a", "manager@example.com", PermissionLevel.ADMIN, true);
    insertUser("club-b", "taken@example.com", PermissionLevel.VIEW_ONLY, true);
    String accessToken = accessToken(login("manager@example.com", PASSWORD).andReturn());

    perform(invite(accessToken, "taken@example.com")).andExpect(status().isConflict());
  }

  @Test
  void everyLoginFailureLooksTheSame() throws Exception {
    insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL, true);
    insertUser("club-a", "former@example.com", PermissionLevel.EDIT_FULL, false);

    String wrongPassword = failedLoginBody("coach@example.com", "wrong-password");
    String unknownEmail = failedLoginBody("nobody@example.com", PASSWORD);
    String deactivated = failedLoginBody("former@example.com", PASSWORD);

    assertThat(JsonPath.<String>read(wrongPassword, "$.message"))
        .isEqualTo(JsonPath.<String>read(unknownEmail, "$.message"))
        .isEqualTo(JsonPath.<String>read(deactivated, "$.message"))
        .isEqualTo("Invalid email or password");
  }

  @Test
  void deactivatingAUserEndsTheirSessionAtTheNextRefresh() throws Exception {
    insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL, true);
    Cookie cookie = refreshCookie(login("coach@example.com", PASSWORD).andReturn());

    mongoTemplate.updateFirst(
        Query.query(Criteria.where("email").is("coach@example.com")),
        Update.update("active", false),
        User.class);

    perform(post("/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  @Test
  void logoutEndsOnlyThatSession() throws Exception {
    insertUser("club-a", "coach@example.com", PermissionLevel.EDIT_FULL, true);
    Cookie laptop = refreshCookie(login("coach@example.com", PASSWORD).andReturn());
    Cookie phone = refreshCookie(login("coach@example.com", PASSWORD).andReturn());

    perform(post("/auth/logout").cookie(laptop)).andExpect(status().isNoContent());

    perform(post("/auth/refresh").cookie(laptop)).andExpect(status().isUnauthorized());
    perform(post("/auth/refresh").cookie(phone)).andExpect(status().isOk());
  }

  /** Performs the request and checks that it left no clubId behind on this thread. */
  private ResultActions perform(RequestBuilder request) throws Exception {
    ResultActions result = mockMvc.perform(request);
    assertThat(clubContext.getClubId()).as("ClubContext after the request").isEmpty();
    return result;
  }

  private ResultActions login(String email, String password) throws Exception {
    return perform(
        post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
  }

  private String failedLoginBody(String email, String password) throws Exception {
    return login(email, password)
        .andExpect(status().isUnauthorized())
        .andReturn()
        .getResponse()
        .getContentAsString();
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

  private void insertUser(
      String clubId, String email, PermissionLevel permissionLevel, boolean active) {
    User user = new User();
    user.setClubId(clubId);
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(PASSWORD));
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(permissionLevel);
    user.setFullName("Dana Levi");
    user.setActive(active);
    mongoTemplate.insert(user);
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
