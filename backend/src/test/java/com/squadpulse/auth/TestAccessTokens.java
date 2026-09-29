package com.squadpulse.auth;

/**
 * Genuine signed access tokens for tests <b>outside</b> the {@code auth} package, which can't reach
 * the package-private {@link JwtService} directly (e.g. the squad controller tests).
 * {@code @Import} it into the test: in a {@code @WebMvcTest} slice together with {@link
 * AuthWebMvcTestConfig}, in a {@code @SpringBootTest} on its own.
 */
public class TestAccessTokens {

  private final JwtService jwtService;

  TestAccessTokens(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  /** An {@code Authorization} header value for user {@code user-1} of {@code clubId}. */
  public String bearer(String clubId, PermissionLevel permissionLevel) {
    User user = new User();
    user.setId("user-1");
    user.setClubId(clubId);
    user.setPermissionLevel(permissionLevel);
    return "Bearer " + jwtService.issue(user).value();
  }
}
