package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Pins the permission hierarchy down with hand-written expectations, deliberately not derived from
 * {@link PermissionLevel}'s order: if a level is added, removed or reordered, this fails until the
 * change is made on purpose. {@code PermissionLevelHierarchyWebMvcTest} proves the same hierarchy
 * is actually applied to {@code @PreAuthorize} checks.
 */
class PermissionLevelHierarchyTest {

  private final RoleHierarchy hierarchy = SecurityConfig.permissionLevelHierarchy();

  @Test
  void theDeclarationOrderIsTheHierarchyHighestFirst() {
    assertThat(PermissionLevel.values())
        .containsExactly(
            PermissionLevel.ADMIN,
            PermissionLevel.EDIT_FULL,
            PermissionLevel.EDIT_PARTIAL,
            PermissionLevel.VIEW_ONLY);
  }

  @Test
  void eachLevelReachesItselfAndEveryLowerLevelOnly() {
    Map<PermissionLevel, List<String>> expected =
        Map.of(
            PermissionLevel.ADMIN, List.of("ADMIN", "EDIT_FULL", "EDIT_PARTIAL", "VIEW_ONLY"),
            PermissionLevel.EDIT_FULL, List.of("EDIT_FULL", "EDIT_PARTIAL", "VIEW_ONLY"),
            PermissionLevel.EDIT_PARTIAL, List.of("EDIT_PARTIAL", "VIEW_ONLY"),
            PermissionLevel.VIEW_ONLY, List.of("VIEW_ONLY"));

    assertThat(expected).containsOnlyKeys(PermissionLevel.values());
    expected.forEach(
        (level, reachable) ->
            assertThat(
                    hierarchy
                        .getReachableGrantedAuthorities(
                            List.of(new SimpleGrantedAuthority(level.name())))
                        .stream()
                        .map(GrantedAuthority::getAuthority))
                .as("authorities reachable from %s", level)
                .containsExactlyInAnyOrderElementsOf(reachable));
  }
}
