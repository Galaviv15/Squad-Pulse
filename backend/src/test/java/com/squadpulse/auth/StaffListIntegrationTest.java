package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import com.squadpulse.common.ImageKind;
import com.squadpulse.common.ImageOwner;
import com.squadpulse.common.ImageStorage;
import com.squadpulse.common.ImageType;
import com.squadpulse.common.TestImages;
import com.squadpulse.common.ValidatedImage;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code GET /auth/users} through the whole application (KAN-36): the security chain, the
 * club-scoped user query and the club-scoped photo query on a real MongoDB, and real logins,
 * invitations and activations on a real Redis. {@link EmailSender} is a mock, so the activation
 * code is read from its invocation.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
class StaffListIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String NEW_PASSWORD = "a-brand-new-password";
  private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

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
  @Autowired private ImageStorage imageStorage;
  @MockitoBean private EmailSender emailSender;

  private Club clubA;
  private Club clubB;
  private User adminA;
  private String adminAToken;

  @BeforeEach
  void createClubsAndAdmins() throws Exception {
    clubA = insertClub("Club A");
    clubB = insertClub("Club B");
    adminA = insertUser(clubA, "admin-a@example.com", "Dana Levi", PermissionLevel.ADMIN);
    adminAToken = login("admin-a@example.com", PASSWORD);
  }

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

  /**
   * The admin, an activated user with a photo, an invited user and a deactivated one: all four,
   * active users by name first, then the deactivated one — even though its name sorts first.
   */
  @Test
  void listsEveryUserOfTheClubInTheAgreedOrderWithTheirFlags() throws Exception {
    User coach = insertUser(clubA, "coach@example.com", "Moshe Peretz", PermissionLevel.EDIT_FULL);
    uploadPhoto(coach);
    String invitedId = invite("invited@example.com", "Noa Cohen");
    User former = insertUser(clubA, "former@example.com", "Avi Golan", PermissionLevel.VIEW_ONLY);
    deactivate(former);

    String body =
        perform(list(adminAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<String>>read(body, "$[*].id"))
        .containsExactly(adminA.getId(), coach.getId(), invitedId, former.getId());
    assertThat(JsonPath.<List<String>>read(body, "$[*].fullName"))
        .containsExactly("Dana Levi", "Moshe Peretz", "Noa Cohen", "Avi Golan");
    assertThat(JsonPath.<List<Boolean>>read(body, "$[*].active"))
        .containsExactly(true, true, true, false);
    assertThat(JsonPath.<List<Boolean>>read(body, "$[*].activated"))
        .containsExactly(true, true, false, true);
    assertThat(JsonPath.<List<Boolean>>read(body, "$[*].hasPhoto"))
        .containsExactly(false, true, false, false);
    assertThat(JsonPath.<Map<String, Object>>read(body, "$[0]").keySet())
        .containsExactly(
            "id",
            "email",
            "fullName",
            "title",
            "permissionLevel",
            "dateOfBirth",
            "active",
            "hasPhoto",
            "activated");
    assertThat(body).doesNotContain("password", "$argon2", "version", "clubId", clubA.getId());
  }

  /**
   * Club B has its own users, one with a photo — and a photo stored in club B under club A's
   * coach's own id, the worst case for the photo flag. Neither club sees the other's users, and B's
   * photos don't touch A's {@code hasPhoto}. And the reverse.
   */
  @Test
  void neverListsAnotherClubsUsersAndIgnoresTheirPhotos() throws Exception {
    User coachA =
        insertUser(clubA, "coach-a@example.com", "Moshe Peretz", PermissionLevel.EDIT_FULL);
    User adminB = insertUser(clubB, "admin-b@example.com", "Yael Shani", PermissionLevel.ADMIN);
    User coachB = insertUser(clubB, "coach-b@example.com", "Rami Ben", PermissionLevel.EDIT_FULL);
    String adminBToken = login("admin-b@example.com", PASSWORD);
    uploadPhoto(adminBToken, coachB);
    storePhotoInClub(clubB, coachA.getId());
    storePhotoInClub(clubA, coachB.getId());

    String bodyA = perform(list(adminAToken)).andReturn().getResponse().getContentAsString();
    String bodyB = perform(list(adminBToken)).andReturn().getResponse().getContentAsString();

    assertThat(JsonPath.<List<String>>read(bodyA, "$[*].id"))
        .containsExactly(adminA.getId(), coachA.getId());
    assertThat(JsonPath.<List<Boolean>>read(bodyA, "$[*].hasPhoto")).containsExactly(false, false);
    assertThat(bodyA).doesNotContain(adminB.getId(), coachB.getId(), "Yael", "Rami", "-b@");

    assertThat(JsonPath.<List<String>>read(bodyB, "$[*].id"))
        .containsExactly(coachB.getId(), adminB.getId());
    assertThat(JsonPath.<List<Boolean>>read(bodyB, "$[*].hasPhoto")).containsExactly(true, false);
    assertThat(bodyB).doesNotContain(adminA.getId(), coachA.getId(), "Dana", "Moshe", "-a@");
  }

  @Test
  void anInvitedUserIsListedAsNotActivatedUntilTheyUseTheirCode() throws Exception {
    String invitedId = invite("new.coach@example.com", "Noa Cohen");
    perform(list(adminAToken))
        .andExpect(jsonPath("$[1].id").value(invitedId))
        .andExpect(jsonPath("$[1].active").value(true))
        .andExpect(jsonPath("$[1].activated").value(false));

    // An admin may change a not-yet-activated user's level: still not activated.
    changeLevel(invitedId, "EDIT_PARTIAL").andExpect(jsonPath("$.activated").value(false));

    perform(
            post("/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted(
                            "new.coach@example.com",
                            lastCodeSentTo("new.coach@example.com"),
                            NEW_PASSWORD)))
        .andExpect(status().isNoContent());

    perform(list(adminAToken))
        .andExpect(jsonPath("$[1].id").value(invitedId))
        .andExpect(jsonPath("$[1].activated").value(true));
    changeLevel(invitedId, "EDIT_FULL").andExpect(jsonPath("$.activated").value(true));
    login("new.coach@example.com", NEW_PASSWORD);
  }

  /** {@code GET /auth/users} and {@code GET /auth/users/me} don't clash. */
  @Test
  void meStillReturnsTheCallerNotTheList() throws Exception {
    insertUser(clubA, "coach@example.com", "Moshe Peretz", PermissionLevel.EDIT_FULL);

    perform(get("/auth/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(adminA.getId()))
        .andExpect(jsonPath("$.activated").value(true))
        .andExpect(jsonPath("$.club.id").value(clubA.getId()));
  }

  @Test
  void aNonAdminGets403() throws Exception {
    insertUser(clubA, "viewer@example.com", "Moshe Peretz", PermissionLevel.EDIT_FULL);
    String token = login("viewer@example.com", PASSWORD);

    perform(list(token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Access denied"));
  }

  @Test
  void listingWritesNothing() throws Exception {
    User coach = insertUser(clubA, "coach@example.com", "Moshe Peretz", PermissionLevel.EDIT_FULL);
    User before = mongoTemplate.findById(coach.getId(), User.class);
    User adminBefore = mongoTemplate.findById(adminA.getId(), User.class);

    perform(list(adminAToken)).andExpect(status().isOk());
    perform(list(adminAToken)).andExpect(status().isOk());

    User after = mongoTemplate.findById(coach.getId(), User.class);
    User adminAfter = mongoTemplate.findById(adminA.getId(), User.class);
    assertThat(after.getVersion()).isEqualTo(before.getVersion());
    assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    assertThat(adminAfter.getVersion()).isEqualTo(adminBefore.getVersion());
    assertThat(adminAfter.getUpdatedAt()).isEqualTo(adminBefore.getUpdatedAt());
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** Performs the request and checks that it left no clubId behind on this thread. */
  private ResultActions perform(RequestBuilder request) throws Exception {
    ResultActions result = mockMvc.perform(request);
    assertThat(clubContext.getClubId()).as("ClubContext after the request").isEmpty();
    return result;
  }

  private static RequestBuilder list(String accessToken) {
    return get("/auth/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
  }

  private String login(String email, String password) throws Exception {
    String body =
        perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }

  private String invite(String email, String fullName) throws Exception {
    String body =
        perform(
                post("/auth/users/invite")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email": "%s", "fullName": "%s", "title": "ANALYST",
                         "permissionLevel": "VIEW_ONLY"}
                        """
                            .formatted(email, fullName)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.activated").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private ResultActions changeLevel(String userId, String level) throws Exception {
    return perform(
            patch("/auth/users/" + userId + "/permission-level")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissionLevel\": \"" + level + "\"}"))
        .andExpect(status().isOk());
  }

  private void uploadPhoto(User user) throws Exception {
    uploadPhoto(adminAToken, user);
  }

  private void uploadPhoto(String adminToken, User user) throws Exception {
    perform(
            multipart(HttpMethod.PUT, "/users/" + user.getId() + "/photo")
                .file(new MockMultipartFile("file", "photo", "image/png", TestImages.png()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
        .andExpect(status().isNoContent());
  }

  /** A staff photo stored in {@code club} under any owner id, bypassing the API's user check. */
  private void storePhotoInClub(Club club, String ownerId) {
    clubContext.callAs(
        club.getId(),
        () -> {
          imageStorage.store(
              new ImageOwner(ImageKind.STAFF_PHOTO, ownerId),
              new ValidatedImage(TestImages.png(), ImageType.PNG));
          return null;
        });
  }

  private String lastCodeSentTo(String email) {
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(emailSender, atLeastOnce()).send(eq(email), anyString(), body.capture());
    Matcher matcher = CODE.matcher(body.getValue());
    assertThat(matcher.find()).as("a 6-digit code in the email to %s", email).isTrue();
    return matcher.group(1);
  }

  private void deactivate(User user) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(user.getId())),
        Update.update("active", false),
        User.class);
  }

  private Club insertClub(String name) {
    Club club = new Club();
    club.setName(name);
    return mongoTemplate.insert(club);
  }

  private User insertUser(Club club, String email, String fullName, PermissionLevel level) {
    User user = new User();
    user.setClubId(club.getId());
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(PASSWORD));
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(level);
    user.setFullName(fullName);
    user.setDateOfBirth(LocalDate.of(1985, 3, 1));
    return mongoTemplate.insert(user);
  }
}
