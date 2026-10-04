package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * {@link ClubSettingsService} on mocked repositories: the caller re-check comes first, the write
 * targets the token's club with a single-field update, and the response is the re-read club.
 */
class ClubSettingsServiceTest {

  private static final AuthenticatedUser ADMIN =
      new AuthenticatedUser("user-1", "club-a", PermissionLevel.ADMIN);

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ClubRepository clubRepository = mock(ClubRepository.class);
  private final ClubLogoService clubLogoService = mock(ClubLogoService.class);
  private final ClubSettingsService service =
      new ClubSettingsService(
          new ActiveCallerCheck(userRepository), clubRepository, clubLogoService);

  // --- read --------------------------------------------------------------------------------------

  @Test
  void theClubIsTheTokensWithItsLogoFlagAndReadingRechecksNobody() {
    when(clubRepository.findById("club-a")).thenReturn(Optional.of(club("Hapoel Example")));
    when(clubLogoService.hasLogo()).thenReturn(true);

    assertThat(service.club(ADMIN)).isEqualTo(new ClubResponse("club-a", "Hapoel Example", true));
    verifyNoInteractions(userRepository);
    verify(clubRepository).findById("club-a");
    verifyNoMoreInteractions(clubRepository);
  }

  @Test
  void readingAMissingClubIsAServerError() {
    when(clubRepository.findById("club-a")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.club(ADMIN))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessageContaining("club-a");
  }

  // --- update ------------------------------------------------------------------------------------

  @Test
  void theCallerIsRecheckedBeforeTheTargetedUpdateOfTheTokensClubThenReRead() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(admin(true)));
    when(clubRepository.findById("club-a")).thenReturn(Optional.of(club("Maccabi Example")));

    service.update(ADMIN, new UpdateClubRequest("Maccabi Example"));

    InOrder order = inOrder(userRepository, clubRepository);
    order.verify(userRepository).findById("user-1");
    order.verify(clubRepository).updateNameByClubId("club-a", "Maccabi Example");
    order.verify(clubRepository).findById("club-a");
    // Never a whole-document save.
    verify(clubRepository, never()).save(any());
    verifyNoMoreInteractions(clubRepository);
  }

  /** Not built from the request: whatever is stored after the write — e.g. a later rename. */
  @Test
  void theResponseComesFromTheReReadNotTheRequest() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(admin(true)));
    when(clubRepository.findById("club-a")).thenReturn(Optional.of(club("Stored Name")));
    when(clubLogoService.hasLogo()).thenReturn(true);

    assertThat(service.update(ADMIN, new UpdateClubRequest("Requested Name")))
        .isEqualTo(new ClubResponse("club-a", "Stored Name", true));
  }

  @Test
  void aDeactivatedCallerWritesNothing() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(admin(false)));

    assertThatThrownBy(() -> service.update(ADMIN, new UpdateClubRequest("Maccabi Example")))
        .isInstanceOf(CurrentUserUnavailableException.class)
        .hasMessage("Authentication required");
    verifyNoInteractions(clubRepository, clubLogoService);
  }

  @Test
  void aCallerNotFoundInTheTokensClubWritesNothing() {
    when(userRepository.findById("user-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(ADMIN, new UpdateClubRequest("Maccabi Example")))
        .isInstanceOf(CurrentUserUnavailableException.class);
    verifyNoInteractions(clubRepository, clubLogoService);
  }

  /**
   * The update matched nothing (it can't say so itself: Spring Data reports the modified count), so
   * the re-read finds no club — a data-integrity bug, a generic 500.
   */
  @Test
  void anUpdateOfAMissingClubIsAServerError() {
    when(userRepository.findById("user-1")).thenReturn(Optional.of(admin(true)));
    when(clubRepository.findById("club-a")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(ADMIN, new UpdateClubRequest("Maccabi Example")))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessageContaining("club-a");
    verifyNoInteractions(clubLogoService);
  }

  private static User admin(boolean active) {
    User user = new User();
    user.setId("user-1");
    user.setClubId("club-a");
    user.setPermissionLevel(PermissionLevel.ADMIN);
    user.setActive(active);
    return user;
  }

  private static Club club(String name) {
    Club club = new Club();
    club.setId("club-a");
    club.setName(name);
    return club;
  }
}
