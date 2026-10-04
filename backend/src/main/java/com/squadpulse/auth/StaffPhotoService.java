package com.squadpulse.auth;

import com.squadpulse.common.ImageKind;
import com.squadpulse.common.ImageOwner;
import com.squadpulse.common.ImageStorage;
import com.squadpulse.common.NotFoundException;
import com.squadpulse.common.StoredImage;
import com.squadpulse.common.ValidatedImage;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * A staff user's optional profile photo (KAN-32), kept in {@link ImageStorage} under {@link
 * ImageKind#STAFF_PHOTO} with the user's id as the owner id.
 *
 * <p><b>Club isolation.</b> Every operation first loads the user through the club-scoped {@link
 * UserRepository#findById}, so a user of another club — or no user at all — is a 404 before storage
 * is touched; the storage itself is club-scoped by the same token clubId.
 *
 * <p><b>The {@link User} document is never written.</b> The photo isn't on it, and nothing here
 * saves a user: the user is only read. So its {@code @Version} and {@code updatedAt} never change,
 * a photo can't conflict with a concurrent write to the user, and there's nothing to retry (unlike
 * {@link UserWriteRetry}).
 *
 * <p><b>Deactivated users</b> are treated like released players: their photo can still be read, but
 * uploading or removing it is a 409 ({@link DeactivatedUserException}) — whoever the caller is,
 * including the deactivated user themselves with a still-valid access token. Deactivation keeps the
 * photo.
 *
 * <p><b>Simpler than player photos, deliberately.</b> Unlike {@code PlayerService.uploadPhoto},
 * there's no re-check after storing: that guards against the owner being permanently deleted
 * meanwhile, and users can't be permanently deleted. If a user hard-delete is ever added, it must
 * delete the photo too, and {@link #upload} must gain that re-check. A deactivation racing an
 * upload can leave a photo stored for a just-deactivated user — harmless, since deactivation keeps
 * the photo anyway.
 */
@Service
class StaffPhotoService {

  private final UserRepository userRepository;
  private final ImageStorage imageStorage;

  StaffPhotoService(UserRepository userRepository, ImageStorage imageStorage) {
    this.userRepository = userRepository;
    this.imageStorage = imageStorage;
  }

  /**
   * Stores {@code photo} as the user's photo, replacing any earlier one.
   *
   * @throws NotFoundException if there's no such user in the caller's club
   * @throws DeactivatedUserException if the user has been deactivated
   */
  void upload(String userId, ValidatedImage photo) {
    requireActive(load(userId));
    imageStorage.store(photoOf(userId), photo);
  }

  /**
   * The user's photo; deactivated users' too.
   *
   * @throws NotFoundException if there's no such user in the caller's club, or they have no photo
   */
  StoredImage photo(String userId) {
    load(userId);
    return imageStorage
        .find(photoOf(userId))
        .orElseThrow(() -> new NotFoundException("This user has no photo"));
  }

  /**
   * Removes the user's photo; nothing to do if there's none.
   *
   * @throws NotFoundException if there's no such user in the caller's club
   * @throws DeactivatedUserException if the user has been deactivated
   */
  void delete(String userId) {
    requireActive(load(userId));
    imageStorage.delete(photoOf(userId));
  }

  /**
   * Whether the user — already loaded through the club-scoped repository — has a photo, in one
   * storage query.
   */
  boolean hasPhoto(User user) {
    return imageStorage.exists(photoOf(user.getId()));
  }

  /**
   * The ids of the club's users that have a photo, in one storage query (see {@link #hasPhoto}).
   */
  Set<String> userIdsWithPhoto() {
    return imageStorage.ownerIdsWithImage(ImageKind.STAFF_PHOTO);
  }

  private User load(String userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new NotFoundException("User not found"));
  }

  private static void requireActive(User user) {
    if (!user.isActive()) {
      throw new DeactivatedUserException();
    }
  }

  private static ImageOwner photoOf(String userId) {
    return new ImageOwner(ImageKind.STAFF_PHOTO, userId);
  }
}
