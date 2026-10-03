package com.squadpulse.common;

import java.util.Objects;

/**
 * Image content that has passed {@link ImageValidator}: not empty, within the size limit, and of a
 * {@link ImageType} detected from its own bytes. {@link ImageStorage#store} only accepts this, so
 * unchecked bytes can't reach storage. The constructor re-checks what it can without the
 * configuration (not empty, {@code type} matches the content); the size limit is the validator's.
 *
 * <p>The array is not copied: treat it as read-only once wrapped.
 */
public record ValidatedImage(byte[] content, ImageType type) {

  public ValidatedImage {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(type, "type");
    if (content.length == 0 || ImageType.detect(content).filter(type::equals).isEmpty()) {
      throw new IllegalArgumentException("content is not a " + type + " image");
    }
  }
}
