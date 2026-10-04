package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.squadpulse.common.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.OptimisticLockingFailureException;

class UserPermissionLevelServiceTest {

  private static final AuthenticatedUser CALLER =
      new AuthenticatedUser("admin-1", "club-a", PermissionLevel.ADMIN);

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ActiveCallerCheck activeCallerCheck = mock(ActiveCallerCheck.class);
  private final UserPermissionLevelService service =
      new UserPermissionLevelService(userRepository, activeCallerCheck);

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

  // --- caller re-check (KAN-37) ------------------------------------------------------------------

  /** 401 before the self-change 409 and the target's 404: nothing else is even looked at. */
  @Test
  void aCallerWhoCanNoLongerActIsRejectedBeforeAnythingElse() {
    when(activeCallerCheck.requireActive(CALLER)).thenThrow(new CurrentUserUnavailableException());

    for (String target : new String[] {"user-2", "admin-1", "no-such-user"}) {
      assertThatThrownBy(
              () -> service.changePermissionLevel(target, PermissionLevel.VIEW_ONLY, CALLER))
          .isInstanceOf(CurrentUserUnavailableException.class);
    }
    verifyNoInteractions(userRepository);
  }

  @Test
  void theCallerIsCheckedOnceBeforeTheRetryLoop() {
    when(userRepository.findById("user-2"))
        .thenAnswer(invocation -> Optional.of(user("user-2", PermissionLevel.VIEW_ONLY)));
    when(userRepository.save(any(User.class)))
        .thenThrow(new OptimisticLockingFailureException("conflict"))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.changePermissionLevel("user-2", PermissionLevel.EDIT_FULL, CALLER);

    InOrder order = inOrder(activeCallerCheck, userRepository);
    order.verify(activeCallerCheck).requireActive(CALLER);
    order.verify(userRepository, times(2)).findById("user-2");
    verify(activeCallerCheck, times(1)).requireActive(any());
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
    verify(userRepository, times(1)).findById("elsewhere"); // not retried
  }

  // --- concurrent writes (KAN-24) ----------------------------------------------------------------

  @Test
  void aVersionConflictReloadsTheUserAndSavesAgain() {
    User stale = user("user-2", PermissionLevel.VIEW_ONLY);
    User fresh = user("user-2", PermissionLevel.VIEW_ONLY);
    fresh.setPasswordHash("hash-from-a-concurrent-reset");
    when(userRepository.findById("user-2")).thenReturn(Optional.of(stale), Optional.of(fresh));
    when(userRepository.save(any(User.class)))
        .thenThrow(new OptimisticLockingFailureException("conflict"))
        .thenAnswer(invocation -> invocation.getArgument(0));

    User updated = service.changePermissionLevel("user-2", PermissionLevel.EDIT_FULL, CALLER);

    assertThat(updated).isSameAs(fresh);
    assertThat(updated.getPermissionLevel()).isEqualTo(PermissionLevel.EDIT_FULL);
    assertThat(updated.getPasswordHash()).isEqualTo("hash-from-a-concurrent-reset");
    InOrder order = inOrder(userRepository);
    order.verify(userRepository).save(stale);
    order.verify(userRepository).save(fresh);
  }

  /** Someone else set the same level meanwhile: the reloaded user needs no save at all. */
  @Test
  void aConflictWhereTheReloadedUserAlreadyHasTheLevelIsANoOp() {
    User fresh = user("user-2", PermissionLevel.EDIT_FULL);
    when(userRepository.findById("user-2"))
        .thenReturn(Optional.of(user("user-2", PermissionLevel.VIEW_ONLY)), Optional.of(fresh));
    when(userRepository.save(any(User.class)))
        .thenThrow(new OptimisticLockingFailureException("conflict"));

    User result = service.changePermissionLevel("user-2", PermissionLevel.EDIT_FULL, CALLER);

    assertThat(result).isSameAs(fresh);
    verify(userRepository, times(1)).save(any());
  }

  @Test
  void aUserDeletedBeforeTheRetryIsNotFound() {
    when(userRepository.findById("user-2"))
        .thenReturn(Optional.of(user("user-2", PermissionLevel.VIEW_ONLY)), Optional.empty());
    when(userRepository.save(any(User.class)))
        .thenThrow(new OptimisticLockingFailureException("conflict"));

    assertThatThrownBy(
            () -> service.changePermissionLevel("user-2", PermissionLevel.EDIT_FULL, CALLER))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("User not found");
    verify(userRepository, times(1)).save(any());
    verify(userRepository, times(2)).findById("user-2"); // the 404 ends the loop
  }

  @Test
  void givesUpAfterMaxAttemptsAndRethrowsTheConflict() {
    OptimisticLockingFailureException last = new OptimisticLockingFailureException("last");
    when(userRepository.findById("user-2"))
        .thenAnswer(invocation -> Optional.of(user("user-2", PermissionLevel.VIEW_ONLY)));
    when(userRepository.save(any(User.class)))
        .thenThrow(
            new OptimisticLockingFailureException("first"),
            new OptimisticLockingFailureException("second"),
            last);

    assertThatThrownBy(
            () -> service.changePermissionLevel("user-2", PermissionLevel.EDIT_FULL, CALLER))
        .isSameAs(last);
    verify(userRepository, times(UserWriteRetry.MAX_ATTEMPTS)).findById("user-2");
    verify(userRepository, times(UserWriteRetry.MAX_ATTEMPTS)).save(any());
  }

  private static User user(String id, PermissionLevel permissionLevel) {
    User user = new User();
    user.setId(id);
    user.setTitle(Title.ANALYST);
    user.setPermissionLevel(permissionLevel);
    return user;
  }
}
