package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.auth.TestClubMappings;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The 256 KB cap on {@code POST /internal/scrape-results} (KAN-66) against the real embedded
 * Tomcat: a body with a {@code Content-Length} over the cap, and a chunked one (no length), both
 * get the standard JSON 413; a chunked body under the cap still goes through.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret",
      "squadpulse.scraper.service-token=" + ScrapeResultBodyLimitIntegrationTest.SERVICE_TOKEN
    })
@Testcontainers
@Import(TestClubMappings.class)
class ScrapeResultBodyLimitIntegrationTest {

  static final String SERVICE_TOKEN = "test-only-scraper-token-0123456789abcdef";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @LocalServerPort private int port;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private TestClubMappings clubs;

  // HTTP/1.1, so a body of unknown length is sent with Transfer-Encoding: chunked.
  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @AfterEach
  void tearDown() {
    mongoTemplate.getCollection("league_fixtures").deleteMany(new Document());
    mongoTemplate.getCollection("league_standings").deleteMany(new Document());
    clubs.deleteAllClubs();
  }

  @Test
  void anOversizeBodyWithALengthIsA413() throws Exception {
    HttpResponse<String> response =
        send(BodyPublishers.ofByteArray(paddedBody(ScrapeResultBodyLimit.MAX_BYTES + 1)));

    assertTooLarge(response);
  }

  @Test
  void anOversizeChunkedBodyIsA413() throws Exception {
    HttpResponse<String> response = send(chunked(paddedBody(ScrapeResultBodyLimit.MAX_BYTES + 1)));

    assertTooLarge(response);
  }

  @Test
  void aChunkedBodyAtTheLimitIsAccepted() throws Exception {
    clubs.createClub("Sderot", ScrapePayloads.SDEROT, ScrapePayloads.LEAGUE, ScrapePayloads.SEASON);

    HttpResponse<String> response = send(chunked(paddedBody(ScrapeResultBodyLimit.MAX_BYTES)));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"outcome\":\"STORED\"");
  }

  private void assertTooLarge(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    assertThat(response.body())
        .contains("\"status\":413")
        .contains("Request body exceeds the maximum allowed size of 256 KB");
    assertThat(mongoTemplate.getCollection("league_fixtures").countDocuments()).isZero();
  }

  private HttpResponse<String> send(BodyPublisher body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/scrape-results"))
            .header("Authorization", "Bearer " + SERVICE_TOKEN)
            .header("Content-Type", "application/json")
            .POST(body)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** No known length, so the client sends it chunked. */
  private static BodyPublisher chunked(byte[] body) {
    return BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));
  }

  /** A valid scrape, padded with trailing whitespace to exactly {@code size} bytes. */
  private static byte[] paddedBody(int size) {
    byte[] json = ScrapePayloads.json(ScrapePayloads.full()).getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[size];
    java.util.Arrays.fill(body, (byte) ' ');
    System.arraycopy(json, 0, body, 0, json.length);
    return body;
  }
}
