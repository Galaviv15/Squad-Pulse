package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The club logo end to end on a real MongoDB (GridFS), through HTTP (KAN-30): the upload / serve /
 * replace / delete lifecycle, club isolation, independence from a player photo with the same owner
 * id, {@code club.hasLogo} on {@code /me}, the untouched {@link Club} document, and a missing club.
 * The storage itself is covered in {@code common.GridFsImageStorageIntegrationTest}.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
class ClubLogoIntegrationTest {

  private static final String URL = "/clubs/me/logo";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private JwtService jwtService;

  private Club clubA;
  private Club clubB;

  @BeforeEach
  void createClubs() {
    clubA = insertClub("Club A");
    clubB = insertClub("Club B");
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

  // --- lifecycle ---------------------------------------------------------------------------------

  /**
   * Served exactly as uploaded, typed by content, with nosniff and no-store; a second upload
   * replaces the first and leaves one file.
   */
  @Test
  void anUploadedLogoIsServedByteForByteAndANewOneReplacesIt() throws Exception {
    upload(clubA, TestImages.png()).andExpect(status().isNoContent());

    getLogo(clubA)
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().longValue("Content-Length", TestImages.png().length))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(content().bytes(TestImages.png()));

    upload(clubA, TestImages.jpeg()).andExpect(status().isNoContent());

    getLogo(clubA)
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"))
        .andExpect(content().bytes(TestImages.jpeg()));
    assertThat(logoFiles(clubA))
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.getString("filename")).isEqualTo("CLUB_LOGO/" + clubA.getId());
              assertThat(file.get("metadata", Document.class).getString("clubId"))
                  .isEqualTo(clubA.getId());
            });
  }

  @Test
  void deletingTheLogoRemovesItAndIsIdempotent() throws Exception {
    upload(clubA, TestImages.png());

    remove(clubA).andExpect(status().isNoContent());

    getLogo(clubA)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("This club has no logo"));
    assertThat(logoFiles(clubA)).isEmpty();
    remove(clubA).andExpect(status().isNoContent());
  }

  static Stream<Arguments> invalidUploads() {
    return Stream.of(
        Arguments.of(
            "SVG",
            multipart(HttpMethod.PUT, URL)
                .file(
                    new MockMultipartFile("file", "logo.svg", "image/svg+xml", TestImages.svg()))),
        Arguments.of(
            "empty file",
            multipart(HttpMethod.PUT, URL)
                .file(new MockMultipartFile("file", "logo.png", "image/png", new byte[0]))),
        Arguments.of(
            "missing file part",
            multipart(HttpMethod.PUT, URL)
                .file(new MockMultipartFile("logo", "logo.png", "image/png", TestImages.png()))),
        Arguments.of(
            "not multipart", put(URL).contentType(MediaType.IMAGE_PNG).content(TestImages.png())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidUploads")
  void anInvalidUploadIs400AndStoresNothing(
      String description, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    mockMvc
        .perform(request.header(HttpHeaders.AUTHORIZATION, admin(clubA)))
        .andExpect(status().isBadRequest());

    assertThat(mongoTemplate.count(new Query(), "images.files")).isZero();
    assertThat(mongoTemplate.count(new Query(), "images.chunks")).isZero();
  }

  // --- club isolation ----------------------------------------------------------------------------

  @Test
  void anotherClubNeitherSeesNorRemovesTheLogo() throws Exception {
    upload(clubA, TestImages.png()).andExpect(status().isNoContent());
    Document before = logoFiles(clubA).getFirst();
    insertUser(clubB, "b@example.com");

    getLogo(clubB)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("This club has no logo"));
    remove(clubB).andExpect(status().isNoContent());
    mockMvc
        .perform(me("b@example.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.club.id").value(clubB.getId()))
        .andExpect(jsonPath("$.club.hasLogo").value(false));

    assertThat(logoFiles(clubA)).containsExactly(before);
    getLogo(clubA).andExpect(status().isOk()).andExpect(content().bytes(TestImages.png()));
  }

  // --- no collision with player photos ----------------------------------------------------------

  /**
   * A player whose id equals the club's id: the photo and the logo have the same owner id, but
   * different kinds, so each is stored, served and removed on its own.
   */
  @Test
  void aPlayerPhotoWithTheClubsIdAsOwnerStaysIndependentOfTheLogo() throws Exception {
    String playerId = insertPlayerWithId(clubA, clubA.getId());
    uploadPhoto(clubA, playerId, TestImages.webp()).andExpect(status().isNoContent());

    upload(clubA, TestImages.png()).andExpect(status().isNoContent());
    getLogo(clubA).andExpect(content().bytes(TestImages.png()));
    getPhoto(clubA, playerId).andExpect(content().bytes(TestImages.webp()));

    remove(clubA).andExpect(status().isNoContent());
    getLogo(clubA).andExpect(status().isNotFound());
    getPhoto(clubA, playerId)
        .andExpect(status().isOk())
        .andExpect(content().bytes(TestImages.webp()));

    upload(clubA, TestImages.jpeg()).andExpect(status().isNoContent());
    mockMvc
        .perform(delete(photoUrl(playerId)).header(HttpHeaders.AUTHORIZATION, admin(clubA)))
        .andExpect(status().isNoContent());
    getPhoto(clubA, playerId).andExpect(status().isNotFound());
    getLogo(clubA).andExpect(status().isOk()).andExpect(content().bytes(TestImages.jpeg()));
  }

  // --- /me ---------------------------------------------------------------------------------------

  @Test
  void meReportsHasLogoBeforeUploadAfterAndAfterDelete() throws Exception {
    insertUser(clubA, "a@example.com");

    mockMvc
        .perform(me("a@example.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.club.hasLogo").value(false));
    upload(clubA, TestImages.png()).andExpect(status().isNoContent());
    mockMvc
        .perform(me("a@example.com"))
        .andExpect(jsonPath("$.club.id").value(clubA.getId()))
        .andExpect(jsonPath("$.club.name").value("Club A"))
        .andExpect(jsonPath("$.club.hasLogo").value(true));
    remove(clubA).andExpect(status().isNoContent());
    mockMvc.perform(me("a@example.com")).andExpect(jsonPath("$.club.hasLogo").value(false));
  }

  // --- the club document -------------------------------------------------------------------------

  @Test
  void anUploadDoesNotModifyTheClubDocument() throws Exception {
    Document before = rawClub(clubA);

    upload(clubA, TestImages.png()).andExpect(status().isNoContent());
    remove(clubA).andExpect(status().isNoContent());
    upload(clubA, TestImages.jpeg()).andExpect(status().isNoContent());

    assertThat(rawClub(clubA)).isEqualTo(before);
  }

  /** A token for a club that doesn't exist (a data-integrity bug): a generic 500, no file. */
  @Test
  void aMissingClubIsAGeneric500AndStoresNothing() throws Exception {
    mongoTemplate.remove(Query.query(Criteria.where("_id").is(clubA.getId())), Club.class);

    String body =
        upload(clubA, TestImages.png())
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.details").isEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(clubA.getId()).doesNotContain("not found");
    assertThat(mongoTemplate.count(new Query(), "images.files")).isZero();
    assertThat(mongoTemplate.count(new Query(), "images.chunks")).isZero();
  }

  // --- helpers -----------------------------------------------------------------------------------

  private ResultActions upload(Club club, byte[] content) throws Exception {
    return mockMvc.perform(
        multipart(HttpMethod.PUT, URL)
            .file(new MockMultipartFile("file", "logo", "image/jpeg", content))
            .header(HttpHeaders.AUTHORIZATION, admin(club)));
  }

  private ResultActions getLogo(Club club) throws Exception {
    return mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, viewer(club)));
  }

  private ResultActions remove(Club club) throws Exception {
    return mockMvc.perform(delete(URL).header(HttpHeaders.AUTHORIZATION, admin(club)));
  }

  private ResultActions uploadPhoto(Club club, String playerId, byte[] content) throws Exception {
    return mockMvc.perform(
        multipart(HttpMethod.PUT, photoUrl(playerId))
            .file(new MockMultipartFile("file", "photo", "image/jpeg", content))
            .header(HttpHeaders.AUTHORIZATION, admin(club)));
  }

  private ResultActions getPhoto(Club club, String playerId) throws Exception {
    return mockMvc.perform(get(photoUrl(playerId)).header(HttpHeaders.AUTHORIZATION, viewer(club)));
  }

  private static String photoUrl(String playerId) {
    return "/squad/players/" + playerId + "/photo";
  }

  private RequestBuilder me(String email) {
    User user = mongoTemplate.findOne(Query.query(Criteria.where("email").is(email)), User.class);
    return get("/auth/users/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(user).value());
  }

  private String admin(Club club) {
    return bearer(club, PermissionLevel.ADMIN);
  }

  private String viewer(Club club) {
    return bearer(club, PermissionLevel.VIEW_ONLY);
  }

  /** A token for a user of {@code club} who needn't exist — the logo endpoints never load one. */
  private String bearer(Club club, PermissionLevel level) {
    User user = new User();
    user.setId("user-of-" + club.getId());
    user.setClubId(club.getId());
    user.setPermissionLevel(level);
    return "Bearer " + jwtService.issue(user).value();
  }

  private Club insertClub(String name) {
    Club club = new Club();
    club.setName(name);
    return mongoTemplate.insert(club);
  }

  private void insertUser(Club club, String email) {
    User user = new User();
    user.setClubId(club.getId());
    user.setEmail(email);
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(PermissionLevel.VIEW_ONLY);
    user.setFullName("Dana Levi");
    mongoTemplate.insert(user);
  }

  /** Inserted directly: the API never lets a client choose a player's id. */
  private String insertPlayerWithId(Club club, String id) {
    Player player = new Player();
    player.setId(id);
    player.setClubId(club.getId());
    player.setFullName("Eran Zahavi");
    player.setPrimaryPosition(Position.ST);
    player.setDateOfBirth(LocalDate.of(1995, 5, 20));
    return mongoTemplate.insert(player).getId();
  }

  private Document rawClub(Club club) {
    return mongoTemplate
        .getCollection("clubs")
        .find(new Document("_id", new ObjectId(club.getId())))
        .first();
  }

  /** The GridFS files for this club's logo, whatever their club metadata says. */
  private List<Document> logoFiles(Club club) {
    return mongoTemplate.find(
        Query.query(
            Criteria.where("metadata.kind")
                .is("CLUB_LOGO")
                .and("metadata.ownerId")
                .is(club.getId())),
        Document.class,
        "images.files");
  }
}
