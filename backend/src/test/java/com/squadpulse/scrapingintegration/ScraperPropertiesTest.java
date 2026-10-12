package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.common.CapturedLogs;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/**
 * An unset token starts the app (internal endpoints off); a weak one stops it. An unset URL starts
 * it (no refresh possible), an invalid one stops it, and so do bad durations (KAN-68).
 */
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

  @Test
  void defaultsToNoUrlAndTenMinutes() {
    runner.run(
        context -> {
          ScraperProperties properties = context.getBean(ScraperProperties.class);
          assertThat(properties.url()).isNull();
          assertThat(properties.triggerEnabled()).isFalse();
          assertThat(properties.refreshCooldown()).isEqualTo(Duration.ofMinutes(10));
          assertThat(properties.runTimeout()).isEqualTo(Duration.ofMinutes(10));
        });
    runner
        .withPropertyValues("squadpulse.scraper.url=")
        .run(context -> assertThat(context.getBean(ScraperProperties.class).url()).isNull());
  }

  @Test
  void bindsAValidUrl() {
    for (String url :
        List.of("http://127.0.0.1:8090", "http://127.0.0.1:8090/", "https://scraper.internal")) {
      runner
          .withPropertyValues(
              "squadpulse.scraper.service-token=" + VALID_TOKEN, "squadpulse.scraper.url=" + url)
          .run(
              context -> {
                assertThat(context).as(url).hasNotFailed();
                assertThat(context.getBean(ScraperProperties.class).triggerEnabled()).isTrue();
              });
    }
  }

  /**
   * Bound through Spring Boot ({@link ApplicationContextRunner}): the context fails to start with
   * the Bean Validation error of the {@code @AssertTrue isUrlValid()} constraint itself, not any
   * other failure.
   */
  @Test
  void failsForAnInvalidUrl() {
    for (String url :
        List.of(
            "ftp://127.0.0.1:8090",
            "127.0.0.1:8090",
            "http://",
            "http:///scrape",
            "http://user:secret@127.0.0.1:8090",
            "http://127.0.0.1:8090?x=1",
            "http://127.0.0.1:8090#x",
            "http://127.0.0.1:80 90",
            "not a url",
            "http://scraper:8090/base",
            "http://scraper:8090/base/",
            "http://scraper:8090/scrape",
            "http://scraper:8090//")) {
      runner
          .withPropertyValues(
              "squadpulse.scraper.service-token=" + VALID_TOKEN, "squadpulse.scraper.url=" + url)
          .run(context -> assertRefusedBy(context, url, "urlValid"));
    }
  }

  /** Bound through Spring Boot, like {@link #failsForAnInvalidUrl}. */
  @Test
  void failsForARunTimeoutLongerThanTheCooldown() {
    runner
        .withPropertyValues(
            "squadpulse.scraper.refresh-cooldown=5m", "squadpulse.scraper.run-timeout=6m")
        .run(context -> assertRefusedBy(context, "6m > 5m", "runTimeoutWithinCooldown"));
  }

  /** The other side of both constraints: a valid URL and equal durations start. */
  @Test
  void startsWithAValidUrlAndEqualDurations() {
    runner
        .withPropertyValues(
            "squadpulse.scraper.service-token=" + VALID_TOKEN,
            "squadpulse.scraper.url=http://127.0.0.1:8090",
            "squadpulse.scraper.refresh-cooldown=5m",
            "squadpulse.scraper.run-timeout=5m")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ScraperProperties properties = context.getBean(ScraperProperties.class);
              assertThat(properties.triggerEnabled()).isTrue();
              assertThat(properties.runTimeout()).isEqualTo(properties.refreshCooldown());
            });
  }

  /** Startup failed, and the cause is a binding validation error on exactly {@code field}. */
  private static void assertRefusedBy(
      AssertableApplicationContext context, String description, String field) {
    assertThat(context).as(description).hasFailed();
    Throwable cause = context.getStartupFailure();
    while (cause != null && !(cause instanceof BindValidationException)) {
      cause = cause.getCause();
    }
    assertThat(cause).as(description).isInstanceOf(BindValidationException.class);
    assertThat(((BindValidationException) cause).getValidationErrors().getAllErrors())
        .as(description)
        .singleElement()
        .satisfies(
            error ->
                assertThat(((org.springframework.validation.FieldError) error).getField())
                    .isEqualTo(field));
  }

  @Test
  void failsForANonPositiveDuration() {
    for (String setting :
        List.of(
            "squadpulse.scraper.refresh-cooldown=0s",
            "squadpulse.scraper.run-timeout=0s",
            "squadpulse.scraper.run-timeout=-1s")) {
      runner
          .withPropertyValues(setting)
          .run(context -> assertThat(context).as(setting).hasFailed());
    }
  }

  @Test
  void anUnsetUrlLogsOneWarningAtStartup() {
    CapturedLogs logs = new CapturedLogs();
    logs.start();
    try {
      new ScraperClient(
          new ScraperProperties(VALID_TOKEN, null, null, null), JsonMapper.builder().build());
      new ScraperClient(
          new ScraperProperties(VALID_TOKEN, "http://127.0.0.1:8090", null, null),
          JsonMapper.builder().build());
    } finally {
      logs.stop();
    }
    assertThat(logs.warningsOrAbove())
        .singleElement()
        .satisfies(
            event -> assertThat(event.getFormattedMessage()).contains("SCRAPER_URL is not set"));
  }

  @Test
  void toStringNeverPrintsTheTokenOrTheUrl() {
    assertThat(new ScraperProperties(VALID_TOKEN, "http://user:pw@host", null, null).toString())
        .doesNotContain(VALID_TOKEN)
        .doesNotContain("pw");
  }
}
