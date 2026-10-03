package com.squadpulse.common;

import java.io.InputStream;

/**
 * An owner's current image as read from {@link ImageStorage}.
 *
 * @param type the type detected when it was stored — serve it with {@link ImageType#mediaType()}
 * @param length the content's size in bytes
 * @param content the image bytes; the caller must close it (handing it to Spring MVC as an {@code
 *     InputStreamResource} body does that)
 */
public record StoredImage(ImageType type, long length, InputStream content) {}
