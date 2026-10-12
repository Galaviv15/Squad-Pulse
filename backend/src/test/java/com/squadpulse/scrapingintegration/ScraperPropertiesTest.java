package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** An unset token starts the app (internal endpoints off); a weak one stops it. */
class ScraperPropertiesTest {

  private static final String VALID_TOKEN = "0123456789abcdef0123456789abcdef";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @EnableConfigurationProperties(ScraperProperties.class)
  static class Config {}

  @Test
  void startsWithoutAToken() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ScraperProperties.class).enabled()).isFalse();
        });
    runner
        .withPropertyValues("squadpulse.scraper.service-token=")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ScraperProperties.class).enabled()).isFalse();
            });
  }

  @Test
  void bindsAStrongToken() {
    runner
        .withPropertyValues("squadpulse.scraper.service-token=" + VALID_TOKEN)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ScraperProperties.class).enabled()).isTrue();
              assertThat(context.getBean(ScraperProperties.class).toString())
                  .doesNotContain(VALID_TOKEN);
            });
  }

  @Test
  void failsForATokenShorterThan32Characters() {
    runner
        .withPropertyValues("squadpulse.scraper.service-token=" + VALID_TOKEN.substring(1))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsForATokenWithWhitespace() {
    runner
        .withPropertyValues("squadpulse.scraper.service-token=" + VALID_TOKEN + " x")
        .run(context -> assertThat(context).hasFailed());
  }
}
