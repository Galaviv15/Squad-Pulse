package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.redis.testcontainers.RedisContainer;
import com.squadpulse.common.TestImages;
import com.squadpulse.squad.Player;
import com.squadpulse.squad.Position;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Staff photos end to end on a real MongoDB (GridFS), through HTTP (KAN-32): the own-photo and
 * admin lifecycles, permissions, validation, unknown and deactivated users, the untouched {@link
 * User} document, club isolation, independence from a player photo and the club logo with the same
 * owner id, and {@code hasPhoto} on {@code /me}, invite and permission-level responses. The storage
 * itself is covered in {@code common.GridFsImageStorageIntegrationTest}.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
class StaffPhotoIntegrationTest {

  private static final String ME = "/users/me/photo";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  /** Invitations store an activation code in Redis. */
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
  @Autowired private JwtService jwtService;

  private Club clubA;
  private Club clubB;
  private User adminA;
  private User viewerA;
  private User coachA;
  private User adminB;

  @BeforeEach
  void createClubsAndUsers() {
    clubA = insertClub(null, "Club A");
    clubB = insertClub(null, "Club B");
    adminA = insertUser(null, clubA, "admin-a@example.com", PermissionLevel.ADMIN);
    viewerA = insertUser(null, clubA, "viewer-a@example.com", PermissionLevel.VIEW_ONLY);
    coachA = insertUser(null, clubA, "coach-a@example.com", PermissionLevel.EDIT_FULL);
    adminB = insertUser(null, clubB, "admin-b@example.com", PermissionLevel.ADMIN);
  }

  @AfterEach
  void tearDown() {
    // Remove documents rather than dropping the collections, which would drop their indexes.
    mongoTemplate.remove(new Query(), Club.class);
    mongoTemplate.remove(new Query(), User.class);
    mongoTemplate.remove(new Query(), Player.class);
    mongoTemplate.remove(new Query(), "images.files");
    mongoTemplate.remove(new Query(), "images.chunks");
  }

  // --- own photo lifecycle -----------------------------------------------------------------------

