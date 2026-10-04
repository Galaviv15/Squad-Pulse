package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class UserInvitationServiceTest {

  private static final AuthenticatedUser CALLER =
      new AuthenticatedUser("admin-1", "club-a", PermissionLevel.ADMIN);

  private final UserRepository userRepository = mock(UserRepository.class);
  private final PasswordResetService passwordResetService = mock(PasswordResetService.class);
  private final ActiveCallerCheck activeCallerCheck = mock(ActiveCallerCheck.class);
  private final UserInvitationService service =
      new UserInvitationService(userRepository, passwordResetService, activeCallerCheck);

  @Test
  void createsAnActiveUserWithNoPasswordAndLeavesTheClubToTheScopedRepository() {
    when(userRepository.insert(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    User invited =
        service.invite(
            new InviteUserRequest(
                " Coach@Example.com ",
                "Dana Levi",
                Title.HEAD_COACH,
                PermissionLevel.EDIT_FULL,
                LocalDate.of(1985, 3, 1)),
            CALLER);

    assertThat(invited.getEmail()).isEqualTo("coach@example.com");
    assertThat(invited.getFullName()).isEqualTo("Dana Levi");
    assertThat(invited.getTitle()).isEqualTo(Title.HEAD_COACH);
    assertThat(invited.getPermissionLevel()).isEqualTo(PermissionLevel.EDIT_FULL);
    assertThat(invited.getDateOfBirth()).isEqualTo(LocalDate.of(1985, 3, 1));
    assertThat(invited.getPasswordHash()).isNull();
    assertThat(invited.isActive()).isTrue();
    // Stamped by ClubScopedRepositoryImpl from ClubContext, never by this service.
    assertThat(invited.getClubId()).isNull();
  }

  @Test
  void emailsTheInvitedUserAnActivationCode() {
    User stored = new User();
    when(userRepository.insert(any(User.class))).thenReturn(stored);

    User invited =
        service.invite(
            new InviteUserRequest(
                "coach@example.com",
                "Dana Levi",
                Title.HEAD_COACH,
                PermissionLevel.EDIT_FULL,
                null),
            CALLER);

    assertThat(invited).isSameAs(stored);
    verify(passwordResetService).sendActivationCode(stored);
  }

  @Test
  void aTakenEmailIsAConflict() {
    when(userRepository.insert(any(User.class))).thenThrow(new DuplicateKeyException("dup"));

    assertThatThrownBy(
            () ->
                service.invite(
                    new InviteUserRequest(
                        "coach@example.com",
                        "Dana Levi",
                        Title.HEAD_COACH,
                        PermissionLevel.EDIT_FULL,
                        null),
                    CALLER))
        .isInstanceOf(EmailAlreadyRegisteredException.class);

    verifyNoInteractions(passwordResetService);
  }

  /** A deactivated or deleted caller is refused before anything is created or sent (KAN-37). */
  @Test
  void aCallerWhoCanNoLongerActCreatesNobody() {
    when(activeCallerCheck.requireActive(CALLER)).thenThrow(new CurrentUserUnavailableException());

    assertThatThrownBy(
            () ->
                service.invite(
                    new InviteUserRequest(
                        "coach@example.com",
                        "Dana Levi",
                        Title.HEAD_COACH,
                        PermissionLevel.EDIT_FULL,
                        null),
                    CALLER))
        .isInstanceOf(CurrentUserUnavailableException.class);

    verifyNoInteractions(userRepository, passwordResetService);
  }
}
