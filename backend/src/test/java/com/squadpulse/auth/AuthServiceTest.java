package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.squadpulse.auth.AuthService.IssuedTokens;
import com.squadpulse.auth.JwtService.AccessToken;
import com.squadpulse.auth.RefreshTokenService.RefreshSession;
import com.squadpulse.auth.RefreshTokenService.Rotation;
import com.squadpulse.common.ClubContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

  private static final String DUMMY_HASH = "dummy-hash";
  private static final String REAL_HASH = "real-hash";
  private static final String IP = "203.0.113.7";
  private static final Instant LOGIN_TIME = Instant.parse("2026-09-01T10:00:00Z");
  private static final AccessToken ACCESS_TOKEN =
      new AccessToken("access-token", Instant.parse("2026-09-01T10:15:00Z"));

  private final UserRepository userRepository = mock(UserRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final JwtService jwtService = mock(JwtService.class);
  private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
  private final LoginThrottleService loginThrottleService = mock(LoginThrottleService.class);
  private final ClubContext clubContext = new ClubContext();

  private AuthService authService;

  @BeforeEach
  void setUp() {
    when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
    when(jwtService.issue(any())).thenReturn(ACCESS_TOKEN);
    authService =
        new AuthService(
            userRepository,
            passwordEncoder,
            jwtService,
            refreshTokenService,
            loginThrottleService,
            clubContext);
  }

  @AfterEach
  void tearDown() {
    clubContext.clear();
  }

  @Test
  void loginIssuesAnAccessTokenAndStartsARefreshFamily() {
    User user = user();
    when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret", REAL_HASH)).thenReturn(true);
    when(refreshTokenService.issue("user-1", "club-a")).thenReturn("refresh-token");

    IssuedTokens tokens = authService.login(" Coach@Example.com ", "secret", IP);

    assertThat(tokens).isEqualTo(new IssuedTokens(ACCESS_TOKEN, "refresh-token"));
    verify(jwtService).issue(user);
  }

  /** So earlier typos don't leave the user's next session with a partly used-up allowance. */
  @Test
  void aSuccessfulLoginIsCountedBeforeThePasswordCheckThenClearsItsCount() {
    when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(user()));
    when(passwordEncoder.matches("secret", REAL_HASH)).thenReturn(true);

    authService.login(" Coach@Example.com ", "secret", IP);

    InOrder order = inOrder(loginThrottleService, passwordEncoder);
    order.verify(loginThrottleService).recordAttempt("coach@example.com", IP);
    order.verify(passwordEncoder).matches("secret", REAL_HASH);
    order.verify(loginThrottleService).reset("coach@example.com", IP);
  }

  /** Refused before the lookup and the Argon2 check — that's what actually sheds the load. */
  @Test
  void aThrottledLoginIsRefusedWithoutCheckingThePassword() {
    doThrow(new LoginThrottledException(Duration.ofMinutes(10)))
        .when(loginThrottleService)
        .recordAttempt("coach@example.com", IP);

    assertThatThrownBy(() -> authService.login("Coach@Example.com", "secret", IP))
        .isInstanceOf(LoginThrottledException.class);

    verifyNoInteractions(userRepository, jwtService, refreshTokenService);
    verify(passwordEncoder, never()).matches(anyString(), anyString());
    verify(loginThrottleService, never()).reset(anyString(), anyString());
  }

  @Test
  void loginRejectsAWrongPassword() {
    when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(user()));

    assertLoginRejected("coach@example.com", "wrong");
  }

  /** Same Argon2 cost as a wrong password, so timing doesn't reveal that the email is unknown. */
  @Test
  void loginRejectsAnUnknownEmailAfterStillCheckingAPassword() {
    when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

    assertLoginRejected("nobody@example.com", "secret");

    verify(passwordEncoder).matches("secret", DUMMY_HASH);
  }

  @Test
  void loginRejectsAnInvitedUserWhoHasNoPasswordYet() {
    User invited = user();
    invited.setPasswordHash(null);
    when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(invited));
    when(passwordEncoder.matches(anyString(), eq(DUMMY_HASH))).thenReturn(true);

    assertLoginRejected("coach@example.com", "anything");
  }

  @Test
  void loginRejectsADeactivatedUserEvenWithTheRightPassword() {
    User deactivated = user();
    deactivated.setActive(false);
    when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(deactivated));
    when(passwordEncoder.matches("secret", REAL_HASH)).thenReturn(true);

    assertLoginRejected("coach@example.com", "secret");
  }

  @Test
  void refreshRotatesAndIssuesAnAccessTokenFromTheCurrentUserRecord() {
    User user = user();
    user.setPermissionLevel(PermissionLevel.VIEW_ONLY); // e.g. downgraded since login
    AtomicReference<Optional<String>> clubDuringLookup = new AtomicReference<>();
    when(refreshTokenService.rotate("old"))
        .thenReturn(new Rotation("new", new RefreshSession("user-1", "club-a", LOGIN_TIME)));
    when(userRepository.findById("user-1"))
        .thenAnswer(
            invocation -> {
              clubDuringLookup.set(clubContext.getClubId());
              return Optional.of(user);
            });

    IssuedTokens tokens = authService.refresh("old");

    assertThat(tokens).isEqualTo(new IssuedTokens(ACCESS_TOKEN, "new"));
    verify(jwtService).issue(user);
    assertThat(clubDuringLookup.get()).contains("club-a");
    assertThat(clubContext.getClubId()).isEmpty();
  }

  @Test
  void refreshRestoresAnyClubContextTheRequestAlreadyHad() {
    clubContext.setClubId("club-from-access-token");
    when(refreshTokenService.rotate("old"))
        .thenReturn(new Rotation("new", new RefreshSession("user-1", "club-a", LOGIN_TIME)));
    when(userRepository.findById("user-1")).thenReturn(Optional.of(user()));

    authService.refresh("old");

    assertThat(clubContext.getClubId()).contains("club-from-access-token");
  }

  @Test
  void refreshForADeactivatedUserRevokesTheSession() {
    User deactivated = user();
    deactivated.setActive(false);
    when(refreshTokenService.rotate("old"))
        .thenReturn(new Rotation("new", new RefreshSession("user-1", "club-a", LOGIN_TIME)));
    when(userRepository.findById("user-1")).thenReturn(Optional.of(deactivated));

    assertThatThrownBy(() -> authService.refresh("old"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(refreshTokenService).revoke("new");
    verify(jwtService, never()).issue(any());
  }

  /** A password reset after this family was started ends it (see User#sessionsInvalidatedAt). */
  @Test
  void refreshForAFamilyStartedBeforeTheUsersSessionsWereInvalidatedRevokesIt() {
    User user = user();
    user.setSessionsInvalidatedAt(LOGIN_TIME.plusMillis(1));
    whenRefreshFindsUser("old", LOGIN_TIME, user);

    assertThatThrownBy(() -> authService.refresh("old"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(refreshTokenService).revoke("new");
    verify(jwtService, never()).issue(any());
  }

  /** A login after the reset — or in the same millisecond, the precision both are stored with. */
  @Test
  void refreshForAFamilyStartedAtOrAfterTheInvalidationStillWorks() {
    User user = user();
    user.setSessionsInvalidatedAt(LOGIN_TIME.plusNanos(999_999));
    whenRefreshFindsUser("same-millisecond", LOGIN_TIME, user);
    whenRefreshFindsUser("later", LOGIN_TIME.plusSeconds(1), user);

    assertThat(authService.refresh("same-millisecond").refreshToken()).isEqualTo("new");
    assertThat(authService.refresh("later").refreshToken()).isEqualTo("new");
    verify(refreshTokenService, never()).revoke(anyString());
  }

  /** RefreshTokenService reports a family from before issuedAt was recorded as the epoch. */
  @Test
  void refreshForALegacyFamilyIsRevokedOnceTheUsersSessionsWereInvalidated() {
    User user = user();
    user.setSessionsInvalidatedAt(LOGIN_TIME);
    whenRefreshFindsUser("legacy", Instant.EPOCH, user);

    assertThatThrownBy(() -> authService.refresh("legacy"))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(refreshTokenService).revoke("new");
  }

  @Test
  void refreshForALegacyFamilyWorksWhileTheUsersSessionsWereNeverInvalidated() {
    whenRefreshFindsUser("legacy", Instant.EPOCH, user());

    assertThat(authService.refresh("legacy").refreshToken()).isEqualTo("new");
  }

  @Test
  void refreshForADeletedUserRevokesTheSession() {
    when(refreshTokenService.rotate("old"))
        .thenReturn(new Rotation("new", new RefreshSession("user-1", "club-a", LOGIN_TIME)));
    when(userRepository.findById("user-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authService.refresh("old"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(refreshTokenService).revoke("new");
  }

  @Test
  void refreshPropagatesAnInvalidRefreshToken() {
    when(refreshTokenService.rotate("reused")).thenThrow(new InvalidRefreshTokenException());

    assertThatThrownBy(() -> authService.refresh("reused"))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(jwtService, never()).issue(any());
  }

  @Test
  void logoutRevokesTheSession() {
    authService.logout("token");

    verify(refreshTokenService).revoke("token");
  }

  private void whenRefreshFindsUser(String token, Instant familyIssuedAt, User user) {
    when(refreshTokenService.rotate(token))
        .thenReturn(new Rotation("new", new RefreshSession("user-1", "club-a", familyIssuedAt)));
    when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
  }

  /** Also checks that the attempt counted against the (normalized email, IP) pair and stays. */
  private void assertLoginRejected(String email, String password) {
    assertThatThrownBy(() -> authService.login(email, password, IP))
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessage("Invalid email or password");
    verify(jwtService, never()).issue(any());
    verify(refreshTokenService, never()).issue(anyString(), anyString());
    verify(loginThrottleService).recordAttempt(User.normalizeEmail(email), IP);
    verify(loginThrottleService, never()).reset(anyString(), anyString());
  }

  private static User user() {
    User user = new User();
    user.setId("user-1");
    user.setClubId("club-a");
    user.setEmail("coach@example.com");
    user.setPasswordHash(REAL_HASH);
    user.setPermissionLevel(PermissionLevel.ADMIN);
    user.setTitle(Title.CLUB_MANAGER);
    user.setFullName("Dana Levi");
    return user;
  }
}