  /**
   * A {@code VIEW_ONLY} user manages their own photo: it's served byte for byte at both paths, to
   * them and to another club member, typed by content with nosniff and no-store; a second upload
   * replaces the first and leaves one file; delete removes it and is idempotent.
   */
  @Test
  void aViewerUploadsReplacesAndRemovesTheirOwnPhoto() throws Exception {
    upload(viewerA, ME, TestImages.png()).andExpect(status().isNoContent());

    for (ResultActions served : List.of(getPhoto(viewerA, ME), getPhoto(coachA, urlOf(viewerA)))) {
      served
          .andExpect(status().isOk())
          .andExpect(header().string("Content-Type", "image/png"))
          .andExpect(header().longValue("Content-Length", TestImages.png().length))
          .andExpect(header().string("X-Content-Type-Options", "nosniff"))
          .andExpect(header().string("Cache-Control", containsString("no-store")))
          .andExpect(content().bytes(TestImages.png()));
    }

    upload(viewerA, ME, TestImages.jpeg()).andExpect(status().isNoContent());

    getPhoto(viewerA, ME)
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"))
        .andExpect(content().bytes(TestImages.jpeg()));
    assertThat(photoFiles(viewerA))
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.getString("filename")).isEqualTo("STAFF_PHOTO/" + viewerA.getId());
              assertThat(file.get("metadata", Document.class).getString("clubId"))
                  .isEqualTo(clubA.getId());
            });

    remove(viewerA, ME).andExpect(status().isNoContent());

    getPhoto(viewerA, ME)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("This user has no photo"));
    assertThat(photoFiles(viewerA)).isEmpty();
    remove(viewerA, ME).andExpect(status().isNoContent());
  }

  // --- admin on another user ---------------------------------------------------------------------

  @Test
  void anAdminSetsAndRemovesAnotherUsersPhoto() throws Exception {
    upload(adminA, urlOf(viewerA), TestImages.webp()).andExpect(status().isNoContent());

    getPhoto(viewerA, ME)
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/webp"))
        .andExpect(content().bytes(TestImages.webp()));

    remove(adminA, urlOf(viewerA)).andExpect(status().isNoContent());

    getPhoto(viewerA, ME).andExpect(status().isNotFound());
    assertThat(photoFiles(viewerA)).isEmpty();
  }

  @Test
  void anAdminMayUseTheIdFormOnThemselves() throws Exception {
    upload(adminA, urlOf(adminA), TestImages.png()).andExpect(status().isNoContent());

    getPhoto(adminA, ME).andExpect(status().isOk()).andExpect(content().bytes(TestImages.png()));
  }

  @Test
  void aNonAdminCantSetOrRemoveAnotherUsersPhoto() throws Exception {
    upload(viewerA, ME, TestImages.png()).andExpect(status().isNoContent());
    Document before = photoFiles(viewerA).getFirst();

    upload(coachA, urlOf(viewerA), TestImages.jpeg())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Access denied"));
    remove(coachA, urlOf(viewerA)).andExpect(status().isForbidden());
    upload(viewerA, urlOf(coachA), TestImages.jpeg()).andExpect(status().isForbidden());

    assertThat(photoFiles(viewerA)).containsExactly(before);
    assertThat(photoFiles(coachA)).isEmpty();
  }

  // --- validation --------------------------------------------------------------------------------

  static Stream<Arguments> invalidUploads() {
    return Stream.of(
        Arguments.of(
            "SVG",
            multipart(HttpMethod.PUT, ME)
                .file(
                    new MockMultipartFile("file", "photo.svg", "image/svg+xml", TestImages.svg()))),
        Arguments.of(
            "empty file",
            multipart(HttpMethod.PUT, ME)
                .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[0]))),
        Arguments.of(
            "missing file part",
            multipart(HttpMethod.PUT, ME)
                .file(new MockMultipartFile("photo", "photo.png", "image/png", TestImages.png()))),
        Arguments.of(
            "not multipart", put(ME).contentType(MediaType.IMAGE_PNG).content(TestImages.png())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidUploads")
  void anInvalidUploadIs400AndStoresNothing(
      String description, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    mockMvc
        .perform(request.header(HttpHeaders.AUTHORIZATION, bearer(viewerA)))
        .andExpect(status().isBadRequest());

    assertNoImagesStored();
  }

  // --- unknown and deactivated users -------------------------------------------------------------

  @Test
  void anUnknownUserIs404AndNothingIsStored() throws Exception {
    String unknown = "/users/" + new ObjectId().toHexString() + "/photo";

    upload(adminA, unknown, TestImages.png())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("User not found"));
    getPhoto(adminA, unknown).andExpect(status().isNotFound());
    remove(adminA, unknown).andExpect(status().isNotFound());

    assertNoImagesStored();
  }

  /**
   * Like a released player: the photo stays readable, but nobody — not an admin, not the user with
   * a still-valid token — can change or remove it.
   */
  @Test
  void aDeactivatedUsersPhotoIsReadableButNotChangeable() throws Exception {
    upload(viewerA, ME, TestImages.png()).andExpect(status().isNoContent());
    String viewerToken = bearer(viewerA); // issued while still active
    Document before = photoFiles(viewerA).getFirst();
    deactivate(viewerA);

    getPhoto(coachA, urlOf(viewerA))
        .andExpect(status().isOk())
        .andExpect(content().bytes(TestImages.png()));
    upload(adminA, urlOf(viewerA), TestImages.jpeg())
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("This user has been deactivated; their profile can't be changed"));
    remove(adminA, urlOf(viewerA)).andExpect(status().isConflict());
    mockMvc
        .perform(
            multipart(HttpMethod.PUT, ME)
                .file(new MockMultipartFile("file", "photo", "image/jpeg", TestImages.jpeg()))
                .header(HttpHeaders.AUTHORIZATION, viewerToken))
        .andExpect(status().isConflict());
    mockMvc
        .perform(delete(ME).header(HttpHeaders.AUTHORIZATION, viewerToken))
        .andExpect(status().isConflict());

    assertThat(photoFiles(viewerA)).containsExactly(before);
    getPhoto(adminA, urlOf(viewerA)).andExpect(content().bytes(TestImages.png()));
  }

  // --- the user document -------------------------------------------------------------------------

  @Test
  void uploadAndDeleteDoNotModifyTheUserDocument() throws Exception {
    Document before = rawUser(viewerA);

    upload(viewerA, ME, TestImages.png()).andExpect(status().isNoContent());
    upload(adminA, urlOf(viewerA), TestImages.jpeg()).andExpect(status().isNoContent());
    remove(viewerA, ME).andExpect(status().isNoContent());

    Document after = rawUser(viewerA);
    assertThat(after.get("version")).isEqualTo(before.get("version"));
    assertThat(after.get("updatedAt")).isEqualTo(before.get("updatedAt"));
    assertThat(after).isEqualTo(before);
  }

  // --- club isolation ----------------------------------------------------------------------------

  @Test
  void anotherClubsAdminNeitherSeesNorChangesNorRemovesThePhoto() throws Exception {
    upload(viewerA, ME, TestImages.png()).andExpect(status().isNoContent());
    Document before = photoFiles(viewerA).getFirst();

    getPhoto(adminB, urlOf(viewerA))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("User not found"));
    upload(adminB, urlOf(viewerA), TestImages.jpeg()).andExpect(status().isNotFound());
    remove(adminB, urlOf(viewerA)).andExpect(status().isNotFound());

    assertThat(photoFiles(viewerA)).containsExactly(before);
    getPhoto(viewerA, ME).andExpect(status().isOk()).andExpect(content().bytes(TestImages.png()));
  }

  // --- no collision with other kinds -------------------------------------------------------------

  /**
   * A user, their club and a player that all share one id: the staff photo, the club logo and the
   * player photo have the same owner id but different kinds, so each is stored, served and removed
   * on its own.
   */
  @Test
  void aStaffPhotoStaysIndependentOfALogoAndAPlayerPhotoWithTheSameOwnerId() throws Exception {
    String sharedId = new ObjectId().toHexString();
    Club club = insertClub(sharedId, "Club C");
    User admin = insertUser(sharedId, club, "admin-c@example.com", PermissionLevel.ADMIN);
    insertPlayerWithId(club, sharedId);
    String playerPhoto = "/squad/players/" + sharedId + "/photo";

    upload(admin, "/clubs/me/logo", TestImages.png()).andExpect(status().isNoContent());
    upload(admin, playerPhoto, TestImages.webp()).andExpect(status().isNoContent());
    upload(admin, ME, TestImages.jpeg()).andExpect(status().isNoContent());

    getPhoto(admin, ME).andExpect(content().bytes(TestImages.jpeg()));
    getPhoto(admin, "/clubs/me/logo").andExpect(content().bytes(TestImages.png()));
    getPhoto(admin, playerPhoto).andExpect(content().bytes(TestImages.webp()));

    remove(admin, ME).andExpect(status().isNoContent());
    getPhoto(admin, ME).andExpect(status().isNotFound());
    getPhoto(admin, "/clubs/me/logo")
        .andExpect(status().isOk())
        .andExpect(content().bytes(TestImages.png()));
    getPhoto(admin, playerPhoto)
        .andExpect(status().isOk())
        .andExpect(content().bytes(TestImages.webp()));

    upload(admin, ME, TestImages.png()).andExpect(status().isNoContent());
    remove(admin, "/clubs/me/logo").andExpect(status().isNoContent());
    remove(admin, playerPhoto).andExpect(status().isNoContent());
    getPhoto(admin, ME).andExpect(status().isOk()).andExpect(content().bytes(TestImages.png()));
  }

  // --- hasPhoto ----------------------------------------------------------------------------------

  @Test
  void meReportsHasPhotoBeforeUploadAfterAndAfterDelete() throws Exception {
    me(viewerA).andExpect(status().isOk()).andExpect(jsonPath("$.hasPhoto").value(false));
    upload(viewerA, ME, TestImages.png()).andExpect(status().isNoContent());
    me(viewerA).andExpect(jsonPath("$.hasPhoto").value(true));
    remove(viewerA, ME).andExpect(status().isNoContent());
    me(viewerA).andExpect(jsonPath("$.hasPhoto").value(false));
  }

  @Test
  void anInvitedUserHasNoPhoto() throws Exception {
    mockMvc
        .perform(
            post("/auth/users/invite")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "new@example.com", "fullName": "Noa Cohen", "title": "ANALYST",
                     "permissionLevel": "VIEW_ONLY", "dateOfBirth": "1990-01-01"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.hasPhoto").value(false));
  }

  @Test
  void aPermissionLevelChangeReportsTheTargetsPhoto() throws Exception {
    changeLevel(viewerA, "EDIT_PARTIAL").andExpect(jsonPath("$.hasPhoto").value(false));

    upload(viewerA, ME, TestImages.png()).andExpect(status().isNoContent());

    changeLevel(viewerA, "EDIT_FULL")
        .andExpect(jsonPath("$.permissionLevel").value("EDIT_FULL"))
        .andExpect(jsonPath("$.hasPhoto").value(true));
  }

  // --- helpers -----------------------------------------------------------------------------------

  private ResultActions upload(User caller, String url, byte[] content) throws Exception {
    return mockMvc.perform(
        multipart(HttpMethod.PUT, url)
            .file(new MockMultipartFile("file", "photo", "image/jpeg", content))
            .header(HttpHeaders.AUTHORIZATION, bearer(caller)));
  }

  private ResultActions getPhoto(User caller, String url) throws Exception {
    return mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(caller)));
  }

  private ResultActions remove(User caller, String url) throws Exception {
    return mockMvc.perform(delete(url).header(HttpHeaders.AUTHORIZATION, bearer(caller)));
  }

  private ResultActions me(User caller) throws Exception {
    return mockMvc.perform(get("/auth/users/me").header(HttpHeaders.AUTHORIZATION, bearer(caller)));
  }

  private ResultActions changeLevel(User target, String level) throws Exception {
    return mockMvc
        .perform(
            patch("/auth/users/" + target.getId() + "/permission-level")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissionLevel\": \"" + level + "\"}"))
        .andExpect(status().isOk());
  }

  private static String urlOf(User user) {
    return "/users/" + user.getId() + "/photo";
  }

  private String bearer(User user) {
    return "Bearer " + jwtService.issue(user).value();
  }

  private void deactivate(User user) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(user.getId())),
        Update.update("active", false),
        User.class);
  }

  private void assertNoImagesStored() {
    assertThat(mongoTemplate.count(new Query(), "images.files")).isZero();
    assertThat(mongoTemplate.count(new Query(), "images.chunks")).isZero();
  }

  private Club insertClub(String id, String name) {
    Club club = new Club();
    club.setId(id);
    club.setName(name);
    return mongoTemplate.insert(club);
  }

  private User insertUser(String id, Club club, String email, PermissionLevel level) {
    User user = new User();
    user.setId(id);
    user.setClubId(club.getId());
    user.setEmail(email);
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(level);
    user.setFullName("Dana Levi");
    user.setDateOfBirth(LocalDate.of(1985, 3, 1));
    return mongoTemplate.insert(user);
  }

  /** Inserted directly: the API never lets a client choose a player's id. */
  private void insertPlayerWithId(Club club, String id) {
    Player player = new Player();
    player.setId(id);
    player.setClubId(club.getId());
    player.setFullName("Eran Zahavi");
    player.setPrimaryPosition(Position.ST);
    player.setDateOfBirth(LocalDate.of(1995, 5, 20));
    mongoTemplate.insert(player);
  }

  private Document rawUser(User user) {
    return mongoTemplate
        .getCollection("users")
        .find(new Document("_id", new ObjectId(user.getId())))
        .first();
  }

  /** The GridFS files for this user's photo, whatever their club metadata says. */
  private List<Document> photoFiles(User user) {
    return mongoTemplate.find(
        Query.query(
            Criteria.where("metadata.kind")
                .is("STAFF_PHOTO")
                .and("metadata.ownerId")
                .is(user.getId())),
        Document.class,
        "images.files");
  }
}
