package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.squadpulse.common.ImageKind;
import com.squadpulse.common.ImageStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link StaffListService} on a mocked repository and a mocked {@link ImageStorage} behind the real
 * {@link StaffPhotoService}: the order, where {@code hasPhoto} and {@code activated} come from, and
 * the two-query budget. Club isolation on a real MongoDB is in {@link StaffListIntegrationTest}.
 */
class StaffListServiceTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ImageStorage imageStorage = mock(ImageStorage.class);
  private final StaffListService service =
      new StaffListService(userRepository, new StaffPhotoService(userRepository, imageStorage));

  @Test
  void activeUsersFirstThenByNameThenById() {
    when(userRepository.findAll())
        .thenReturn(
            List.of(
                user("u-5", "Avi", false),
                user("u-4", null, true),
                user("u-3", "Noa", true),
                user("u-2", "Dana", true),
                user("u-1", "Noa", true),
                user("u-6", null, false)));
    when(imageStorage.ownerIdsWithImage(ImageKind.STAFF_PHOTO)).thenReturn(Set.of());

    assertThat(service.list())
        .extracting(UserResponse::id)
        .containsExactly(
            // Active: by name, a missing name last, equal names by id.
            "u-2",
            "u-1",
            "u-3",
            "u-4",
            // Deactivated, ordered the same way among themselves.
            "u-5",
            "u-6");
  }

  /**
   * A user read from MongoDB always has an id, so a missing one is only checked on the comparator
   * itself: it sorts last among equal names, like in {@code SQUAD_ORDER}.
   */
  @Test
  void aMissingIdSortsLastAmongEqualNames() {
    List<User> users =
        new ArrayList<>(
            List.of(user(null, "Noa", true), user("u-2", "Noa", true), user("u-1", "Noa", true)));

    users.sort(StaffListService.STAFF_ORDER);

    assertThat(users).extracting(User::getId).containsExactly("u-1", "u-2", null);
  }

  @Test
  void hasPhotoComesFromTheSetOfIdsWithAPhoto() {
    when(userRepository.findAll())
        .thenReturn(List.of(user("u-1", "Avi", true), user("u-2", "Dana", true)));
    when(imageStorage.ownerIdsWithImage(ImageKind.STAFF_PHOTO))
        .thenReturn(Set.of("u-2", "someone-else"));

    assertThat(service.list())
        .extracting(UserResponse::id, UserResponse::hasPhoto)
        .containsExactly(tuple("u-1", false), tuple("u-2", true));
  }

  @Test
  void activatedComesFromWhetherAPasswordIsSet() {
    User invited = user("u-1", "Avi", true);
    invited.setPasswordHash(null);
    User deactivated = user("u-2", "Dana", false);
    when(userRepository.findAll()).thenReturn(List.of(invited, deactivated));
    when(imageStorage.ownerIdsWithImage(ImageKind.STAFF_PHOTO)).thenReturn(Set.of());

    List<UserResponse> list = service.list();

    assertThat(list.get(0).activated()).isFalse();
    assertThat(list.get(0).active()).isTrue();
    // Deactivation and activation are independent.
    assertThat(list.get(1).activated()).isTrue();
    assertThat(list.get(1).active()).isFalse();
  }

  /**
   * One club-scoped user query and one storage query, whatever the club's size: never a per-user
   * {@code exists}, and nothing is written.
   */
  @Test
  void makesExactlyTwoQueries() {
    when(userRepository.findAll())
        .thenReturn(
            List.of(
                user("u-1", "Avi", true), user("u-2", "Dana", true), user("u-3", "Noa", false)));
    when(imageStorage.ownerIdsWithImage(ImageKind.STAFF_PHOTO)).thenReturn(Set.of("u-1"));

    assertThat(service.list()).hasSize(3);

    verify(userRepository).findAll();
    verify(imageStorage).ownerIdsWithImage(ImageKind.STAFF_PHOTO);
    verify(imageStorage, never()).exists(any());
    verifyNoMoreInteractions(userRepository, imageStorage);
  }

  @Test
  void anEmptyClubIsAnEmptyList() {
    when(userRepository.findAll()).thenReturn(List.of());
    when(imageStorage.ownerIdsWithImage(ImageKind.STAFF_PHOTO)).thenReturn(Set.of());

    assertThat(service.list()).isEmpty();
  }

  private static User user(String id, String fullName, boolean active) {
    User user = new User();
    user.setId(id);
    user.setClubId("club-a");
    user.setEmail((id == null ? "no-id" : id) + "@example.com");
    user.setFullName(fullName);
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(PermissionLevel.VIEW_ONLY);
    user.setActive(active);
    user.setPasswordHash("hash");
    return user;
  }
}
