package com.squadpulse.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal image contents for tests: each starts with its format's real magic bytes, followed by
 * filler. Enough for {@link ImageType#detect}, which is all the application looks at — it never
 * decodes an image.
 */
public final class TestImages {

  private static final byte[] JPEG_HEADER = {
    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00
  };
  private static final byte[] PNG_HEADER = {
    (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
  };
  private static final byte[] WEBP_HEADER = {
    'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '
  };

  private TestImages() {}

  public static byte[] jpeg() {
    return withFiller(JPEG_HEADER, 64, (byte) 1);
  }

  /** A JPEG of exactly {@code size} bytes. */
  public static byte[] jpegOfSize(int size) {
    return withFiller(JPEG_HEADER, size, (byte) 7);
  }

  public static byte[] png() {
    return withFiller(PNG_HEADER, 64, (byte) 2);
  }

  /** A PNG whose content differs from {@link #png()} — to tell two uploads apart. */
  public static byte[] otherPng() {
    return withFiller(PNG_HEADER, 96, (byte) 9);
  }

  public static byte[] webp() {
    return withFiller(WEBP_HEADER, 64, (byte) 3);
  }

  public static byte[] svg() {
    return "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] withFiller(byte[] header, int size, byte filler) {
    byte[] content = Arrays.copyOf(header, size);
    Arrays.fill(content, header.length, size, filler);
    return content;
  }
}
