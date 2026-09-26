package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves that missing or non-positive token lifetimes stop the application from starting. */
class TokenPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @EnableConfigurationProperties(TokenProperties.class)
  static class Config {}

  @Test
  void bindsValidLifetimes() {
    runner
        .withPropertyValues(
            "squadpulse.security.token.access-ttl=15m", "squadpulse.security.token.refresh-ttl=30d")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              TokenProperties properties = context.getBean(TokenProperties.class);
              assertThat(properties.accessTtl()).isEqualTo(Duration.ofMinutes(15));
              assertThat(properties.refreshTtl()).isEqualTo(Duration.ofDays(30));
            });
  }

  @Test
  void failsWhenLifetimesAreMissing() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenAccessLifetimeIsZero() {
    runner
        .withPropertyValues(
            "squadpulse.security.token.access-ttl=0s", "squadpulse.security.token.refresh-ttl=30d")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenRefreshLifetimeIsNegative() {
    runner
        .withPropertyValues(
            "squadpulse.security.token.access-ttl=15m", "squadpulse.security.token.refresh-ttl=-1d")
        .run(context -> assertThat(context).hasFailed());
  }
}
