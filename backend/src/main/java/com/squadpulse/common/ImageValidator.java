package com.squadpulse.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/**
 * Checks an uploaded image before anything stores it, the same way for every kind of image: not
 * empty, at most {@link ImageProperties#maxSize()}, and a JPEG, PNG or WebP by its own bytes
 * ({@link ImageType#detect}). The client's {@code Content-Type} and file name are ignored and never
 * echoed back.
 *
 * <p>The servlet container's multipart limit ({@code spring.servlet.multipart.max-file-size}) is
 * the outer guard against huge bodies; this is the limit the API promises, and the one that applies
 * wherever the container's doesn't (e.g. MockMvc).
 */
@Component
public class ImageValidator {

  static final String FIELD = "file";

  private final DataSize maxSize;

  public ImageValidator(ImageProperties properties) {
    this.maxSize = properties.maxSize();
  }

  /**
   * @throws BadRequestException for an empty file or one that isn't a JPEG, PNG or WebP image,
   *     scoped to the {@value #FIELD} field
   * @throws PayloadTooLargeException for a file bigger than the configured maximum
   */
  public ValidatedImage validate(MultipartFile file) {
    if (file.isEmpty()) {
      throw new BadRequestException(FIELD, "must not be empty");
    }
    // Checked before reading the content, from the size the container already knows.
    if (file.getSize() > maxSize.toBytes()) {
      throw new PayloadTooLargeException("Image must be at most " + describe(maxSize));
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read the uploaded file", e);
    }
    return ImageType.detect(content)
        .map(type -> new ValidatedImage(content, type))
        .orElseThrow(() -> new BadRequestException(FIELD, "must be a JPEG, PNG or WebP image"));
  }

  /** "2 MB", "500 KB" or "1000 bytes" — the largest unit that divides the size exactly. */
  static String describe(DataSize size) {
    long bytes = size.toBytes();
    if (bytes % DataSize.ofMegabytes(1).toBytes() == 0) {
      return size.toMegabytes() + " MB";
    }
    if (bytes % DataSize.ofKilobytes(1).toBytes() == 0) {
      return size.toKilobytes() + " KB";
    }
    return bytes + " bytes";
  }
}
