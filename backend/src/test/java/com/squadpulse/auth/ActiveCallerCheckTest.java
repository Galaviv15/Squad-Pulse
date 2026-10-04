package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActiveCallerCheckTest {

  private static final AuthenticatedUser CALLER =
      new AuthenticatedUser("admin-1", "club-a", PermissionLevel.ADMIN);

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ActiveCallerCheck check = new ActiveCallerCheck(userRepository);

  @Test
  void anActiveCallerIsReturnedAfterOneClubScopedRead() {
    User caller = caller(true);
    when(userRepository.findById("admin-1")).thenReturn(Optional.of(caller));

    assertThat(check.requireActive(CALLER)).isSameAs(caller);
    verify(userRepository).findById("admin-1");
    verifyNoMoreInteractions(userRepository);
  }

  @Test
  void aDeactivatedCallerIsTheGeneric401() {
    when(userRepository.findById("admin-1")).thenReturn(Optional.of(caller(false)));

    assertThatThrownBy(() -> check.requireActive(CALLER))
        .isInstanceOf(CurrentUserUnavailableException.class)
        .hasMessage(SecurityConfig.AUTHENTICATION_REQUIRED_MESSAGE);
  }

  /** Deleted, or the token's sub names a user of another club: the scoped read finds nobody. */
  @Test
  void aCallerNotFoundInTheirTokensClubIsTheSameGeneric401() {
    when(userRepository.findById("admin-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> check.requireActive(CALLER))
        .isInstanceOf(CurrentUserUnavailableException.class)
        .hasMessage(SecurityConfig.AUTHENTICATION_REQUIRED_MESSAGE);
  }

  private static User caller(boolean active) {
    User user = new User();
    user.setId("admin-1");
    user.setPermissionLevel(PermissionLevel.ADMIN);
    user.setActive(active);
    return user;
  }
}
