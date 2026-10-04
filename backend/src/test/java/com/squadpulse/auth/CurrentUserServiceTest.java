package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link CurrentUserService} on mocked repositories: which values come from the token and which
 * from the database, and every way the caller can turn out to be unavailable.
 */
class CurrentUserServiceTest {

  private static final AuthenticatedUser CALLER =
      new AuthenticatedUser("user-1", "club-a", PermissionLevel.EDIT_FULL);

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ClubRepository clubRepository = mock(ClubRepository.class);
  private final ClubLogoService clubLogoService = mock(ClubLogoService.class);
  private final StaffPhotoService staffPhotoService = mock(StaffPhotoService.class);
  private final CurrentUserService service =
      new CurrentUserService(
          new ActiveCallerCheck(userRepository),
          clubRepository,
          clubLogoService,
          staffPhotoService);

  @Test
  void theLevelComesFromTheTokenAndEverythingElseFromTheDatabase() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(user(true)));
    when(clubRepository.findById("club-a")).thenReturn(Optional.of(club()));

    CurrentUserResponse response = service.currentUser(CALLER);

    assertThat(response)
        .isEqualTo(
            new CurrentUserResponse(
                "user-1",
                "coach@example.com",
                "Dana Levi",
                Title.HEAD_COACH,
                PermissionLevel.EDIT_FULL,
                LocalDate.of(1985, 3, 1),
                true,
                false,
                true,
                new ClubResponse("club-a", "Hapoel Example", false)));
  }

  @Test
  void hasPhotoComesFromTheCallersPhotoInStorage() {
    User user = user(true);
    when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    when(clubRepository.findById("club-a")).thenReturn(Optional.of(club()));
    when(staffPhotoService.hasPhoto(user)).thenReturn(true);

    assertThat(service.currentUser(CALLER).hasPhoto()).isTrue();
  }

  /**
   * Derived from the stored user, not hard-coded. A caller without a password can't really get a
   * token, so this only proves where the value comes from.
   */
  @Test
  void activatedComesFromTheStoredPasswordHash() {
    User user = user(true);
    user.setPasswordHash(null);
    when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    when(clubRepository.findById("club-a")).thenReturn(Optional.of(club()));

    assertThat(service.currentUser(CALLER).activated()).isFalse();
  }

  @Test
  void hasLogoComesFromTheLogoStorage() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(user(true)));
    when(clubRepository.findById("club-a")).thenReturn(Optional.of(club()));
    when(clubLogoService.hasLogo()).thenReturn(true);

    assertThat(service.currentUser(CALLER).club())
        .isEqualTo(new ClubResponse("club-a", "Hapoel Example", true));
  }

  @Test
  void aDeactivatedUserIsUnavailable() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(user(false)));

    assertThatThrownBy(() -> service.currentUser(CALLER))
        .isInstanceOf(CurrentUserUnavailableException.class)
        .hasMessage("Authentication required");
    verify(clubRepository, never()).findById("club-a");
  }

  /**
   * Deleted, or in another club — the club-scoped lookup can't tell, and neither can the caller.
   */
  @Test
  void aUserNotFoundInTheTokensClubIsUnavailable() {
    when(userRepository.findById("user-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.currentUser(CALLER))
        .isInstanceOf(CurrentUserUnavailableException.class)
        .hasMessage("Authentication required");
    verify(clubRepository, never()).findById("club-a");
  }

  @Test
  void aMissingClubIsAServerErrorNotA401() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(user(true)));
    when(clubRepository.findById("club-a")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.currentUser(CALLER))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessageContaining("club-a");
  }

  @Test
  void neverWritesAnything() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(user(true)));
    when(clubRepository.findById("club-a")).thenReturn(Optional.of(club()));

    service.currentUser(CALLER);

    verify(userRepository).findById("user-1");
    verify(clubRepository).findById("club-a");
    verify(clubLogoService).hasLogo();
    verify(staffPhotoService).hasPhoto(any());
    verifyNoMoreInteractions(userRepository, clubRepository, clubLogoService, staffPhotoService);
  }

  private static User user(boolean active) {
    User user = new User();
    user.setId("user-1");
    user.setClubId("club-a");
    user.setEmail("coach@example.com");
    user.setFullName("Dana Levi");
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(PermissionLevel.VIEW_ONLY);
    user.setDateOfBirth(LocalDate.of(1985, 3, 1));
    user.setActive(active);
    user.setPasswordHash("hash-that-must-not-leak");
    return user;
  }

  private static Club club() {
    Club club = new Club();
    club.setId("club-a");
    club.setName("Hapoel Example");
    return club;
  }
}
