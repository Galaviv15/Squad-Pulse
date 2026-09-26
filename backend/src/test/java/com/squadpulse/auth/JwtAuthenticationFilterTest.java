package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squadpulse.common.ClubContext;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Proves the {@link ClubContext} lifecycle the filter is responsible for: set from the token for
 * the duration of the request, and cleared afterwards — including when the request throws.
 */
class JwtAuthenticationFilterTest {

  private final JwtService jwtService =
      new JwtService(
          new SecurityProperties(
              "test-only-jwt-secret-not-a-real-secret", "test-only-pepper-not-a-real-secret"),
          new TokenProperties(Duration.ofMinutes(15), Duration.ofDays(30)));
  private final ClubContext clubContext = new ClubContext();
  private final JwtAuthenticationFilter filter =
      new JwtAuthenticationFilter(jwtService, clubContext);

  private final AtomicReference<Optional<String>> clubIdDuringRequest = new AtomicReference<>();
  private final AtomicReference<Authentication> authenticationDuringRequest =
      new AtomicReference<>();

  @AfterEach
  void tearDown() {
    clubContext.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void aValidTokenAuthenticatesTheRequestAndScopesItToTheTokensClub() throws Exception {
    filter.doFilter(
        requestWithToken(accessToken()), new MockHttpServletResponse(), recordingChain());

    assertThat(clubIdDuringRequest.get()).contains("club-a");
    Authentication authentication = authenticationDuringRequest.get();
    assertThat(authentication.getPrincipal())
        .isEqualTo(new AuthenticatedUser("user-1", "club-a", PermissionLevel.EDIT_FULL));
    assertThat(authentication.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("EDIT_FULL");
    assertThat(clubContext.getClubId()).isEmpty();
  }

  @Test
  void clearsTheClubContextEvenWhenTheRequestThrows() {
    FilterChain failingChain =
        (request, response) -> {
          assertThat(clubContext.getClubId()).contains("club-a");
          throw new IllegalStateException("boom");
        };

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    requestWithToken(accessToken()), new MockHttpServletResponse(), failingChain))
        .isInstanceOf(IllegalStateException.class);

    assertThat(clubContext.getClubId()).isEmpty();
  }

  @Test
  void aRequestWithoutATokenContinuesUnauthenticatedWithNoClub() throws Exception {
    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), recordingChain());

    assertThat(clubIdDuringRequest.get()).isEmpty();
    assertThat(authenticationDuringRequest.get()).isNull();
  }

  @Test
  void anInvalidTokenContinuesUnauthenticatedWithNoClub() throws Exception {
    filter.doFilter(
        requestWithToken("not-a-valid-token"), new MockHttpServletResponse(), recordingChain());

    assertThat(clubIdDuringRequest.get()).isEmpty();
    assertThat(authenticationDuringRequest.get()).isNull();
  }

  @Test
  void ignoresOtherAuthorizationSchemes() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Basic " + accessToken());

    filter.doFilter(request, new MockHttpServletResponse(), recordingChain());

    assertThat(authenticationDuringRequest.get()).isNull();
  }

  @Test
  void aLeftoverClubIdOnThePooledThreadNeverReachesAnUnauthenticatedRequest() throws Exception {
    clubContext.setClubId("stale-club");

    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), recordingChain());

    assertThat(clubIdDuringRequest.get()).isEmpty();
  }

  private FilterChain recordingChain() {
    return (request, response) -> {
      clubIdDuringRequest.set(clubContext.getClubId());
      authenticationDuringRequest.set(SecurityContextHolder.getContext().getAuthentication());
    };
  }

  private String accessToken() {
    User user = new User();
    user.setId("user-1");
    user.setClubId("club-a");
    user.setPermissionLevel(PermissionLevel.EDIT_FULL);
    return jwtService.issue(user).value();
  }

  private static MockHttpServletRequest requestWithToken(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    return request;
  }
}
