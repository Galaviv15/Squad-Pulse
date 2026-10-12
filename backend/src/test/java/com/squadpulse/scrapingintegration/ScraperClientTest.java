package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
            client.trigger(new ScraperClient.TriggerRequest("run-1", 7617, 62, 28, true, true));

        assertThat(answer).as(url).isEqualTo(new ScraperClient.Answer.Queued("run-1"));
        assertThat(scraper.received().getLast().path()).as(url).isEqualTo("/scrape");
        assertThat(scraper.received().getLast().method()).as(url).isEqualTo("POST");
      }
      assertThat(scraper.received()).hasSize(2);
    }
  }
}
