package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The scraper's service authority (KAN-66) stands outside the user permission hierarchy: no user
 * level reaches it, and it reaches no user level.
 */
class ScraperAuthorityHierarchyTest {

  private static final String SCRAPER_SERVICE = "SCRAPER_SERVICE";

  private final RoleHierarchy hierarchy = SecurityConfig.permissionLevelHierarchy();

  @Test
  void noPermissionLevelImpliesTheScraperAuthority() {
    for (PermissionLevel level : PermissionLevel.values()) {
      assertThat(reachable(level.name())).as(level.name()).doesNotContain(SCRAPER_SERVICE);
    }
  }

  @Test
  void theScraperAuthorityImpliesNothingElse() {
    assertThat(reachable(SCRAPER_SERVICE)).containsExactly(SCRAPER_SERVICE);
  }

  private List<String> reachable(String authority) {
    return hierarchy
        .getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority(authority)))
        .stream()
        .map(GrantedAuthority::getAuthority)
        .toList();
  }
}
