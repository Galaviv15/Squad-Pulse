package com.squadpulse;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.auth.OwnerBootstrapProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Smoke test: fails fast if the Spring context can't even start.
 *
 * <p>Runs with zero infrastructure (no Docker needed): the Mongo driver only connects lazily on
 * first use, so pointing it at a dummy URI (with index creation off — see below) is enough to let
 * the context — including the club-scoped repository beans from the {@code common} module (KAN-15)
 * — wire up without a real database. Real data-layer behavior is covered by Testcontainers-backed
 * integration tests (see docs/spec.md section 11), e.g. {@code
 * ClubScopedRepositoryImplIntegrationTest}.
 */
@SpringBootTest(
    properties = {
      "spring.mongodb.uri=mongodb://localhost:27017/squadpulse-smoke-test",
      // Index creation (enabled in application.yml for the unique User.email index, KAN-17) runs
      // eagerly while MongoTemplate is built and needs a live server, so it's switched off here to
      // keep this test infrastructure-free. The index itself is exercised against a real MongoDB
      // in UserRepositoryIntegrationTest.
      "spring.data.mongodb.auto-index-creation=false",
      // Likewise the startup User version backfill (KAN-24) writes to MongoDB, so it's off here
      // too.
      "squadpulse.user-version-backfill.enabled=false",
      // Dummy values so the startup validation in SecurityProperties passes — not real secrets.
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
class SquadpulseApplicationTests {

  @Test
  void contextLoads() {
    // Intentionally empty: a failing context load already fails this test.
  }

  /**
   * The club bootstrap task (KAN-16) and its owner secret only exist under the {@code bootstrap}
   * profile — a normal server must never bind or hold that secret.
   */
  @Test
  void bootstrapOnlyBeansAreAbsentFromANormalServer(@Autowired ApplicationContext context) {
    assertThat(context.getBeanNamesForType(OwnerBootstrapProperties.class)).isEmpty();
    assertThat(context.containsBean("clubBootstrapService")).isFalse();
    assertThat(context.containsBean("clubBootstrapRunner")).isFalse();
    assertThat(context.getEnvironment().getProperty("squadpulse.bootstrap.owner-secret")).isNull();
  }

  /**
   * Endpoints a test registers for itself (under {@code /test-only/}, e.g. {@code
   * ForwardedHeadersIntegrationTest.PeerProbe}) must never leak into other contexts: component
   * scanning reaches test classes too, so each one is a {@code @TestComponent} brought in by an
   * explicit {@code @Import}.
   */
  @Test
  void noTestOnlyEndpointIsRegistered(
      @Autowired RequestMappingHandlerMapping requestMappingHandlerMapping) {
    assertThat(
            requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPatternValues)
                .flatMap(java.util.Set::stream))
        .noneMatch(path -> path.startsWith("/test-only/"));
  }
}
