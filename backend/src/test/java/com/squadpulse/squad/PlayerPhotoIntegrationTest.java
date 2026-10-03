package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.ImageKind;
import com.squadpulse.common.ImageOwner;
import com.squadpulse.common.ImageStorage;
import com.squadpulse.common.ImageType;
import com.squadpulse.common.TestImages;
import com.squadpulse.common.ValidatedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Player photos end to end on a real MongoDB (GridFS): the upload / serve / replace / delete
 * lifecycle, club isolation over HTTP, released players, permanent deletion taking the photo with
 * it, a delete racing an upload, the untouched {@code version}, and {@code hasPhoto}. The storage
 * itself — replace semantics, concurrent stores, the index — is covered in {@code
 * common.GridFsImageStorageIntegrationTest}.
 *
 * <p>{@link ImageStorage} is a spy on the real one, to count the list's storage queries and to run
 * a "concurrent" delete deterministically right after a store.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestAccessTokens.class)
class PlayerPhotoIntegrationTest {

  private static final String CLUB_A = "club-a";
  private static final String CLUB_B = "club-b";
  private static final String RELEASED_MESSAGE =
      "This player has been released; re-activate them before editing";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private TestAccessTokens tokens;
  @MockitoSpyBean private ImageStorage imageStorage;

  @AfterEach
  void tearDown() {
    reset(imageStorage);
    mongoTemplate.remove(new Query(), Player.class);
    mongoTemplate.remove(new Query(), "images.files");
    mongoTemplate.remove(new Query(), "images.chunks");
  }

  // --- lifecycle ---------------------------------------------------------------------------------

