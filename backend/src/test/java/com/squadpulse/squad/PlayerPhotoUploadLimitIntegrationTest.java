package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.TestImages;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The servlet container's multipart limits, which only a real server enforces (MockMvc doesn't —
 * the in-code limit is covered in {@link PlayerControllerTest}). Proves the limits in
 * application.yml are applied: Boot's 1MB default would refuse a valid 2 MB photo, and an oversize
 * upload must be a clean 413 JSON error the client can read.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
@Import(TestAccessTokens.class)
class PlayerPhotoUploadLimitIntegrationTest {

  private static final String CLUB = "club-a";
  private static final int MIB = 1024 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @LocalServerPort private int port;
  @Autowired private TestAccessTokens tokens;
  @Autowired private PlayerRepository playerRepository;
  @Autowired private ClubContext clubContext;
  @Autowired private MongoTemplate mongoTemplate;

  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private String playerId;

  @BeforeEach
  void createPlayer() {
    Player player = new Player();
    player.setFullName("Eran Zahavi");
    player.setPrimaryPosition(Position.ST);
    player.setDateOfBirth(LocalDate.of(1995, 5, 20));
    playerId = clubContext.callAs(CLUB, () -> playerRepository.insert(player)).getId();
  }

  @AfterEach
  void tearDown() {
    mongoTemplate.remove(new Query(), Player.class);
    mongoTemplate.remove(new Query(), "images.files");
    mongoTemplate.remove(new Query(), "images.chunks");
  }

  /**
   * Exactly the 2 MB limit gets through the container (Boot's default 1MB would refuse it), and
   * over real HTTP the photo comes back with its type, length and Spring Security's nosniff.
   */
  @Test
  void aPhotoOfExactlyTheLimitIsAcceptedAndServed() throws Exception {
    byte[] photo = TestImages.jpegOfSize(2 * MIB);

    HttpResponse<String> upload = upload(photo, true);
    assertThat(upload.statusCode()).isEqualTo(204);

    HttpResponse<byte[]> served =
        client.send(
            HttpRequest.newBuilder(photoUri())
                .header("Authorization", tokens.bearer(CLUB, PermissionLevel.VIEW_ONLY))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(served.statusCode()).isEqualTo(200);
    assertThat(served.body()).isEqualTo(photo);
    assertThat(served.headers().firstValue("Content-Type")).contains("image/jpeg");
    assertThat(served.headers().firstValue("Content-Length")).contains(String.valueOf(2 * MIB));
    assertThat(served.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
  }

  /** Over the container's file limit: refused while the body is parsed, as a JSON 413. */
  @ParameterizedTest(name = "{0} MiB")
  @ValueSource(ints = {3, 10})
  void anOversizeUploadIsAClean413(int mebibytes) throws Exception {
    HttpResponse<String> response = upload(TestImages.jpegOfSize(mebibytes * MIB), true);

    assertThat(response.statusCode()).isEqualTo(413);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("status").asInt()).isEqualTo(413);
    assertThat(body.get("error").asString()).isEqualTo("Content Too Large");
    assertThat(body.get("message").asString()).isEqualTo("Upload exceeds the maximum allowed size");
    assertThat(mongoTemplate.count(new Query(), "images.files")).isZero();
  }

  /** Without a token the security chain answers first — the body is never parsed. */
  @Test
  void anUnauthenticatedOversizeUploadIs401() throws Exception {
    HttpResponse<String> response = upload(TestImages.jpegOfSize(10 * MIB), false);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(JSON.readTree(response.body()).get("message").asString())
        .isEqualTo("Authentication required");
  }

  private HttpResponse<String> upload(byte[] content, boolean authenticated) throws Exception {
    String boundary = "----squadpulse-" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    body.write(content);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(photoUri())
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
    if (authenticated) {
      request.header("Authorization", tokens.bearer(CLUB, PermissionLevel.EDIT_FULL));
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI photoUri() {
    return URI.create("http://localhost:" + port + "/squad/players/" + playerId + "/photo");
  }
}
