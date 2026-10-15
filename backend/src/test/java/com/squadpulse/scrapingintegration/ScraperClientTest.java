package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

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
   * (today's scraper rejects both as unknown) — a {@code false} by {@code NON_DEFAULT}, an empty
   * list by {@code NON_EMPTY}, checked with this Jackson version — and {@link
   * ScrapeTriggerService#REQUEST_GAME_DETAILS} is {@code false} until KAN-80.
   */
  @Test
  void gameDetailsAndKnownGameIdsAreInTheBodyOnlyWhenAskedFor() {
    JsonMapper json = JsonMapper.builder().build();

    assertThat(
            json.readTree(
                    json.writeValueAsString(
                        new ScraperClient.TriggerRequest(
                            "r", 1, 2, 3, true, true, true, false, List.of())))
                .propertyNames())
        .containsExactly(
            "runId", "teamId", "leagueId", "seasonId", "fixtures", "standings", "playerStats");
    assertThat(
            json.readTree(
                    json.writeValueAsString(
                        new ScraperClient.TriggerRequest(
                            "r", 1, 2, 3, true, true, true, false, null)))
                .propertyNames())
        .doesNotContain("gameDetails", "knownGameIds");

    JsonNode asked =
        json.readTree(
            json.writeValueAsString(
                new ScraperClient.TriggerRequest(
                    "r", 1, 2, 3, true, true, true, true, List.of(1122601, 1143937))));
    assertThat(asked.get("gameDetails").booleanValue()).isTrue();
    assertThat(asked.get("knownGameIds").toString()).isEqualTo("[1122601,1143937]");

    JsonNode nothingKnown =
        json.readTree(
            json.writeValueAsString(
                new ScraperClient.TriggerRequest("r", 1, 2, 3, true, true, true, true, List.of())));
    assertThat(nothingKnown.get("gameDetails").booleanValue()).isTrue();
    assertThat(nothingKnown.has("knownGameIds")).isFalse();

    assertThat(ScrapeTriggerService.REQUEST_GAME_DETAILS).isFalse();
  }
}