  /**
   * Served exactly as uploaded, typed by content: a PNG declared as {@code image/jpeg} named {@code
   * x.jpg} comes back as {@code image/png}, and the client's filename isn't stored.
   */
  @Test
  void anUploadedPhotoIsServedByteForByteWithItsDetectedType() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);

    mockMvc
        .perform(
            asEditor(
                CLUB_A,
                multipart(HttpMethod.PUT, photoUrl(id))
                    .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", TestImages.png()))))
        .andExpect(status().isNoContent());

    getPhoto(CLUB_A, id)
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().longValue("Content-Length", TestImages.png().length))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(content().bytes(TestImages.png()));
    assertThat(photoFiles(id))
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.getString("filename")).isEqualTo("PLAYER_PHOTO/" + id);
              assertThat(file.toJson()).doesNotContain("x.jpg");
            });
  }

  @Test
  void aNewPhotoReplacesTheOldOneLeavingOneFile() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);
    upload(CLUB_A, id, TestImages.png()).andExpect(status().isNoContent());

    upload(CLUB_A, id, TestImages.webp()).andExpect(status().isNoContent());

    getPhoto(CLUB_A, id)
        .andExpect(header().string("Content-Type", "image/webp"))
        .andExpect(content().bytes(TestImages.webp()));
    assertThat(photoFiles(id)).hasSize(1);
  }

  @Test
  void deletingThePhotoRemovesItAndIsIdempotent() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);
    upload(CLUB_A, id, TestImages.jpeg());

    deletePhoto(CLUB_A, id).andExpect(status().isNoContent());

    getPhoto(CLUB_A, id)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("This player has no photo"));
    assertThat(photoFiles(id)).isEmpty();
    deletePhoto(CLUB_A, id).andExpect(status().isNoContent());
  }

  @Test
  void aPlayerWithoutAPhotoIs404() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);

    getPhoto(CLUB_A, id)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("This player has no photo"));
    getPhoto(CLUB_A, "000000000000000000000000")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
  }

  // --- club isolation ----------------------------------------------------------------------------

  /** A 404 on all three — before storage is touched — and club A's file is untouched. */
  @Test
  void anotherClubCannotReadReplaceOrDeleteAPhoto() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);
    upload(CLUB_A, id, TestImages.png());
    Document before = photoFiles(id).getFirst();
    clearInvocations(imageStorage);

    getPhoto(CLUB_B, id)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
    upload(CLUB_B, id, TestImages.jpeg())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
    deletePhoto(CLUB_B, id)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));

    verify(imageStorage, never()).store(any(), any());
    verify(imageStorage, never()).find(any());
    verify(imageStorage, never()).delete(any());
    assertThat(photoFiles(id)).containsExactly(before);
    getPhoto(CLUB_A, id).andExpect(content().bytes(TestImages.png()));
  }

  // --- released players --------------------------------------------------------------------------

  @Test
  void aReleasedPlayersPhotoIsReadOnlyAndSurvivesReactivation() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);
    upload(CLUB_A, id, TestImages.png());
    release(CLUB_A, id, 0).andExpect(status().isOk());

    upload(CLUB_A, id, TestImages.jpeg())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(RELEASED_MESSAGE));
    deletePhoto(CLUB_A, id)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(RELEASED_MESSAGE));
    getPhoto(CLUB_A, id).andExpect(status().isOk()).andExpect(content().bytes(TestImages.png()));

    reactivate(CLUB_A, id, 1).andExpect(status().isOk());

    getPhoto(CLUB_A, id).andExpect(status().isOk()).andExpect(content().bytes(TestImages.png()));
    assertThat(photoFiles(id)).hasSize(1);
  }

  // --- permanent delete --------------------------------------------------------------------------

  @Test
  void permanentlyDeletingAPlayerDeletesTheirPhoto() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);
    String other = createPlayer(CLUB_A, "Other", 8);
    upload(CLUB_A, id, TestImages.png());
    upload(CLUB_A, other, TestImages.jpeg());

    removePlayer(CLUB_A, id).andExpect(status().isNoContent());

    assertThat(photoFiles(id)).isEmpty();
    assertThat(photoFiles(other)).hasSize(1);
    assertThat(mongoTemplate.count(new Query(), "images.chunks")).isEqualTo(1);
  }

  /**
   * The interleaving the re-check after storing exists for: a permanent delete has already removed
   * the photos, and removes the player right after this upload stored its file. The upload notices,
   * removes its file again and answers 404 — no orphan file whose player is gone.
   */
  @Test
  void anUploadRacingAPermanentDeleteLeavesNoOrphan() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)), Player.class);
              return null;
            })
        .when(imageStorage)
        .store(any(), any());

    upload(CLUB_A, id, TestImages.png())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));

    assertThat(photoFiles(id)).isEmpty();
  }

  // --- version -----------------------------------------------------------------------------------

  /** The photo isn't on the player document, so a pending edit isn't made stale by it. */
  @Test
  void photoChangesDontTouchThePlayersVersion() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);
    Document before = rawPlayer(id);

    upload(CLUB_A, id, TestImages.png()).andExpect(status().isNoContent());
    upload(CLUB_A, id, TestImages.jpeg()).andExpect(status().isNoContent());
    deletePhoto(CLUB_A, id).andExpect(status().isNoContent());
    upload(CLUB_A, id, TestImages.webp()).andExpect(status().isNoContent());

    assertThat(rawPlayer(id)).isEqualTo(before);
    Map<String, Object> edit = fields("Eran Zahavi", 10);
    edit.put("medicalStatus", "FIT");
    edit.put("version", 0);
    update(CLUB_A, id, edit)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jerseyNumber").value(10))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.hasPhoto").value(true));
  }

  // --- hasPhoto ----------------------------------------------------------------------------------

  @Test
  void hasPhotoIsReportedOnEveryPlayerResponse() throws Exception {
    create(CLUB_A, fields("New", 5))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.hasPhoto").value(false));
    String with = createPlayer(CLUB_A, "With Photo", 7);
    String without = createPlayer(CLUB_A, "Without Photo", 8);
    upload(CLUB_A, with, TestImages.png());

    mockMvc
        .perform(get("/squad/players").header("Authorization", viewer(CLUB_A)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + with + "')].hasPhoto").value(true))
        .andExpect(jsonPath("$[?(@.id == '" + without + "')].hasPhoto").value(false))
        .andExpect(jsonPath("$[?(@.fullName == 'New')].hasPhoto").value(false));
    mockMvc
        .perform(get("/squad/players/" + with).header("Authorization", viewer(CLUB_A)))
        .andExpect(jsonPath("$.hasPhoto").value(true));
    mockMvc
        .perform(get("/squad/players/" + without).header("Authorization", viewer(CLUB_A)))
        .andExpect(jsonPath("$.hasPhoto").value(false));

    Map<String, Object> edit = fields("With Photo", 7);
    edit.put("medicalStatus", "INJURED");
    edit.put("version", 0);
    update(CLUB_A, with, edit).andExpect(jsonPath("$.hasPhoto").value(true));
    release(CLUB_A, with, 1).andExpect(jsonPath("$.hasPhoto").value(true));
    reactivate(CLUB_A, with, 2).andExpect(jsonPath("$.hasPhoto").value(true));
    release(CLUB_A, without, 0).andExpect(jsonPath("$.hasPhoto").value(false));
    reactivate(CLUB_A, without, 1).andExpect(jsonPath("$.hasPhoto").value(false));
  }

  /** One storage query for the whole list, however many players — never one per player. */
  @Test
  void theListMakesOneStorageQueryRegardlessOfPlayerCount() throws Exception {
    for (int number = 1; number <= 5; number++) {
      String id = createPlayer(CLUB_A, "Player " + number, number);
      if (number % 2 == 0) {
        upload(CLUB_A, id, TestImages.png());
      }
    }
    clearInvocations(imageStorage);

    mockMvc
        .perform(get("/squad/players").header("Authorization", viewer(CLUB_A)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].hasPhoto").value(contains(false, true, false, true, false)));

    verify(imageStorage, times(1)).ownerIdsWithImage(ImageKind.PLAYER_PHOTO);
    verify(imageStorage, never()).exists(any());
    verify(imageStorage, never()).find(any());
  }

  /** Club B's photos don't flag club A's players, even for the same owner id. */
  @Test
  void anotherClubsPhotoNeverFlagsAPlayer() throws Exception {
    String id = createPlayer(CLUB_A, "Eran Zahavi", 7);
    // A file for the same player id, stored as club B directly through the storage.
    ClubContext context = new ClubContext();
    context.callAs(
        CLUB_B,
        () -> {
          imageStorage.store(
              new ImageOwner(ImageKind.PLAYER_PHOTO, id),
              new ValidatedImage(TestImages.png(), ImageType.PNG));
          return null;
        });

    mockMvc
        .perform(get("/squad/players/" + id).header("Authorization", viewer(CLUB_A)))
        .andExpect(jsonPath("$.hasPhoto").value(false));
    mockMvc
        .perform(get("/squad/players").header("Authorization", viewer(CLUB_A)))
        .andExpect(jsonPath("$[0].hasPhoto").value(false));
    getPhoto(CLUB_A, id).andExpect(status().isNotFound());
  }

  // --- helpers -----------------------------------------------------------------------------------

  private String createPlayer(String clubId, String fullName, int jerseyNumber) throws Exception {
    MvcResult result =
        create(clubId, fields(fullName, jerseyNumber)).andExpect(status().isCreated()).andReturn();
    return JSON.readTree(result.getResponse().getContentAsString()).get("id").asString();
  }

  private ResultActions create(String clubId, Map<String, Object> body) throws Exception {
    return mockMvc.perform(
        post("/squad/players")
            .header("Authorization", editor(clubId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  private ResultActions update(String clubId, String id, Map<String, Object> body)
      throws Exception {
    return mockMvc.perform(
        put("/squad/players/" + id)
            .header("Authorization", editor(clubId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  private ResultActions release(String clubId, String id, long version) throws Exception {
    return mockMvc.perform(
        post("/squad/players/" + id + "/release")
            .header("Authorization", editor(clubId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\": " + version + "}"));
  }

  private ResultActions reactivate(String clubId, String id, long version) throws Exception {
    return mockMvc.perform(
        post("/squad/players/" + id + "/reactivate")
            .header("Authorization", editor(clubId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\": " + version + "}"));
  }

  private ResultActions removePlayer(String clubId, String id) throws Exception {
    return mockMvc.perform(
        delete("/squad/players/" + id)
            .header("Authorization", tokens.bearer(clubId, PermissionLevel.ADMIN)));
  }

  private ResultActions upload(String clubId, String id, byte[] content) throws Exception {
    return mockMvc.perform(
        asEditor(
            clubId,
            multipart(HttpMethod.PUT, photoUrl(id))
                .file(new MockMultipartFile("file", "photo", "image/jpeg", content))));
  }

  private ResultActions getPhoto(String clubId, String id) throws Exception {
    return mockMvc.perform(get(photoUrl(id)).header("Authorization", viewer(clubId)));
  }

  private ResultActions deletePhoto(String clubId, String id) throws Exception {
    return mockMvc.perform(delete(photoUrl(id)).header("Authorization", editor(clubId)));
  }

  private <B extends AbstractMockHttpServletRequestBuilder<B>> B asEditor(
      String clubId, B request) {
    return request.header("Authorization", editor(clubId));
  }

  private String editor(String clubId) {
    return tokens.bearer(clubId, PermissionLevel.EDIT_FULL);
  }

  private String viewer(String clubId) {
    return tokens.bearer(clubId, PermissionLevel.VIEW_ONLY);
  }

  private static String photoUrl(String id) {
    return "/squad/players/" + id + "/photo";
  }

  private static Map<String, Object> fields(String fullName, int jerseyNumber) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fullName", fullName);
    body.put("primaryPosition", "ST");
    body.put("jerseyNumber", jerseyNumber);
    body.put("dateOfBirth", "1995-05-20");
    return body;
  }

  private Document rawPlayer(String id) {
    return mongoTemplate
        .getCollection("players")
        .find(new Document("_id", new ObjectId(id)))
        .first();
  }

  /** The GridFS files for this player's photo, whatever their club. */
  private List<Document> photoFiles(String playerId) {
    return mongoTemplate.find(
        Query.query(
            Criteria.where("metadata.kind")
                .is("PLAYER_PHOTO")
                .and("metadata.ownerId")
                .is(playerId)),
        Document.class,
        "images.files");
  }
}
