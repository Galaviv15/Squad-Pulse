package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.common.TestImages;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * The servlet container's multipart limit on {@code PUT /clubs/me/logo}, which only a real server
 * enforces: the same container-wide limit and global 413 handling as for player photos (see {@code
 * squad.PlayerPhotoUploadLimitIntegrationTest}, which covers the limit in full).
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
class ClubLogoUploadLimitIntegrationTest {

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
  @Autowired private JwtService jwtService;
  @Autowired private MongoTemplate mongoTemplate;

  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private Club club;

  @BeforeEach
  void createClub() {
    club = new Club();
    club.setName("Club A");
    club = mongoTemplate.insert(club);
  }

  @AfterEach
  void tearDown() {
    mongoTemplate.remove(new Query(), Club.class);
    mongoTemplate.remove(new Query(), "images.files");
    mongoTemplate.remove(new Query(), "images.chunks");
  }

  /** Over the container's file limit: refused while the body is parsed, as a JSON 413. */
  @Test
  void anOversizeLogoIsAClean413AndNothingIsStored() throws Exception {
    HttpResponse<String> response = upload(TestImages.jpegOfSize(3 * MIB));

    assertThat(response.statusCode()).isEqualTo(413);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("error").asString()).isEqualTo("Content Too Large");
    assertThat(body.get("message").asString()).isEqualTo("Upload exceeds the maximum allowed size");
    assertThat(mongoTemplate.count(new Query(), "images.files")).isZero();
  }

  private HttpResponse<String> upload(byte[] content) throws Exception {
    String boundary = "----squadpulse-" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"logo.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    body.write(content);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    User admin = new User();
    admin.setId("user-1");
    admin.setClubId(club.getId());
    admin.setPermissionLevel(PermissionLevel.ADMIN);
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/clubs/me/logo"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + jwtService.issue(admin).value())
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
