package com.squadpulse.common;

import java.util.Optional;
import java.util.Set;

/**
 * Where images (player photos, the club logo and staff photos) are kept: the only API the rest of
 * the codebase uses for them. Today's implementation is {@link GridFsImageStorage}; this contract
 * is written so it can be replaced by object storage (S3/R2, Phase 6) without touching any caller,
 * which is why nothing here mentions GridFS, file ids or upload dates.
 *
 * <p><b>Club scoping.</b> No method takes a clubId. Every call acts on the caller's club, read from
 * {@link ClubContext#requireClubId()}, and throws {@link MissingClubContextException} when there is
 * none. An implementation stores the clubId with every image and restricts every read and delete to
 * it, so another club's image is never found, served or removed — exactly as if it didn't exist.
 * This store is not a Spring Data repository, so the club-scoped repository layer doesn't protect
 * it: the implementation must apply the clubId itself (see docs/spec.md section 03).
 *
 * <p><b>One current image per owner.</b> {@link #store} makes the new image the owner's current one
 * and removes the older ones. Two concurrent stores for one owner both succeed; afterwards one of
 * the two is the current image, and the other is gone or, briefly, a stale leftover that is never
 * returned and is removed by the owner's next {@link #store} or {@link #delete}.
 *
 * <p><b>Idempotent delete.</b> {@link #delete} removes whatever the owner has, and does nothing if
 * there's nothing to remove — including when a concurrent call removed it first.
 *
 * <p>Callers must not rely on anything else, e.g. how the implementation names or orders its files.
 */
public interface ImageStorage {

  /**
   * Stores {@code image} as the owner's current image, replacing any earlier one. The owner record
   * itself isn't checked: the caller loads it (club-scoped) first.
   */
  void store(ImageOwner owner, ValidatedImage image);

  /** The owner's current image in the caller's club, or empty if it has none. */
  Optional<StoredImage> find(ImageOwner owner);

  /** Whether the owner has an image in the caller's club. */
  boolean exists(ImageOwner owner);

  /**
   * The ids of the owners of {@code kind} that have an image in the caller's club, in a single
   * round trip however many there are — for flagging a whole list at once.
   */
  Set<String> ownerIdsWithImage(ImageKind kind);

  /** Removes all of the owner's images in the caller's club; a no-op if it has none. */
  void delete(ImageOwner owner);
}
