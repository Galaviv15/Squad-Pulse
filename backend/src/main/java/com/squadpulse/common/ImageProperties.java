package com.squadpulse.common;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on uploaded images, bound from {@code squadpulse.images.*} (see application.yml and {@link
 * ImageValidator}). Validated, so a missing or non-positive limit stops the application from
 * starting.
 *
 * <p>{@code spring.servlet.multipart.max-file-size} must be at least {@code maxSize}, or the
 * servlet container rejects valid images before this limit is ever checked.
 *
 * @param maxSize the largest image accepted; anything bigger is a 413
 */
@Validated
@ConfigurationProperties(prefix = "squadpulse.images")
public record ImageProperties(@NotNull DataSize maxSize) {

  public ImageProperties {
    if (maxSize != null && maxSize.toBytes() <= 0) {
      throw new IllegalArgumentException("squadpulse.images.max-size must be positive");
    }
  }
}
