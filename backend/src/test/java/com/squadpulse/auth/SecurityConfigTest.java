package com.squadpulse.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.common.ClubContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves the security rules themselves, independently of the real auth endpoints, against a
 * test-only controller: protected by default, 401 without a valid token, 403 from a failed {@code
 * hasAuthority} check, and the caller's club available to the controller via {@link ClubContext}.
 */
@WebMvcTest(
    controllers = SecurityConfigTest.ProbeController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import({AuthWebMvcTestConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {

  @RestController
  static class ProbeController {

    private final ClubContext clubContext;

    ProbeController(ClubContext clubContext) {
      this.clubContext = clubContext;
    }

    @GetMapping("/probe")
    String probe(@AuthenticationPrincipal AuthenticatedUser user) {
      return user.userId() + "@" + clubContext.requireClubId();
    }

    @GetMapping("/probe/admin")
    @PreAuthorize("hasAuthority('ADMIN')")
    String adminOnly() {
      return "ok";
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @Autowired private SecurityProperties securityProperties;

  @Test
  void aValidTokenReachesTheControllerWithTheCallersClub() throws Exception {
    mockMvc
        .perform(get("/probe").header("Authorization", bearer(PermissionLevel.VIEW_ONLY)))
        .andExpect(status().isOk())
        .andExpect(content().string("user-1@club-a"));
  }

  @Test
  void rejectsARequestWithoutATokenWithAJson401() throws Exception {
    mockMvc
        .perform(get("/probe"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.message").value("Authentication required"));
  }

  @Test
  void rejectsAnExpiredToken() throws Exception {
    JwtService issuedAnHourAgo =
        new JwtService(
            securityProperties,
            new TokenProperties(Duration.ofMinutes(15), Duration.ofDays(30)),
            Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));

    mockMvc
        .perform(
            get("/probe")
                .header(
                    "Authorization",
                    "Bearer " + issuedAnHourAgo.issue(user(PermissionLevel.ADMIN)).value()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsATamperedToken() throws Exception {
    mockMvc
        .perform(get("/probe").header("Authorization", bearer(PermissionLevel.ADMIN) + "x"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anAdminPassesAnAdminOnlyCheck() throws Exception {
    mockMvc
        .perform(get("/probe/admin").header("Authorization", bearer(PermissionLevel.ADMIN)))
        .andExpect(status().isOk());
  }

  @Test
  void aNonAdminGetsAJson403FromAnAdminOnlyCheck() throws Exception {
    mockMvc
        .perform(get("/probe/admin").header("Authorization", bearer(PermissionLevel.EDIT_FULL)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.message").value("Access denied"));
  }

  private String bearer(PermissionLevel permissionLevel) {
    return "Bearer " + jwtService.issue(user(permissionLevel)).value();
  }

  private static User user(PermissionLevel permissionLevel) {
    User user = new User();
    user.setId("user-1");
    user.setClubId("club-a");
    user.setPermissionLevel(permissionLevel);
    return user;
  }
}
