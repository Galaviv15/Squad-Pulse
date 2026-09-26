package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class UserInvitationServiceTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final UserInvitationService service = new UserInvitationService(userRepository);

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
                LocalDate.of(1985, 3, 1)));

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
                        null)))
        .isInstanceOf(EmailAlreadyRegisteredException.class);
  }
}
