package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.squadpulse.common.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserPermissionLevelServiceTest {

  private static final AuthenticatedUser CALLER =
      new AuthenticatedUser("admin-1", "club-a", PermissionLevel.ADMIN);

  private final UserRepository userRepository = mock(UserRepository.class);
  private final UserPermissionLevelService service = new UserPermissionLevelService(userRepository);

  @Test
  void setsTheNewLevelAndSavesThroughTheScopedRepositoryLeavingTheTitleAlone() {
    User target = user("user-2", PermissionLevel.VIEW_ONLY);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(target));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User updated = service.changePermissionLevel("user-2", PermissionLevel.EDIT_PARTIAL, CALLER);

    assertThat(updated.getPermissionLevel()).isEqualTo(PermissionLevel.EDIT_PARTIAL);
    assertThat(updated.getTitle()).isEqualTo(Title.ANALYST);
    verify(userRepository).save(target);
  }

  @Test
  void canGrantAndRemoveAdmin() {
    when(userRepository.findById("user-2"))
        .thenReturn(Optional.of(user("user-2", PermissionLevel.EDIT_FULL)));
    when(userRepository.findById("user-3"))
        .thenReturn(Optional.of(user("user-3", PermissionLevel.ADMIN)));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(
            service
                .changePermissionLevel("user-2", PermissionLevel.ADMIN, CALLER)
                .getPermissionLevel())
        .isEqualTo(PermissionLevel.ADMIN);
    assertThat(
            service
                .changePermissionLevel("user-3", PermissionLevel.VIEW_ONLY, CALLER)
                .getPermissionLevel())
        .isEqualTo(PermissionLevel.VIEW_ONLY);
  }

  @Test
  void settingTheCurrentLevelIsANoOp() {
    User target = user("user-2", PermissionLevel.EDIT_FULL);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(target));

    User result = service.changePermissionLevel("user-2", PermissionLevel.EDIT_FULL, CALLER);

    assertThat(result).isSameAs(target);
    verify(userRepository, never()).save(any());
  }

  @Test
  void aCallerCantChangeTheirOwnLevel() {
    assertThatThrownBy(
            () -> service.changePermissionLevel("admin-1", PermissionLevel.VIEW_ONLY, CALLER))
        .isInstanceOf(CannotChangeOwnPermissionLevelException.class);
    verifyNoInteractions(userRepository);
  }

  /** The club-scoped findById returns empty for another club's id too — same outcome. */
  @Test
  void aUserNotFoundInTheCallersClubIsNotFound() {
    when(userRepository.findById("elsewhere")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.changePermissionLevel("elsewhere", PermissionLevel.ADMIN, CALLER))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("User not found");
    verify(userRepository, never()).save(any());
  }

  private static User user(String id, PermissionLevel permissionLevel) {
    User user = new User();
    user.setId(id);
    user.setTitle(Title.ANALYST);
    user.setPermissionLevel(permissionLevel);
    return user;
  }
}
