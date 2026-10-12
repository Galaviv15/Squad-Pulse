package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves {@link InternalEndpointRules} catches each kind of violation and allows the compliant
 * shapes. The fixtures are nested in this test class, which Spring Boot's test scanning skips, so
 * they never become real endpoints.
 */
class InternalEndpointAuthorizationRuleTest {

  @Test
  void allowsInternalHandlersRequiringTheScraper() {
    assertThat(InternalEndpointRules.violations(List.of(Compliant.class, ClassLevel.class)))
        .isEmpty();
  }

  @Test
  void failsForAnInternalHandlerThatAUserMayCall() {
    assertThat(InternalEndpointRules.violations(List.of(InternalForUsers.class)))
        .singleElement()
        .asString()
        .contains("InternalForUsers.ingest")
        .contains("doesn't require exactly hasAuthority('SCRAPER_SERVICE')");
  }

  @Test
  void failsForAnInternalHandlerThatAlsoAdmitsAUser() {
    assertThat(InternalEndpointRules.violations(List.of(InternalForEither.class))).hasSize(1);
  }

  @Test
  void failsForTheScraperAuthorityOutsideInternal() {
    assertThat(InternalEndpointRules.violations(List.of(ScraperOutsideInternal.class)))
        .singleElement()
        .asString()
        .contains("ScraperOutsideInternal.read")
        .contains("mentions SCRAPER_SERVICE");
  }

  @RestController
  static class Compliant {
    @PostMapping("/internal/ok")
    @PreAuthorize("hasAuthority('SCRAPER_SERVICE')")
    void ingest() {}
  }

  @RestController
  @RequestMapping("/internal")
  @PreAuthorize("hasAuthority('SCRAPER_SERVICE')")
  static class ClassLevel {
    @PostMapping("/class-level")
    void ingest() {}
  }

  @RestController
  static class InternalForUsers {
    @PostMapping("/internal/users")
    @PreAuthorize("hasAuthority('VIEW_ONLY')")
    void ingest() {}
  }

  @RestController
  static class InternalForEither {
    @PostMapping("/internal/either")
    @PreAuthorize("hasAnyAuthority('SCRAPER_SERVICE', 'ADMIN')")
    void ingest() {}
  }

  @RestController
  static class ScraperOutsideInternal {
    @GetMapping("/matches/scraper")
    @PreAuthorize("hasAuthority('SCRAPER_SERVICE')")
    void read() {}
  }
}
