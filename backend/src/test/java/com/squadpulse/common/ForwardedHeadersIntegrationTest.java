package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.squad.Player;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * X-Forwarded-* headers are honored under the {@code dev} profile only (application-dev.yml,
 * KAN-56). The strategy is "native", Tomcat's RemoteIpValve, which runs in the container before any
 * Spring code — MockMvc never goes through it — so this needs a real server: the requests here
 * reach Tomcat over a real loopback connection, exactly as the Vite dev proxy's do.
 *
 * <p>The forwarded headers are the ones the Vite proxy sends ({@code xfwd: true}). Without the
 * profile, the same headers are ignored, so a client can't choose the scheme / host of URLs the
 * backend builds, nor the address the login throttle keys on ({@code getRemoteAddr()}, read here
 * through {@link PeerProbe}).
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
@Import({TestAccessTokens.class, ForwardedHeadersIntegrationTest.PeerProbe.class})
class ForwardedHeadersIntegrationTest {

  private static final String CLUB = "club-a";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What the Vite dev proxy adds for a browser on https://localhost:5173 (measured, KAN-56). */
  private static final Map<String, String> VITE_FORWARDED_HEADERS =
      Map.of(
          "X-Forwarded-For", "::1",
          "X-Forwarded-Host", "localhost:5173",
          "X-Forwarded-Port", "5173",
          "X-Forwarded-Proto", "https");

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  /**
   * Echoes what the app sees of the request's origin; test-only. {@code @TestComponent} keeps
   * component scanning from registering it in every other test context (Boot's
   * TestTypeExcludeFilter doesn't recognize this enclosing class as a test: its {@code @Test}
   * methods are all in {@code @Nested} classes); the {@code @Import} above registers it here.
   */
  @TestComponent
  @RestController
  static class PeerProbe {
    @GetMapping("/test-only/peer")
    @PreAuthorize("hasAuthority('VIEW_ONLY')")
    Map<String, Object> peer(HttpServletRequest request) {
      return Map.of(
          "remoteAddr", request.getRemoteAddr(),
          "scheme", request.getScheme(),
          "secure", request.isSecure());
    }
  }

  @LocalServerPort private int port;
  @Autowired private TestAccessTokens tokens;
  @Autowired private MongoTemplate mongoTemplate;

  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @AfterEach
  void tearDown() {
    mongoTemplate.remove(new Query(), Player.class);
  }

  @Nested
  @ActiveProfiles("dev")
  class UnderTheDevProfile {

    @Test
    void aCreatedPlayersLocationPointsAtTheDevServer() throws Exception {
      String location = createPlayer(VITE_FORWARDED_HEADERS);

      assertThat(location).startsWith("https://localhost:5173/squad/players/");
    }

    @Test
    void theThrottleKeyThroughTheProxyIsStillTheLoopbackAddress() throws Exception {
      JsonNode peer = peer(VITE_FORWARDED_HEADERS);

      assertThat(peer.get("remoteAddr").asString()).isIn("::1", "0:0:0:0:0:0:0:1");
      assertThat(peer.get("scheme").asString()).isEqualTo("https");
      assertThat(peer.get("secure").asBoolean()).isTrue();
    }

    /**
     * A request made secure by the forwarded headers gets no HSTS header: browsers would keep it
     * for localhost (every port) for a year and force https on other local projects.
     */
    @Test
    void aSecureRequestGetsNoHstsHeader() throws Exception {
      HttpResponse<String> response = sendPeer(VITE_FORWARDED_HEADERS);

      assertThat(JSON.readTree(response.body()).get("secure").asBoolean()).isTrue();
      assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }
  }

  /**
   * Forwarded headers honored as under the dev profile, but without it: a secure request gets
   * Spring Security's default HSTS header, unchanged — only the dev profile switches it off.
   */
  @Nested
  @TestPropertySource(properties = "server.forward-headers-strategy=native")
  class SecureRequestsWithoutTheDevProfile {

    @Test
    void getTheDefaultHstsHeader() throws Exception {
      HttpResponse<String> response = sendPeer(VITE_FORWARDED_HEADERS);

      assertThat(JSON.readTree(response.body()).get("secure").asBoolean()).isTrue();
      assertThat(response.headers().allValues("Strict-Transport-Security"))
          .containsExactly("max-age=31536000 ; includeSubDomains");
    }
  }

  @Nested
  class WithoutTheDevProfile {

    @Test
    void forwardedHeadersDoNotChangeTheLocation() throws Exception {
      String location = createPlayer(VITE_FORWARDED_HEADERS);

      assertThat(location).startsWith("http://localhost:" + port + "/squad/players/");
    }

    @Test
    void aClientCannotChooseItsThrottleKeyOrScheme() throws Exception {
      JsonNode peer = peer(Map.of("X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https"));

      assertThat(peer.get("remoteAddr").asString()).isIn("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
      assertThat(peer.get("scheme").asString()).isEqualTo("http");
      assertThat(peer.get("secure").asBoolean()).isFalse();
    }
  }

  private String createPlayer(Map<String, String> headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/squad/players"))
            .header("Authorization", tokens.bearer(CLUB, PermissionLevel.EDIT_FULL))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"fullName": "Eran Zahavi", "primaryPosition": "ST",
                     "dateOfBirth": "1995-05-20"}
                    """));
    headers.forEach(request::header);
    HttpResponse<String> response =
        client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return response.headers().firstValue("Location").orElseThrow();
  }

  private JsonNode peer(Map<String, String> headers) throws Exception {
    return JSON.readTree(sendPeer(headers).body());
  }

  private HttpResponse<String> sendPeer(Map<String, String> headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test-only/peer"))
            .header("Authorization", tokens.bearer(CLUB, PermissionLevel.VIEW_ONLY))
            .GET();
    headers.forEach(request::header);
    HttpResponse<String> response =
        client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response;
  }
}
