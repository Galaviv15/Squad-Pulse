package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.squadpulse.common.ClubContext;
import com.squadpulse.common.EmailSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordResetServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final String EMAIL = "coach@example.com";

  private final UserRepository userRepository = mock(UserRepository.class);
  private final PasswordResetCodeService codeService = mock(PasswordResetCodeService.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final EmailSender emailSender = mock(EmailSender.class);
  private final ClubContext clubContext = new ClubContext();

  private final PasswordResetService service =
      new PasswordResetService(
          userRepository,
          codeService,
          new PasswordResetProperties(Duration.ofMinutes(15), 5, 5, Duration.ofHours(24)),
          passwordEncoder,
          emailSender,
          clubContext,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @AfterEach
  void tearDown() {
    clubContext.clear();
  }

  @Test
  void requestResetEmailsACodeToAnActiveUser() {
    when(codeService.recordRequest(EMAIL)).thenReturn(true);
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));
    when(codeService.issue(EMAIL)).thenReturn("042137");

    service.requestReset(" Coach@Example.com ");

    verify(emailSender).send(eq(EMAIL), eq(PasswordResetService.RESET_SUBJECT), contains("042137"));
  }

  /** Counted before anything else, so unknown emails are counted exactly like registered ones. */
  @Test
  void requestResetCountsTheRequestBeforeLookingTheUserUp() {
    when(codeService.recordRequest(EMAIL)).thenReturn(true);
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    service.requestReset(EMAIL);

    InOrder order = inOrder(codeService, userRepository);
    order.verify(codeService).recordRequest(EMAIL);
    order.verify(userRepository).findByEmail(EMAIL);
  }

  @Test
  void requestResetForAnUnknownEmailIssuesAndSendsNothing() {
    when(codeService.recordRequest(EMAIL)).thenReturn(true);
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    service.requestReset(EMAIL);

    verify(codeService, never()).issue(anyString());
    verifyNoInteractions(emailSender);
  }

  @Test
  void requestResetForADeactivatedUserIssuesAndSendsNothing() {
    when(codeService.recordRequest(EMAIL)).thenReturn(true);
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(false)));

    service.requestReset(EMAIL);

    verify(codeService, never()).issue(anyString());
    verifyNoInteractions(emailSender);
  }

  @Test
  void aThrottledRequestIsSilentlyDropped() {
    when(codeService.recordRequest(EMAIL)).thenReturn(false);

    service.requestReset(EMAIL);

    verifyNoInteractions(userRepository, emailSender);
    verify(codeService, never()).issue(anyString());
  }

  @Test
  void resetPasswordSetsTheHashAndInvalidatesSessionsInTheUsersOwnClub() {
    User user = user(true);
    user.setPasswordHash(null); // e.g. an invited user activating their account
    AtomicReference<Optional<String>> clubDuringSave = new AtomicReference<>();
    when(codeService.verify(EMAIL, "123456")).thenReturn(true);
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
    when(userRepository.save(any(User.class)))
        .thenAnswer(
            invocation -> {
              clubDuringSave.set(clubContext.getClubId());
              return invocation.getArgument(0);
            });

    service.resetPassword(" Coach@Example.com ", "123456", "new-password");

    verify(userRepository).save(user);
    assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    assertThat(user.getSessionsInvalidatedAt()).isEqualTo(NOW);
    assertThat(clubDuringSave.get()).contains("club-a");
    assertThat(clubContext.getClubId()).isEmpty();
  }

  /** A stray access token's club on the request must neither be used nor lost. */
  @Test
  void resetPasswordSavesInTheUsersClubAndRestoresTheRequestsOwn() {
    clubContext.setClubId("club-from-access-token");
    AtomicReference<Optional<String>> clubDuringSave = new AtomicReference<>();
    when(codeService.verify(EMAIL, "123456")).thenReturn(true);
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));
    when(userRepository.save(any(User.class)))
        .thenAnswer(
            invocation -> {
              clubDuringSave.set(clubContext.getClubId());
              return invocation.getArgument(0);
            });

    service.resetPassword(EMAIL, "123456", "new-password");

    assertThat(clubDuringSave.get()).contains("club-a");
    assertThat(clubContext.getClubId()).contains("club-from-access-token");
  }

  @Test
  void resetPasswordWithAWrongCodeChangesNothing() {
    when(codeService.verify(EMAIL, "123456")).thenReturn(false);

    assertResetRejected();

    verifyNoInteractions(userRepository);
  }

  @Test
  void resetPasswordForAUserWhoNoLongerExistsLooksLikeAWrongCode() {
    when(codeService.verify(EMAIL, "123456")).thenReturn(true);
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    assertResetRejected();
  }

  @Test
  void resetPasswordForADeactivatedUserLooksLikeAWrongCode() {
    when(codeService.verify(EMAIL, "123456")).thenReturn(true);
    when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(false)));

    assertResetRejected();
  }

  private void assertResetRejected() {
    assertThatThrownBy(() -> service.resetPassword(EMAIL, "123456", "new-password"))
        .isInstanceOf(InvalidResetCodeException.class)
        .hasMessage("Invalid or expired code");
    verify(userRepository, never()).save(any());
    verify(passwordEncoder, never()).encode(any());
  }

  private static User user(boolean active) {
    User user = new User();
    user.setId("user-1");
    user.setClubId("club-a");
    user.setEmail(EMAIL);
    user.setPasswordHash("old-hash");
    user.setPermissionLevel(PermissionLevel.EDIT_FULL);
    user.setTitle(Title.HEAD_COACH);
    user.setFullName("Dana Levi");
    user.setActive(active);
    return user;
  }
}
