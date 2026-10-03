package com.squadpulse.common;

import java.util.Objects;

/**
 * The slot an image is stored under: what it is for, and the id of the record it belongs to (e.g. a
 * player's id). Always within the caller's club — {@link ImageStorage} adds the clubId itself, so
 * it's deliberately not part of this record.
 */
public record ImageOwner(ImageKind kind, String ownerId) {

  public ImageOwner {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(ownerId, "ownerId");
  }
}
