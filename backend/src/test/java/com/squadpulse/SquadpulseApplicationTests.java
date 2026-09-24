package com.squadpulse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: fails fast if the Spring context can't even start.
 *
 * <p>Runs with zero infrastructure (no Docker needed): the Mongo driver only connects lazily on
 * first use, so pointing it at a dummy URI is enough to let the context — including the club-scoped
 * repository beans from the {@code common} module (KAN-15) — wire up without a real database. Real
 * data-layer behavior is covered by Testcontainers-backed integration tests (see docs/spec.md
 * section 11), e.g. {@code ClubScopedRepositoryImplIntegrationTest}.
 */
@SpringBootTest(
    properties = {
      "spring.mongodb.uri=mongodb://localhost:27017/squadpulse-smoke-test",
      // Dummy values so the startup validation in SecurityProperties passes — not real secrets.
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
class SquadpulseApplicationTests {

  @Test
  void contextLoads() {
    // Intentionally empty: a failing context load already fails this test.
  }
}
