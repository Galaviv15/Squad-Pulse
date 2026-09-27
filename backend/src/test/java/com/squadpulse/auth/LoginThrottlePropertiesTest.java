package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves that a missing or non-positive login-throttle setting stops the application starting. */
class LoginThrottlePropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @EnableConfigurationProperties(LoginThrottleProperties.class)
  static class Config {}

  @Test
  void bindsValidLimits() {
    runner
        .withPropertyValues(
            "squadpulse.security.login-throttle.max-attempts=5",
            "squadpulse.security.login-throttle.window=15m")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              LoginThrottleProperties properties = context.getBean(LoginThrottleProperties.class);
              assertThat(properties.maxAttempts()).isEqualTo(5);
              assertThat(properties.window()).isEqualTo(Duration.ofMinutes(15));
            });
  }

  @Test
  void failsWhenLimitsAreMissing() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenMaxAttemptsIsZero() {
    runner
        .withPropertyValues(
            "squadpulse.security.login-throttle.max-attempts=0",
            "squadpulse.security.login-throttle.window=15m")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenWindowIsZero() {
    runner
        .withPropertyValues(
            "squadpulse.security.login-throttle.max-attempts=5",
            "squadpulse.security.login-throttle.window=0s")
        .run(context -> assertThat(context).hasFailed());
  }
}
