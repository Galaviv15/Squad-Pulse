package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** {@link ImageType#detect}: magic bytes only, and nothing but JPEG, PNG and WebP. */
class ImageTypeTest {

  @Test
  void detectsJpegPngAndWebpByTheirMagicBytes() {
    assertThat(ImageType.detect(TestImages.jpeg())).contains(ImageType.JPEG);
    assertThat(ImageType.detect(TestImages.png())).contains(ImageType.PNG);
    assertThat(ImageType.detect(TestImages.webp())).contains(ImageType.WEBP);
  }

  @Test
  void theShortestRecognizableHeadersAreEnough() {
    assertThat(ImageType.detect(bytes(0xFF, 0xD8, 0xFF))).contains(ImageType.JPEG);
    assertThat(ImageType.detect(bytes(0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n')))
        .contains(ImageType.PNG);
    assertThat(ImageType.detect(ascii("RIFF\0\0\0\0WEBP"))).contains(ImageType.WEBP);
  }

  @Test
  void eachTypeHasItsMediaType() {
    assertThat(ImageType.JPEG.mediaType()).isEqualTo("image/jpeg");
    assertThat(ImageType.PNG.mediaType()).isEqualTo("image/png");
    assertThat(ImageType.WEBP.mediaType()).isEqualTo("image/webp");
  }

  static Stream<Arguments> notAcceptedImages() {
    byte[] random = new byte[256];
    new Random(42).nextBytes(random);
    random[0] = 0x00; // make sure it can't start like a JPEG or PNG by chance
    return Stream.of(
        Arguments.of("SVG", TestImages.svg()),
        Arguments.of("SVG with an XML declaration", ascii("<?xml version=\"1.0\"?><svg/>")),
        Arguments.of("GIF", ascii("GIF89a\1\0\1\0")),
        Arguments.of("HTML", ascii("<!DOCTYPE html><html></html>")),
        Arguments.of("PDF", ascii("%PDF-1.7\n")),
        Arguments.of("random bytes", random),
        Arguments.of("empty", new byte[0]),
        Arguments.of("a WebP header cut to 11 bytes", ascii("RIFF\0\0\0\0WEB")),
        Arguments.of("RIFF but WAVE", ascii("RIFF\0\0\0\0WAVEfmt ")),
        Arguments.of("two bytes of a JPEG", bytes(0xFF, 0xD8)),
        Arguments.of("seven bytes of a PNG", bytes(0x89, 'P', 'N', 'G', '\r', '\n', 0x1A)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("notAcceptedImages")
  void anythingElseIsNotDetected(String description, byte[] content) {
    assertThat(ImageType.detect(content)).isEmpty();
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = (byte) values[i];
    }
    return result;
  }
}
