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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.OptimisticLockingFailureException;

class UserActivationServiceTest {

  private static final AuthenticatedUser CALLER =
      new AuthenticatedUser("admin-1", "club-a", PermissionLevel.ADMIN);
  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00.123Z");

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ActiveCallerCheck activeCallerCheck = mock(ActiveCallerCheck.class);
  private final Clock clock = mock(Clock.class);
  private final UserActivationService service =
      new UserActivationService(userRepository, activeCallerCheck, clock);

  /** Both operations, for the checks they share. */
  private final BiFunction<String, AuthenticatedUser, User> deactivate = service::deactivate;

  private final BiFunction<String, AuthenticatedUser, User> reactivate = service::reactivate;

  // --- the change itself -------------------------------------------------------------------------

  @Test
  void deactivationClearsActiveAndInvalidatesSessionsAtTheClocksInstant() {
    when(clock.instant()).thenReturn(NOW);
    User target = user("user-2", true);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(target));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User result = service.deactivate("user-2", CALLER);

    assertThat(result).isSameAs(target);
    assertThat(result.isActive()).isFalse();
    assertThat(result.getSessionsInvalidatedAt()).isEqualTo(NOW);
    verify(userRepository).save(target);
  }

  @Test
  void reactivationSetsActiveAndAlsoInvalidatesSessionsAtTheClocksInstant() {
    when(clock.instant()).thenReturn(NOW);
    User target = user("user-2", false);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(target));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User result = service.reactivate("user-2", CALLER);

    assertThat(result.isActive()).isTrue();
    assertThat(result.getSessionsInvalidatedAt()).isEqualTo(NOW);
    verify(userRepository).save(target);
  }

  /** Password, level, title and name are untouched; a never-activated user stays that way. */
  @Test
  void nothingButActiveAndTheInvalidationInstantChanges() {
    when(clock.instant()).thenReturn(NOW);
    User target = user("user-2", true);
    target.setPermissionLevel(PermissionLevel.ADMIN);
    target.setPasswordHash(null);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(target));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User deactivated = service.deactivate("user-2", CALLER);
    User reactivated = service.reactivate("user-2", CALLER);

    for (User result : new User[] {deactivated, reactivated}) {
      assertThat(result.getPermissionLevel()).isEqualTo(PermissionLevel.ADMIN);
      assertThat(result.getTitle()).isEqualTo(Title.ANALYST);
      assertThat(result.getFullName()).isEqualTo("Noa Cohen");
      assertThat(result.getPasswordHash()).isNull();
    }
  }

  /** A later stored instant (e.g. a reset on a clock ahead of this one) is kept, never undone. */
  @Test
  void aLaterStoredInvalidationInstantIsNeverMovedBackwards() {
    when(clock.instant()).thenReturn(NOW);
    Instant later = NOW.plusSeconds(5);
    User toDeactivate = user("user-2", true);
    toDeactivate.setSessionsInvalidatedAt(later);
    User toReactivate = user("user-3", false);
    toReactivate.setSessionsInvalidatedAt(later);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(toDeactivate));
    when(userRepository.findById("user-3")).thenReturn(Optional.of(toReactivate));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(service.deactivate("user-2", CALLER).getSessionsInvalidatedAt()).isEqualTo(later);
    assertThat(service.reactivate("user-3", CALLER).getSessionsInvalidatedAt()).isEqualTo(later);
  }

  @Test
  void anEarlierStoredInvalidationInstantIsReplaced() {
    when(clock.instant()).thenReturn(NOW);
    User target = user("user-2", true);
    target.setSessionsInvalidatedAt(NOW.minusSeconds(3600));
    when(userRepository.findById("user-2")).thenReturn(Optional.of(target));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(service.deactivate("user-2", CALLER).getSessionsInvalidatedAt()).isEqualTo(NOW);
  }

  // --- no-op -------------------------------------------------------------------------------------

  @Test
  void deactivatingADeactivatedUserIsANoOpWithoutASaveOrAClockReading() {
    Instant earlier = NOW.minusSeconds(60);
    User target = user("user-2", false);
    target.setSessionsInvalidatedAt(earlier);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(target));

    User result = service.deactivate("user-2", CALLER);

    assertThat(result).isSameAs(target);
    assertThat(result.getSessionsInvalidatedAt()).isEqualTo(earlier);
    verify(userRepository, never()).save(any());
    verifyNoInteractions(clock);
  }

  @Test
  void reactivatingAnActiveUserIsANoOpWithoutASaveOrAClockReading() {
    User target = user("user-2", true);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(target));

    User result = service.reactivate("user-2", CALLER);

    assertThat(result).isSameAs(target);
    assertThat(result.getSessionsInvalidatedAt()).isNull();
    verify(userRepository, never()).save(any());
    verifyNoInteractions(clock);
  }

  // --- order of checks: 401 → 409 → 404 ----------------------------------------------------------

  @Test
  void aCallerWhoCanNoLongerActIs401WhateverTheTarget() {
    when(activeCallerCheck.requireActive(CALLER)).thenThrow(new CurrentUserUnavailableException());

    for (BiFunction<String, AuthenticatedUser, User> operation : operations()) {
      for (String target : new String[] {"user-2", "admin-1", "no-such-user"}) {
        assertThatThrownBy(() -> operation.apply(target, CALLER))
            .isInstanceOf(CurrentUserUnavailableException.class);
      }
    }
    verifyNoInteractions(userRepository, clock);
  }

  @Test
  void aCallerCantDeactivateOrReactivateThemselves() {
    for (BiFunction<String, AuthenticatedUser, User> operation : operations()) {
      assertThatThrownBy(() -> operation.apply("admin-1", CALLER))
          .isInstanceOf(CannotChangeOwnActiveStatusException.class)
          .hasMessage("You can't deactivate or reactivate yourself; another ADMIN must do it");
    }
    verify(activeCallerCheck, times(2)).requireActive(CALLER);
    verifyNoInteractions(userRepository, clock);
  }

  /** The club-scoped findById returns empty for another club's id too — same outcome. */
  @Test
  void aUserNotFoundInTheCallersClubIsNotFound() {
    when(userRepository.findById("elsewhere")).thenReturn(Optional.empty());

    for (BiFunction<String, AuthenticatedUser, User> operation : operations()) {
      assertThatThrownBy(() -> operation.apply("elsewhere", CALLER))
          .isInstanceOf(NotFoundException.class)
          .hasMessage("User not found");
    }
    verify(userRepository, never()).save(any());
    verify(userRepository, times(2)).findById("elsewhere"); // once per call, not retried
  }

  // --- concurrent writes (KAN-24) ----------------------------------------------------------------

  /**
   * The retry reapplies to the reloaded user (keeping the concurrent writer's change) and reads the
   * clock again, rather than reusing the first attempt's instant. The caller is checked only once.
   */
  @Test
  void aVersionConflictReloadsTheUserAndAppliesAFreshInstant() {
    Instant retryInstant = NOW.plusMillis(7);
    when(clock.instant()).thenReturn(NOW, retryInstant);
    User stale = user("user-2", true);
    User fresh = user("user-2", true);
    fresh.setPermissionLevel(PermissionLevel.EDIT_FULL);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(stale), Optional.of(fresh));
    when(userRepository.save(any(User.class)))
        .thenThrow(new OptimisticLockingFailureException("conflict"))
        .thenAnswer(invocation -> invocation.getArgument(0));

    User result = service.deactivate("user-2", CALLER);

    assertThat(result).isSameAs(fresh);
    assertThat(result.isActive()).isFalse();
    assertThat(result.getPermissionLevel()).isEqualTo(PermissionLevel.EDIT_FULL);
    assertThat(result.getSessionsInvalidatedAt()).isEqualTo(retryInstant);
    InOrder order = inOrder(activeCallerCheck, userRepository);
    order.verify(activeCallerCheck).requireActive(CALLER);
    order.verify(userRepository).save(stale);
    order.verify(userRepository).save(fresh);
    verify(activeCallerCheck, times(1)).requireActive(any());
  }

  /** Someone else deactivated the user meanwhile: the reloaded user needs no save at all. */
  @Test
  void aConflictWhereTheReloadedUserIsAlreadyInTheStateIsANoOp() {
    when(clock.instant()).thenReturn(NOW);
    User fresh = user("user-2", false);
    when(userRepository.findById("user-2"))
        .thenReturn(Optional.of(user("user-2", true)), Optional.of(fresh));
    when(userRepository.save(any(User.class)))
        .thenThrow(new OptimisticLockingFailureException("conflict"));

    assertThat(service.deactivate("user-2", CALLER)).isSameAs(fresh);
    verify(userRepository, times(1)).save(any());
  }

  @Test
  void aUserDeletedBeforeTheRetryIsNotFound() {
    when(clock.instant()).thenReturn(NOW);
    when(userRepository.findById("user-2"))
        .thenReturn(Optional.of(user("user-2", false)), Optional.empty());
    when(userRepository.save(any(User.class)))
        .thenThrow(new OptimisticLockingFailureException("conflict"));

    assertThatThrownBy(() -> service.reactivate("user-2", CALLER))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("User not found");
    verify(userRepository, times(2)).findById("user-2");
  }

  @Test
  void givesUpAfterMaxAttemptsAndRethrowsTheConflict() {
    when(clock.instant()).thenReturn(NOW);
    OptimisticLockingFailureException last = new OptimisticLockingFailureException("last");
    when(userRepository.findById("user-2"))
        .thenAnswer(invocation -> Optional.of(user("user-2", true)));
    when(userRepository.save(any(User.class)))
        .thenThrow(
            new OptimisticLockingFailureException("first"),
            new OptimisticLockingFailureException("second"),
            last);

    assertThatThrownBy(() -> service.deactivate("user-2", CALLER)).isSameAs(last);
    verify(userRepository, times(UserWriteRetry.MAX_ATTEMPTS)).findById("user-2");
    verify(userRepository, times(UserWriteRetry.MAX_ATTEMPTS)).save(any());
  }

  /** The production constructor reads the real UTC clock. */
  @Test
  void theDefaultClockIsUtc() {
    User target = user("user-2", true);
    when(userRepository.findById("user-2")).thenReturn(Optional.of(target));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    Instant before = Clock.system(ZoneOffset.UTC).instant();

    User result =
        new UserActivationService(userRepository, activeCallerCheck).deactivate("user-2", CALLER);

    assertThat(result.getSessionsInvalidatedAt()).isBetween(before, Instant.now());
  }

  private List<BiFunction<String, AuthenticatedUser, User>> operations() {
    return List.of(deactivate, reactivate);
  }

  private static User user(String id, boolean active) {
    User user = new User();
    user.setId(id);
    user.setTitle(Title.ANALYST);
    user.setFullName("Noa Cohen");
    user.setPermissionLevel(PermissionLevel.VIEW_ONLY);
    user.setPasswordHash("hash");
    user.setActive(active);
    return user;
  }
}
