package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.redis.testcontainers.RedisContainer;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
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
 * {@code GET /auth/users/me} through the whole application (KAN-34): security chain, the
 * club-scoped user lookup on a real MongoDB, and real logins and refreshes on a real Redis.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
class CurrentUserIntegrationTest {

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
  @Autowired private JwtService jwtService;

  private final Logger handlerLogger =
      (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @AfterEach
  void tearDown() {
    handlerLogger.detachAppender(appender);
    clubContext.clear();
    // Remove documents rather than dropping the collections, which would drop their indexes.
    mongoTemplate.remove(new Query(), User.class);
    mongoTemplate.remove(new Query(), Club.class);
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Test
  void returnsTheCallerFromTheDatabaseWithTheirClub() throws Exception {
    Club club = insertClub("Hapoel Example");
    User coach = insertUser(club, "coach@example.com", PermissionLevel.EDIT_FULL);
    String accessToken = accessToken(login("coach@example.com"));

    perform(me(accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(coach.getId()))
        .andExpect(jsonPath("$.email").value("coach@example.com"))
        .andExpect(jsonPath("$.fullName").value("Dana Levi"))
        .andExpect(jsonPath("$.title").value("HEAD_COACH"))
        .andExpect(jsonPath("$.permissionLevel").value("EDIT_FULL"))
        .andExpect(jsonPath("$.dateOfBirth").value("1985-03-01"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.club.id").value(club.getId()))
        .andExpect(jsonPath("$.club.name").value("Hapoel Example"))
        .andExpect(jsonPath("$.club.hasLogo").value(false));
  }

  @Test
  void neverExposesPasswordVersionSessionOrTopLevelClubIdFields() throws Exception {
    Club club = insertClub("Hapoel Example");
    insertUser(club, "coach@example.com", PermissionLevel.EDIT_FULL);
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("email").is("coach@example.com")),
        Update.update("sessionsInvalidatedAt", Instant.now()),
        User.class);
    String accessToken = accessToken(login("coach@example.com"));

    String body = perform(me(accessToken)).andReturn().getResponse().getContentAsString();

    assertThat(JsonPath.<Map<String, Object>>read(body, "$").keySet())
        .containsExactly(
            "id", "email", "fullName", "title", "permissionLevel", "dateOfBirth", "active", "club")
        .doesNotContain("passwordHash", "version", "sessionsInvalidatedAt", "clubId");
    assertThat(body).doesNotContain("password", "$argon2", "version", "sessionsInvalidatedAt");
  }

  /**
   * The documented "effective level": a level change shows up only after the caller's next refresh,
   * like everywhere else — while the other fields are read fresh on every call.
   */
  @Test
  void showsTheTokensLevelUntilTheNextRefreshButEverythingElseFromTheDatabase() throws Exception {
    Club club = insertClub("Hapoel Example");
    insertUser(club, "manager@example.com", PermissionLevel.ADMIN);
    User analyst = insertUser(club, "analyst@example.com", PermissionLevel.VIEW_ONLY);
    String adminToken = accessToken(login("manager@example.com"));
    MvcResult analystLogin = login("analyst@example.com");
    String analystToken = accessToken(analystLogin);

    perform(
            patch("/auth/users/" + analyst.getId() + "/permission-level")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissionLevel\": \"EDIT_FULL\"}"))
        .andExpect(status().isOk());
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(analyst.getId())),
        Update.update("fullName", "Noa Cohen"),
        User.class);

    perform(me(analystToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissionLevel").value("VIEW_ONLY"))
        .andExpect(jsonPath("$.fullName").value("Noa Cohen"));

    MvcResult refreshed =
        perform(post("/auth/refresh").cookie(refreshCookie(analystLogin))).andReturn();
    perform(me(accessToken(refreshed)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissionLevel").value("EDIT_FULL"))
        .andExpect(jsonPath("$.fullName").value("Noa Cohen"));
  }

  /** The deliberate exception to "an access token keeps working after deactivation". */
  @Test
  void aDeactivatedUserWithAValidTokenGetsTheSame401AsNoToken() throws Exception {
    Club club = insertClub("Hapoel Example");
    insertUser(club, "coach@example.com", PermissionLevel.ADMIN);
    String accessToken = accessToken(login("coach@example.com"));
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("email").is("coach@example.com")),
        Update.update("active", false),
        User.class);

    MvcResult deactivated = perform(me(accessToken)).andReturn();
    MvcResult noToken = perform(get("/auth/users/me")).andReturn();

    assertThat(deactivated.getResponse().getStatus()).isEqualTo(401);
    assertThat(noToken.getResponse().getStatus()).isEqualTo(401);
    assertThat(withoutTimestamp(deactivated))
        .isEqualTo(withoutTimestamp(noToken))
        .containsEntry("message", "Authentication required");
    assertThat(deactivated.getResponse().getContentType())
        .isEqualTo(noToken.getResponse().getContentType());
  }

  @Test
  void aDeletedUserWithAValidTokenIs401() throws Exception {
    Club club = insertClub("Hapoel Example");
    User coach = insertUser(club, "coach@example.com", PermissionLevel.ADMIN);
    String accessToken = accessToken(login("coach@example.com"));
    mongoTemplate.remove(Query.query(Criteria.where("_id").is(coach.getId())), User.class);

    perform(me(accessToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
  }

  /**
   * A genuinely signed token whose {@code sub} is a user of club B but whose {@code clubId} is club
   * A: the club-scoped lookup finds nobody, so it's a 401 and nothing of club B is returned.
   */
  @Test
  void aTokenForClubANamingAUserOfClubBIs401AndLeaksNothing() throws Exception {
    Club clubA = insertClub("Club A");
    Club clubB = insertClub("Club B Secret Name");
    User otherClubsUser =
        insertUser(clubB, "secret.person@other.example.com", PermissionLevel.ADMIN);
    User forged = new User();
    forged.setId(otherClubsUser.getId());
    forged.setClubId(clubA.getId());
    forged.setPermissionLevel(PermissionLevel.ADMIN);
    String token = jwtService.issue(forged).value();

    String body =
        perform(me(token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Authentication required"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .doesNotContain(otherClubsUser.getId())
        .doesNotContain("secret.person")
        .doesNotContain("Club B Secret Name")
        .doesNotContain(clubB.getId());
  }

  /** A data-integrity bug, not the client's fault: a generic 500, logged at ERROR. */
  @Test
  void aMissingClubIsAGeneric500LoggedAtError() throws Exception {
    Club club = insertClub("Hapoel Example");
    insertUser(club, "coach@example.com", PermissionLevel.EDIT_FULL);
    String accessToken = accessToken(login("coach@example.com"));
    mongoTemplate.remove(Query.query(Criteria.where("_id").is(club.getId())), Club.class);
    appender.start();
    handlerLogger.addAppender(appender);

    String body =
        perform(me(accessToken))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Internal Server Error"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.details").isEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(club.getId()).doesNotContain("not found");
    List<ILoggingEvent> errors =
        appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    assertThat(errors).hasSize(1);
    assertThat(errors.getFirst().getFormattedMessage())
        .isEqualTo("Unexpected error handling GET /auth/users/me");
    assertThat(ThrowableProxyUtil.asString(errors.getFirst().getThrowableProxy()))
        .contains("java.lang.IllegalStateException: Club " + club.getId());
  }

  @Test
  void readingTheCallerWritesNothing() throws Exception {
    Club club = insertClub("Hapoel Example");
    User coach = insertUser(club, "coach@example.com", PermissionLevel.EDIT_FULL);
    String accessToken = accessToken(login("coach@example.com"));
    User before = mongoTemplate.findById(coach.getId(), User.class);

    perform(me(accessToken)).andExpect(status().isOk());
    perform(me(accessToken)).andExpect(status().isOk());

    User after = mongoTemplate.findById(coach.getId(), User.class);
    assertThat(after.getVersion()).isEqualTo(before.getVersion());
    assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
  }

  /** Performs the request and checks that it left no clubId behind on this thread. */
  private ResultActions perform(RequestBuilder request) throws Exception {
    ResultActions result = mockMvc.perform(request);
    assertThat(clubContext.getClubId()).as("ClubContext after the request").isEmpty();
    return result;
  }

  private static RequestBuilder me(String accessToken) {
    return get("/auth/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
  }

  private MvcResult login(String email) throws Exception {
    return perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
        .andExpect(status().isOk())
        .andReturn();
  }

  private Club insertClub(String name) {
    Club club = new Club();
    club.setName(name);
    return mongoTemplate.insert(club);
  }

  private User insertUser(Club club, String email, PermissionLevel permissionLevel) {
    User user = new User();
    user.setClubId(club.getId());
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(PASSWORD));
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(permissionLevel);
    user.setFullName("Dana Levi");
    user.setDateOfBirth(LocalDate.of(1985, 3, 1));
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

  private static Map<String, Object> withoutTimestamp(MvcResult result) throws Exception {
    Map<String, Object> fields =
        new HashMap<>(
            JsonPath.<Map<String, Object>>read(result.getResponse().getContentAsString(), "$"));
    assertThat(fields.remove("timestamp")).as("timestamp").isNotNull();
    return fields;
  }
}
