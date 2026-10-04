package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.redis.testcontainers.RedisContainer;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.TestImages;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
 * Club settings (KAN-38) end to end: security chain, controller, the targeted {@code $set} on a
 * real MongoDB, and real logins on a real Redis. Every club here has an {@code ObjectId} {@code
 * _id}, as a bootstrapped club does, while tokens carry it as a hex string — so the update's {@code
 * _id} mapping is exercised for real.
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
class ClubSettingsIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  /** Canonical extended JSON: every BSON type spelled out, so equal strings mean equal values. */
  private static final JsonWriterSettings CANONICAL =
      JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();

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
  @Autowired private ClubRepository clubRepository;
  @Autowired private ActiveCallerCheck activeCallerCheck;
  @Autowired private ClubLogoService clubLogoService;

  @AfterEach
  void tearDown() {
    clubContext.clear();
    // Remove documents rather than dropping the collections, which would drop their indexes.
    mongoTemplate.remove(new Query(), User.class);
    mongoTemplate.remove(new Query(), Club.class);
    mongoTemplate.remove(new Query(), "images.files");
    mongoTemplate.remove(new Query(), "images.chunks");
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  // --- round trip --------------------------------------------------------------------------------

  @Test
  void aRenameIsTrimmedAndShowsInTheResponseGetAndMeWithTheRightLogoFlag() throws Exception {
    String clubId = insertClub("Hapoel Example");
    insertUser(clubId, "manager@example.com", PermissionLevel.ADMIN);
    insertUser(clubId, "analyst@example.com", PermissionLevel.VIEW_ONLY);
    String admin = accessToken(login("manager@example.com"));
    String analyst = accessToken(login("analyst@example.com"));

    perform(rename(admin, "{\"name\": \"  הפועל בדיקה \\t\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(clubId))
        .andExpect(jsonPath("$.name").value("הפועל בדיקה"))
        .andExpect(jsonPath("$.hasLogo").value(false));
    expectClub(analyst, clubId, "הפועל בדיקה", false);
    assertThat(storedName(clubId)).isEqualTo("הפועל בדיקה");

    perform(
            multipart(HttpMethod.PUT, "/clubs/me/logo")
                .file(new MockMultipartFile("file", "logo", "image/png", TestImages.png()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
        .andExpect(status().isNoContent());

    perform(rename(admin, "{\"name\": \"Hapoel Example\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Hapoel Example"))
        .andExpect(jsonPath("$.hasLogo").value(true));
    expectClub(analyst, clubId, "Hapoel Example", true);
  }

  /**
   * The name is bound as a value, never spliced into the update's JSON: quotes, braces and
   * operators are stored literally. Renaming to the current name is a 200 too, although Mongo
   * modifies nothing.
   */
  @Test
  void aNameLookingLikeMongoSyntaxIsStoredLiterallyAndTheSameNameAgainIsFine() throws Exception {
    String clubId = insertClub("Hapoel Example");
    insertUser(clubId, "manager@example.com", PermissionLevel.ADMIN);
    String admin = accessToken(login("manager@example.com"));
    String tricky = "x\", \"other\": 1 } ?0 { \"$unset\": { \"createdAt\": \"\" } }";
    String body = "{\"name\": " + jsonString(tricky) + "}";
    Document before = rawClub(clubId);

    perform(rename(admin, body)).andExpect(status().isOk());
    perform(rename(admin, body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(tricky));

    Document stored = rawClub(clubId);
    assertThat(stored.getString("name")).isEqualTo(tricky);
    assertThat(stored.keySet()).containsExactlyElementsOf(before.keySet());
    assertThat(stored.get("createdAt")).isEqualTo(before.get("createdAt"));
  }

  // --- targeted update ---------------------------------------------------------------------------

  /**
   * Proves the write is a {@code $set} of {@code name} alone: a field the application doesn't know
   * about and {@code createdAt} come through unchanged, value for value and in place, and nothing
   * ({@code version}, {@code _class}, ...) is added.
   */
  @Test
  void aRenameChangesOnlyTheNameAndAddsNothing() throws Exception {
    ObjectId id = new ObjectId();
    Document original =
        new Document("_id", id)
            .append("name", "Hapoel Example")
            .append("createdAt", Date.from(Instant.parse("2025-01-02T03:04:05.678Z")))
            .append("futureSetting", "keep")
            .append("nested", new Document("a", 1).append("b", List.of(1L, 2.5, true)));
    mongoTemplate.getCollection("clubs").insertOne(original);
    insertUser(id.toHexString(), "manager@example.com", PermissionLevel.ADMIN);
    String admin = accessToken(login("manager@example.com"));

    perform(rename(admin, "{\"name\": \"Maccabi Example\"}")).andExpect(status().isOk());

    Document expected =
        Document.parse(original.toJson(CANONICAL)).append("name", "Maccabi Example");
    assertThat(rawClub(id.toHexString()).toJson(CANONICAL)).isEqualTo(expected.toJson(CANONICAL));
  }

  // --- club isolation ----------------------------------------------------------------------------

  /**
   * A forged {@code id} / {@code clubId} in the body is ignored (unknown properties): only the
   * token's club is renamed, and club B is untouched and still sees its own name.
   */
  @Test
  void aRenameNeverTouchesAnotherClubEvenWhenTheBodyNamesIt() throws Exception {
    String clubA = insertClub("Club A");
    String clubB = insertClub("Club B");
    insertUser(clubA, "a@example.com", PermissionLevel.ADMIN);
    insertUser(clubB, "b@example.com", PermissionLevel.ADMIN);
    String adminA = accessToken(login("a@example.com"));
    String adminB = accessToken(login("b@example.com"));
    Document clubBBefore = rawClub(clubB);

    perform(
            rename(
                adminA,
                "{\"name\": \"x\", \"id\": \"%s\", \"clubId\": \"%s\", \"_id\": \"%s\"}"
                    .formatted(clubB, clubB, clubB)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(clubA))
        .andExpect(jsonPath("$.name").value("x"));

    assertThat(storedName(clubA)).isEqualTo("x");
    assertThat(rawClub(clubB).toJson(CANONICAL)).isEqualTo(clubBBefore.toJson(CANONICAL));
    assertThat(mongoTemplate.count(new Query(), Club.class)).isEqualTo(2);
    expectClub(adminB, clubB, "Club B", false);
  }

  // --- caller re-check ---------------------------------------------------------------------------

  /**
   * A deactivated admin's still-valid token can't rename the club, while the same token can still
   * read it until it expires — the accepted read window.
   */
  @Test
  void aDeactivatedAdminsStillValidTokenCanReadButNotRename() throws Exception {
    String clubId = insertClub("Hapoel Example");
    User a1 = insertUser(clubId, "a1@example.com", PermissionLevel.ADMIN);
    insertUser(clubId, "a2@example.com", PermissionLevel.ADMIN);
    String a1Token = accessToken(login("a1@example.com"));
    String a2Token = accessToken(login("a2@example.com"));

    perform(
            post("/auth/users/" + a1.getId() + "/deactivate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + a2Token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));

    perform(rename(a1Token, "{\"name\": \"Taken Over\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
    assertThat(storedName(clubId)).isEqualTo("Hapoel Example");

    perform(get("/clubs/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + a1Token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Hapoel Example"));
  }

  // --- last write wins ---------------------------------------------------------------------------

  @Test
  void twoRenamesInARowTheSecondIsStoredAndEachGetsWhatItWrote() throws Exception {
    String clubId = insertClub("Hapoel Example");
    insertUser(clubId, "a1@example.com", PermissionLevel.ADMIN);
    insertUser(clubId, "a2@example.com", PermissionLevel.ADMIN);
    String a1 = accessToken(login("a1@example.com"));
    String a2 = accessToken(login("a2@example.com"));

    perform(rename(a1, "{\"name\": \"First\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("First"));
    perform(rename(a2, "{\"name\": \"Second\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Second"));

    assertThat(storedName(clubId)).isEqualTo("Second");
    expectClub(a1, clubId, "Second", false);
  }

  /**
   * A rename landing between another request's update and its re-read: that request returns the
   * later name, i.e. what's stored, not what it sent. The interleaving is forced deterministically
   * by a repository that runs the other rename just before delegating the re-read, on a real
   * MongoDB — no sleeps or threads. Driven through the service rather than HTTP, as the hook needs
   * a repository wrapper the application context doesn't have.
   */
  @Test
  void aRenameLandingBeforeTheReReadIsWhatTheEarlierRequestReturns() {
    String clubId = insertClub("Hapoel Example");
    User admin = insertUser(clubId, "manager@example.com", PermissionLevel.ADMIN);
    ClubRepository interleaving =
        mock(ClubRepository.class, AdditionalAnswers.delegatesTo(clubRepository));
    doAnswer(
            invocation -> {
              clubRepository.updateNameByClubId(clubId, "Later");
              return clubRepository.findById(invocation.getArgument(0));
            })
        .when(interleaving)
        .findById(anyString());
    ClubSettingsService service =
        new ClubSettingsService(activeCallerCheck, interleaving, clubLogoService);
    AuthenticatedUser caller = new AuthenticatedUser(admin.getId(), clubId, PermissionLevel.ADMIN);

    clubContext.setClubId(clubId); // as JwtAuthenticationFilter does
    ClubResponse response = service.update(caller, new UpdateClubRequest("Earlier"));

    assertThat(response.name()).isEqualTo("Later");
    assertThat(storedName(clubId)).isEqualTo("Later");
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** {@code GET /clubs/me} and {@code /me}'s {@code club} both show exactly this club. */
  private void expectClub(String accessToken, String clubId, String name, boolean hasLogo)
      throws Exception {
    String club =
        perform(get("/clubs/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> expected = Map.of("id", clubId, "name", name, "hasLogo", hasLogo);
    assertThat(JsonPath.<Map<String, Object>>read(club, "$")).isEqualTo(expected);

    String me =
        perform(get("/auth/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<Map<String, Object>>read(me, "$.club")).isEqualTo(expected);
  }

  /** Performs the request and checks that it left no clubId behind on this thread. */
  private ResultActions perform(RequestBuilder request) throws Exception {
    ResultActions result = mockMvc.perform(request);
    assertThat(clubContext.getClubId()).as("ClubContext after the request").isEmpty();
    return result;
  }

  private static RequestBuilder rename(String accessToken, String body) {
    return patch("/clubs/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private MvcResult login(String email) throws Exception {
    return perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
        .andExpect(status().isOk())
        .andReturn();
  }

  /** A club inserted the way the bootstrap does: an {@code ObjectId} {@code _id}. */
  private String insertClub(String name) {
    Club club = new Club();
    club.setName(name);
    String id = mongoTemplate.insert(club).getId();
    assertThat(ObjectId.isValid(id)).isTrue();
    return id;
  }

  private User insertUser(String clubId, String email, PermissionLevel permissionLevel) {
    User user = new User();
    user.setClubId(clubId);
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(PASSWORD));
    user.setTitle(Title.CLUB_MANAGER);
    user.setPermissionLevel(permissionLevel);
    user.setFullName("Dana Levi");
    return mongoTemplate.insert(user);
  }

  private String storedName(String clubId) {
    return rawClub(clubId).getString("name");
  }

  private Document rawClub(String clubId) {
    return mongoTemplate
        .getCollection("clubs")
        .find(new Document("_id", new ObjectId(clubId)))
        .first();
  }

  private static String jsonString(String value) {
    return new Document("v", value).toJson().replaceFirst("^\\{\"v\": ", "").replaceFirst("}$", "");
  }

  private static String accessToken(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }
}
