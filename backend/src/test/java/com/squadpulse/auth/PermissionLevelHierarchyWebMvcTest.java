package com.squadpulse.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves the permission hierarchy is actually wired into method security in this Spring Security
 * version, not just defined: every caller level against an endpoint requiring each level, behind
 * the real security chain with genuine signed tokens. The expected outcomes are written out by hand
 * rather than derived from {@link PermissionLevel}'s order.
 */
@WebMvcTest(
    controllers = PermissionLevelHierarchyWebMvcTest.ProbeController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import({AuthWebMvcTestConfig.class, PermissionLevelHierarchyWebMvcTest.ProbeController.class})
class PermissionLevelHierarchyWebMvcTest {

  @RestController
  static class ProbeController {

    @GetMapping("/probe/ADMIN")
    @PreAuthorize("hasAuthority('ADMIN')")
    String admin() {
      return "ok";
    }

    @GetMapping("/probe/EDIT_FULL")
    @PreAuthorize("hasAuthority('EDIT_FULL')")
    String editFull() {
      return "ok";
    }

    @GetMapping("/probe/EDIT_PARTIAL")
    @PreAuthorize("hasAuthority('EDIT_PARTIAL')")
    String editPartial() {
      return "ok";
    }

    @GetMapping("/probe/VIEW_ONLY")
    @PreAuthorize("hasAuthority('VIEW_ONLY')")
    String viewOnly() {
      return "ok";
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;

  @ParameterizedTest(name = "{0} calling an endpoint requiring {1} -> {2}")
  @CsvSource({
    "ADMIN,        ADMIN,        200",
    "ADMIN,        EDIT_FULL,    200",
    "ADMIN,        EDIT_PARTIAL, 200",
    "ADMIN,        VIEW_ONLY,    200",
    "EDIT_FULL,    ADMIN,        403",
    "EDIT_FULL,    EDIT_FULL,    200",
    "EDIT_FULL,    EDIT_PARTIAL, 200",
    "EDIT_FULL,    VIEW_ONLY,    200",
    "EDIT_PARTIAL, ADMIN,        403",
    "EDIT_PARTIAL, EDIT_FULL,    403",
    "EDIT_PARTIAL, EDIT_PARTIAL, 200",
    "EDIT_PARTIAL, VIEW_ONLY,    200",
    "VIEW_ONLY,    ADMIN,        403",
    "VIEW_ONLY,    EDIT_FULL,    403",
    "VIEW_ONLY,    EDIT_PARTIAL, 403",
    "VIEW_ONLY,    VIEW_ONLY,    200",
  })
  void aCallerPassesExactlyTheChecksForTheirLevelAndBelow(
      PermissionLevel caller, PermissionLevel required, int expectedStatus) throws Exception {
    var result =
        mockMvc
            .perform(get("/probe/" + required.name()).header("Authorization", bearer(caller)))
            .andExpect(status().is(expectedStatus));
    if (expectedStatus == 200) {
      result.andExpect(content().string("ok"));
    } else {
      result
          .andExpect(content().contentType(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.status").value(403))
          .andExpect(jsonPath("$.error").value("Forbidden"))
          .andExpect(jsonPath("$.message").value("Access denied"));
    }
  }

  private String bearer(PermissionLevel permissionLevel) {
    User user = new User();
    user.setId("user-1");
    user.setClubId("club-a");
    user.setPermissionLevel(permissionLevel);
    return "Bearer " + jwtService.issue(user).value();
  }
}
