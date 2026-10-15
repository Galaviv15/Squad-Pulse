package com.squadpulse.scrapingintegration;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend's one call to the scraper (KAN-68): {@code POST <SCRAPER_URL>/scrape}, per
 * docs/scraper-backend-contract.md, "Trigger: backend → scraper".
 *
 * <p>Fails fast and closed: a {@link RestClient} built here — not from any auto-configured builder
 * or {@code spring.http.client.*} setting — over the JDK {@link HttpClient}, with a {@value
 * #CONNECT_TIMEOUT_SECONDS} s connect timeout, a {@value #READ_TIMEOUT_SECONDS} s read timeout, no
 * redirects followed and no retry. Whatever goes wrong is {@link Answer.Unavailable}; it runs on
 * the calling request's thread only. At most {@value #MAX_RESPONSE_BYTES} bytes of the answer are
 * read. Neither body nor the token is ever logged.
 */
@Component
class ScraperClient {

  static final long CONNECT_TIMEOUT_SECONDS = 2;
  static final long READ_TIMEOUT_SECONDS = 5;
  static final int MAX_RESPONSE_BYTES = 1024;

  private static final Logger log = LoggerFactory.getLogger(ScraperClient.class);

  /**
   * The trigger body: the six properties the scraper requires, always (it rejects a missing one),
   * and {@code playerStats} (KAN-72) only when {@code true}. Since KAN-73 the scraper accepts that
   * property as optional (absent = {@code false}), so leaving a {@code false} out of the JSON is
   * harmless; {@link ScrapeTriggerService#REQUEST_PLAYER_STATS} is {@code true}.
   *
   * <p>{@code gameDetails} and {@code knownGameIds} (KAN-79) likewise only when {@code gameDetails}
   * is {@code true} (and the list only when not empty): the scraper accepts {@code knownGameIds}
   * only with {@code gameDetails: true}. Since KAN-80 the scraper accepts both and {@link
   * ScrapeTriggerService#REQUEST_GAME_DETAILS} is {@code true}, so every trigger asks for game
   * details unless the club has too many known games.
   *
   * @param knownGameIds the games the scraper must not fetch (stored or given up); empty unless
   *     {@code gameDetails}
   */
  record TriggerRequest(
      String runId,
      int teamId,
      int leagueId,
      int seasonId,
      boolean fixtures,
      boolean standings,
      @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean playerStats,
      @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean gameDetails,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Integer> knownGameIds) {

    TriggerRequest {
      knownGameIds = knownGameIds == null ? List.of() : List.copyOf(knownGameIds);
    }

    /** Without game details (as before KAN-80; tests). */
    TriggerRequest(
        String runId,
        int teamId,
        int leagueId,
        int seasonId,
        boolean fixtures,
        boolean standings,
        boolean playerStats) {
      this(runId, teamId, leagueId, seasonId, fixtures, standings, playerStats, false, List.of());
    }
  }

  /** What the scraper answered. */
  sealed interface Answer {
    /** {@code 202 QUEUED}, with the runId the scraper echoed. */
    record Queued(String runId) implements Answer {}

    /** {@code 202 ALREADY_QUEUED}: the existing run's runId, possibly {@code null}. */
    record AlreadyQueued(String runId) implements Answer {}

    /**
     * Not reached, or anything but a well-formed {@code 202}.
     *
     * @param status the HTTP status, or {@code 0} if there was no answer
     */
    record Unavailable(int status, String why) implements Answer {}
  }

  private final ScraperProperties properties;
  private final JsonMapper jsonMapper;
  private final URI scrapeUri;
  private final RestClient restClient;

  ScraperClient(ScraperProperties properties, JsonMapper jsonMapper) {
    this.properties = properties;
    this.jsonMapper = jsonMapper;
    if (properties.url() == null) {
      log.warn(
          "SCRAPER_URL is not set: the scraper can't be triggered, every POST /matches/refresh"
              + " answers SCRAPER_UNAVAILABLE");
      this.scrapeUri = null;
    } else {
      // The URL has no path (ScraperProperties#isUrlValid), at most a trailing "/": drop it, so the
      // trigger always goes to "<scheme>://<host>[:port]/scrape", never "//scrape".
      String base = properties.url();
      this.scrapeUri =
          URI.create(
              (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/scrape");
    }
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS));
    this.restClient = RestClient.builder().requestFactory(requestFactory).build();
  }

  /** Whether a trigger can be sent at all: a URL and a token are configured. */
  boolean configured() {
    return scrapeUri != null && properties.triggerEnabled();
  }

  /** Sends one trigger. Never throws for anything the scraper or the network does. */
  Answer trigger(TriggerRequest request) {
    if (!configured()) {
      return new Answer.Unavailable(0, "not configured");
    }
    byte[] body = jsonMapper.writeValueAsBytes(request);
    try {
      return restClient
          .post()
          .uri(scrapeUri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .exchange(
              (clientRequest, response) ->
                  read(response.getStatusCode().value(), response.getBody()));
    } catch (RuntimeException e) {
      // Connection refused, a timeout, a reset: the class name only, never a message (it may
      // carry the URL) or the request.
      return new Answer.Unavailable(0, e.getClass().getSimpleName());
    }
  }

  private Answer read(int status, InputStream bodyStream) throws IOException {
    if (status != 202) {
      return new Answer.Unavailable(status, "status " + status);
    }
    byte[] raw = bodyStream.readNBytes(MAX_RESPONSE_BYTES + 1);
    if (raw.length > MAX_RESPONSE_BYTES) {
      return new Answer.Unavailable(status, "oversize 202");
    }
    JsonNode node;
    try {
      node = jsonMapper.readTree(raw);
    } catch (RuntimeException e) {
      return new Answer.Unavailable(status, "malformed 202");
    }
    if (node == null || !node.isObject()) {
      return new Answer.Unavailable(status, "malformed 202");
    }
    JsonNode statusNode = node.get("status");
    JsonNode runIdNode = node.get("runId");
    if (statusNode == null
        || !statusNode.isString()
        || runIdNode == null
        || !(runIdNode.isNull() || runIdNode.isString())) {
      return new Answer.Unavailable(status, "malformed 202");
    }
    String runId = runIdNode.isNull() ? null : runIdNode.stringValue();
    return switch (statusNode.stringValue()) {
      case "QUEUED" -> new Answer.Queued(runId);
      case "ALREADY_QUEUED" -> new Answer.AlreadyQueued(runId);
      default -> new Answer.Unavailable(status, "malformed 202");
    };
  }
}
