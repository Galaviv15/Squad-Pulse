package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves that missing or weak secrets stop the application from starting. */
class SecurityPropertiesTest {

  private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @EnableConfigurationProperties(SecurityProperties.class)
  static class Config {}

  @Test
  void bindsValidSecrets() {
    runner
        .withPropertyValues(
            "squadpulse.security.jwt-secret=" + VALID_SECRET,
            "squadpulse.security.password-pepper=" + VALID_SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(SecurityProperties.class).jwtSecret())
                  .isEqualTo(VALID_SECRET);
            });
  }

  @Test
  void failsWhenSecretsAreMissing() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenSecretsAreBlank() {
    runner
        .withPropertyValues(
            "squadpulse.security.jwt-secret=", "squadpulse.security.password-pepper=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenJwtSecretIsTooShort() {
    runner
        .withPropertyValues(
            "squadpulse.security.jwt-secret=too-short",
            "squadpulse.security.password-pepper=" + VALID_SECRET)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenPasswordPepperIsTooShort() {
    runner
        .withPropertyValues(
            "squadpulse.security.jwt-secret=" + VALID_SECRET,
            "squadpulse.security.password-pepper=too-short")
        .run(context -> assertThat(context).hasFailed());
  }
}
