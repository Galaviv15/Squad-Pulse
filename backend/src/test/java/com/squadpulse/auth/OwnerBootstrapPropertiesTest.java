package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Proves that a missing or weak owner secret stops the bootstrap task from starting — the same
 * fail-fast rule as {@link SecurityPropertiesTest}. That a normal server never binds it at all is
 * covered by {@code SquadpulseApplicationTests}.
 */
class OwnerBootstrapPropertiesTest {

  private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(
              context -> context.getEnvironment().setActiveProfiles(ClubBootstrapRunner.PROFILE))
          .withUserConfiguration(Config.class);

  @EnableConfigurationProperties(OwnerBootstrapProperties.class)
  static class Config {}

  @Test
  void bindsAValidOwnerSecret() {
    runner
        .withPropertyValues("squadpulse.bootstrap.owner-secret=" + VALID_SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(OwnerBootstrapProperties.class).ownerSecret())
                  .isEqualTo(VALID_SECRET);
            });
  }

  @Test
  void failsWhenOwnerSecretIsMissing() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenOwnerSecretIsBlank() {
    runner
        .withPropertyValues("squadpulse.bootstrap.owner-secret=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenOwnerSecretIsTooShort() {
    runner
        .withPropertyValues("squadpulse.bootstrap.owner-secret=too-short")
        .run(context -> assertThat(context).hasFailed());
  }
}
