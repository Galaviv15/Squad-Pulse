package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

/** {@link ImageValidator}: the checks every uploaded image goes through, in order. */
class ImageValidatorTest {

  private static final int TWO_MIB = 2 * 1024 * 1024;

  private final ImageValidator validator =
      new ImageValidator(new ImageProperties(DataSize.ofMegabytes(2)));

  @Test
  void anImageWithinTheLimitIsAcceptedWithTheTypeOfItsContent() {
    ValidatedImage image =
        validator.validate(new MockMultipartFile("file", "x.jpg", "image/jpeg", TestImages.png()));

    assertThat(image.type()).isEqualTo(ImageType.PNG);
    assertThat(image.content()).isEqualTo(TestImages.png());
  }

  @Test
  void exactlyTheLimitIsAccepted() {
    assertThat(validator.validate(file(TestImages.jpegOfSize(TWO_MIB))).type())
        .isEqualTo(ImageType.JPEG);
  }

  @Test
  void oneByteOverTheLimitIsTooLarge() {
    assertThatThrownBy(() -> validator.validate(file(TestImages.jpegOfSize(TWO_MIB + 1))))
        .isInstanceOf(PayloadTooLargeException.class)
        .hasMessage("Image must be at most 2 MB");
  }

  @Test
  void anEmptyFileIsABadRequestOnTheFileField() {
    assertThatThrownBy(() -> validator.validate(file(new byte[0])))
        .isInstanceOfSatisfying(
            BadRequestException.class, e -> assertThat(e.getField()).contains("file"))
        .hasMessage("file: must not be empty");
  }

  @Test
  void aNonImageIsABadRequestOnTheFileField() {
    assertThatThrownBy(() -> validator.validate(file(TestImages.svg())))
        .isInstanceOfSatisfying(
            BadRequestException.class, e -> assertThat(e.getField()).contains("file"))
        .hasMessage("file: must be a JPEG, PNG or WebP image");
  }

  @Test
  void theLimitIsDescribedInTheLargestUnitThatFitsExactly() {
    assertThat(ImageValidator.describe(DataSize.ofMegabytes(2))).isEqualTo("2 MB");
    assertThat(ImageValidator.describe(DataSize.ofKilobytes(500))).isEqualTo("500 KB");
    assertThat(ImageValidator.describe(DataSize.ofBytes(1000))).isEqualTo("1000 bytes");
  }

  @Test
  void aValidatedImageCannotBeBuiltForContentOfAnotherType() {
    assertThatThrownBy(() -> new ValidatedImage(TestImages.png(), ImageType.JPEG))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ValidatedImage(new byte[0], ImageType.JPEG))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theLimitMustBePositive() {
    assertThatThrownBy(() -> new ImageProperties(DataSize.ofBytes(0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MockMultipartFile file(byte[] content) {
    return new MockMultipartFile("file", "photo.jpg", "image/jpeg", content);
  }
}
