package com.squadpulse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: fails fast if the Spring context can't even start.
 *
 * <p>Mongo auto-configuration is excluded here so this test runs with zero infrastructure (no
 * Docker needed) — it's only checking that the application wires up. Real data-layer tests use
 * Testcontainers once there's an actual repository to test (see docs/spec.md section 11).
 */
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,"
          + "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
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
