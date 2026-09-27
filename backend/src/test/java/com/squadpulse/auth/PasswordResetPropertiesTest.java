package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves that a missing or non-positive password-reset setting stops the application starting. */
class PasswordResetPropertiesTest {

  private static final String PREFIX = "squadpulse.security.password-reset.";
  private static final List<String> VALID =
      List.of(
          PREFIX + "code-ttl=15m",
          PREFIX + "max-attempts=5",
          PREFIX + "max-requests=5",
          PREFIX + "request-window=24h");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @EnableConfigurationProperties(PasswordResetProperties.class)
  static class Config {}

  @Test
  void bindsValidLimits() {
    runner
        .withPropertyValues(VALID.toArray(String[]::new))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              PasswordResetProperties properties = context.getBean(PasswordResetProperties.class);
              assertThat(properties.codeTtl()).isEqualTo(Duration.ofMinutes(15));
              assertThat(properties.maxAttempts()).isEqualTo(5);
              assertThat(properties.maxRequests()).isEqualTo(5);
              assertThat(properties.requestWindow()).isEqualTo(Duration.ofHours(24));
            });
  }

  @Test
  void failsWhenEverythingIsMissing() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"code-ttl", "max-attempts", "max-requests", "request-window"})
  void failsWhenOneSettingIsMissing(String name) {
    runner
        .withPropertyValues(
            VALID.stream().filter(p -> !p.startsWith(PREFIX + name)).toArray(String[]::new))
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"code-ttl=0s", "max-attempts=0", "max-requests=0", "request-window=0s"})
  void failsWhenOneSettingIsNotPositive(String override) {
    String name = override.substring(0, override.indexOf('='));
    List<String> values = new ArrayList<>();
    VALID.stream().filter(p -> !p.startsWith(PREFIX + name)).forEach(values::add);
    values.add(PREFIX + override);

    runner
        .withPropertyValues(values.toArray(String[]::new))
        .run(context -> assertThat(context).hasFailed());
  }
}
