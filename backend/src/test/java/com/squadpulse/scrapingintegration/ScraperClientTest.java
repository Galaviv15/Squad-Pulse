package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How {@link ScraperClient} joins {@code SCRAPER_URL} and {@code /scrape} (KAN-68), against a real
 * local server: with or without a trailing slash, the trigger goes to {@code /scrape}, never {@code
 * //scrape}. A URL with a path never gets here: it's refused at startup ({@code
 * ScraperPropertiesTest.failsForAnInvalidUrl}).
 */
class ScraperClientTest {

  private static final String TOKEN = "test-only-scraper-token-0123456789abcdef";

  @Test
  void theTriggerGoesToScrapeWithOrWithoutATrailingSlash() {
    try (FakeScraper scraper = new FakeScraper()) {
      for (String url : new String[] {scraper.url(), scraper.url() + "/"}) {
        ScraperProperties properties = new ScraperProperties(TOKEN, url, null, null);
        assertThat(properties.isUrlValid()).as(url).isTrue();
        ScraperClient client = new ScraperClient(properties, JsonMapper.builder().build());

        ScraperClient.Answer answer =
            client.trigger(
                new ScraperClient.TriggerRequest("run-1", 7617, 62, 28, true, true, false));

        assertThat(answer).as(url).isEqualTo(new ScraperClient.Answer.Queued("run-1"));
        assertThat(scraper.received().getLast().path()).as(url).isEqualTo("/scrape");
        assertThat(scraper.received().getLast().method()).as(url).isEqualTo("POST");
      }
      assertThat(scraper.received()).hasSize(2);
    }
  }

  /**
   * KAN-72: {@code playerStats} is sent only when true (the scraper treats it as optional, absent =
   * false); KAN-73: {@link ScrapeTriggerService#REQUEST_PLAYER_STATS} is true, so every trigger
   * asks for the stats.
   */
  @Test
  void playerStatsIsInTheBodyOnlyWhenTrue() {
    JsonMapper json = JsonMapper.builder().build();

    assertThat(
            json.readTree(
                    json.writeValueAsString(
                        new ScraperClient.TriggerRequest("r", 1, 2, 3, true, false, false)))
                .propertyNames())
        .containsExactly("runId", "teamId", "leagueId", "seasonId", "fixtures", "standings");
    assertThat(
            json.readTree(
                    json.writeValueAsString(
                        new ScraperClient.TriggerRequest("r", 1, 2, 3, true, false, true)))
                .get("playerStats")
                .booleanValue())
        .isTrue();
    assertThat(ScrapeTriggerService.REQUEST_PLAYER_STATS).isTrue();
  }

  /**
   * KAN-79: {@code gameDetails} and {@code knownGameIds} are left out of the body unless asked for
   * (the scraper accepts {@code knownGameIds} only with {@code gameDetails: true}) — a {@code
   * false} by {@code NON_DEFAULT}, an empty list by {@code NON_EMPTY}, checked with this Jackson
   * version — and KAN-80: {@link ScrapeTriggerService#REQUEST_GAME_DETAILS} is {@code true}.
   */
  @Test
  void gameDetailsAndKnownGameIdsAreInTheBodyOnlyWhenAskedFor() {
    JsonMapper json = JsonMapper.builder().build();

    assertThat(
            json.readTree(
                    json.writeValueAsString(
                        new ScraperClient.TriggerRequest(
                            "r", 1, 2, 3, true, true, true, false, List.of(), false)))
                .propertyNames())
        .containsExactly(
            "runId", "teamId", "leagueId", "seasonId", "fixtures", "standings", "playerStats");
    assertThat(
            json.readTree(
                    json.writeValueAsString(
                        new ScraperClient.TriggerRequest(
                            "r", 1, 2, 3, true, true, true, false, null, false)))
                .propertyNames())
        .doesNotContain("gameDetails", "knownGameIds");

    JsonNode asked =
        json.readTree(
            json.writeValueAsString(
                new ScraperClient.TriggerRequest(
                    "r", 1, 2, 3, true, true, true, true, List.of(1122601, 1143937), false)));
    assertThat(asked.get("gameDetails").booleanValue()).isTrue();
    assertThat(asked.get("knownGameIds").toString()).isEqualTo("[1122601,1143937]");

    JsonNode nothingKnown =
        json.readTree(
            json.writeValueAsString(
                new ScraperClient.TriggerRequest(
                    "r", 1, 2, 3, true, true, true, true, List.of(), false)));
    assertThat(nothingKnown.get("gameDetails").booleanValue()).isTrue();
    assertThat(nothingKnown.has("knownGameIds")).isFalse();

    assertThat(ScrapeTriggerService.REQUEST_GAME_DETAILS).isTrue();
  }

  /**
   * KAN-84: {@code cupGames} is defined but not sent yet — a {@code false} is left out of the body
   * ({@code NON_DEFAULT}, verified here with this Jackson version) and {@link
   * ScrapeTriggerService#REQUEST_CUP_GAMES} is {@code false} — and a {@code true} is sent as such.
   */
  @Test
  void cupGamesIsInTheBodyOnlyWhenTrue() {
    JsonMapper json = JsonMapper.builder().build();

    assertThat(
            json.readTree(
                    json.writeValueAsString(
                        new ScraperClient.TriggerRequest(
                            "r", 1, 2, 3, true, true, true, true, List.of(5), false)))
                .propertyNames())
        .containsExactly(
            "runId",
            "teamId",
            "leagueId",
            "seasonId",
            "fixtures",
            "standings",
            "playerStats",
            "gameDetails",
            "knownGameIds");

    JsonNode asked =
        json.readTree(
            json.writeValueAsString(
                new ScraperClient.TriggerRequest(
                    "r", 1, 2, 3, true, true, true, true, List.of(), true)));
    assertThat(asked.get("cupGames").isBoolean()).isTrue();
    assertThat(asked.get("cupGames").booleanValue()).isTrue();

    assertThat(ScrapeTriggerService.REQUEST_CUP_GAMES).isFalse();
  }

  /**
   * KAN-80: the body sent for the inputs of the contract's trigger example (the block after {@code
   * <!-- contract-example-trigger -->}, which the scraper's own trigger validation also accepts)
   * has exactly that example's properties and values.
   */
  @Test
  void theBodyEqualsTheContractsTriggerExample() throws Exception {
    JsonMapper json = JsonMapper.builder().build();
    JsonNode example =
        json.readTree(
            ContractExampleIntegrationTest.fencedBlockAfter(
                Files.readString(ContractExampleIntegrationTest.CONTRACT, StandardCharsets.UTF_8),
                "<!-- contract-example-trigger -->"));
    List<Integer> knownGameIds = new ArrayList<>();
    example.get("knownGameIds").forEach(id -> knownGameIds.add(id.intValue()));

    try (FakeScraper scraper = new FakeScraper()) {
      ScraperClient client =
          new ScraperClient(new ScraperProperties(TOKEN, scraper.url(), null, null), json);
      client.trigger(
          new ScraperClient.TriggerRequest(
              example.get("runId").stringValue(),
              example.get("teamId").intValue(),
              example.get("leagueId").intValue(),
              example.get("seasonId").intValue(),
              example.get("fixtures").booleanValue(),
              example.get("standings").booleanValue(),
              ScrapeTriggerService.REQUEST_PLAYER_STATS,
              ScrapeTriggerService.REQUEST_GAME_DETAILS,
              knownGameIds,
              ScrapeTriggerService.REQUEST_CUP_GAMES));

      JsonNode body = scraper.received().getLast().body();
      assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(example.propertyNames());
      assertThat(body).isEqualTo(example);
    }
  }
}
