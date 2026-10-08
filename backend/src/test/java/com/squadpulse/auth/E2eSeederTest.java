package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The E2E seeder's guard: it may only ever wipe a dedicated E2E target (KAN-53). */
class E2eSeederTest {

  @Test
  void acceptsAnE2eDatabaseAndANonZeroRedisDatabase() {
    assertThatCode(() -> E2eSeeder.checkTarget("squadpulse_e2e", 1)).doesNotThrowAnyException();
  }

  @Test
  void refusesTheDevelopmentDatabase() {
    assertThatThrownBy(() -> E2eSeeder.checkTarget("squadpulse", 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("_e2e");
  }

  @Test
  void refusesADatabaseThatOnlyContainsTheSuffix() {
    assertThatThrownBy(() -> E2eSeeder.checkTarget("squadpulse_e2e_copy", 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesAMissingDatabaseName() {
    assertThatThrownBy(() -> E2eSeeder.checkTarget(null, 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesRedisDatabaseZero() {
    assertThatThrownBy(() -> E2eSeeder.checkTarget("squadpulse_e2e", 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Redis");
  }
}
