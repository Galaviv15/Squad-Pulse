package com.squadpulse.common;

import java.util.Arrays;
import java.util.Optional;

/**
 * The image formats {@link ImageStorage} accepts, each recognized by its magic bytes alone. A
 * client's {@code Content-Type} and file name are never consulted: both are whatever the client
 * says, so an SVG (which can carry script) or an HTML page labelled {@code image/png} is still
 * rejected, and an accepted image is always served with the type of what it really is.
 */
public enum ImageType {
  JPEG("image/jpeg"),
  PNG("image/png"),
  WEBP("image/webp");

  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
  private static final byte[] WEBP_FOURCC = {'W', 'E', 'B', 'P'};

  /** "RIFF", a 4-byte chunk size, then "WEBP". */
  private static final int WEBP_FOURCC_OFFSET = 8;

  private final String mediaType;

  ImageType(String mediaType) {
    this.mediaType = mediaType;
  }

  /** The {@code Content-Type} to serve an image of this type with. */
  public String mediaType() {
    return mediaType;
  }

  /**
   * The type of {@code content} by its leading bytes; empty for anything else, including SVG, GIF,
   * HTML and input too short to tell.
   */
  public static Optional<ImageType> detect(byte[] content) {
    if (startsWith(content, 0, JPEG_MAGIC)) {
      return Optional.of(JPEG);
    }
    if (startsWith(content, 0, PNG_MAGIC)) {
      return Optional.of(PNG);
    }
    if (startsWith(content, 0, RIFF) && startsWith(content, WEBP_FOURCC_OFFSET, WEBP_FOURCC)) {
      return Optional.of(WEBP);
    }
    return Optional.empty();
  }

  private static boolean startsWith(byte[] content, int offset, byte[] prefix) {
    if (content.length < offset + prefix.length) {
      return false;
    }
    return Arrays.equals(content, offset, offset + prefix.length, prefix, 0, prefix.length);
  }
}
