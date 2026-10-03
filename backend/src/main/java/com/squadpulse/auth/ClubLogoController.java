package com.squadpulse.auth;

import com.squadpulse.common.ImageValidator;
import com.squadpulse.common.StoredImage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The caller's club's logo (see {@link ClubLogoService}). Reading needs {@code VIEW_ONLY}; setting
 * and removing it {@code ADMIN}. "me" is the caller's club: there's never a club id in the path,
 * and none is ever read from the request.
 *
 * <p><b>Deliberately not under {@code /auth}.</b> The refresh-token cookie is scoped to {@code
 * Path=/auth} ({@link AuthController#REFRESH_COOKIE_PATH}), so the browser attaches it to every
 * request below {@code /auth}. The logo is fetched on every app load and must not carry the most
 * sensitive cookie in the system — don't move this to {@code /auth/...}.
 *
 * <p>The handlers mirror the player photo's in {@code squad.PlayerController}, with the same {@link
 * ImageValidator} and limits.
 */
@RestController
@RequestMapping("/clubs/me/logo")
class ClubLogoController {

  private final ClubLogoService clubLogoService;
  private final ImageValidator imageValidator;

  ClubLogoController(ClubLogoService clubLogoService, ImageValidator imageValidator) {
    this.clubLogoService = clubLogoService;
    this.imageValidator = imageValidator;
  }

  /**
   * Sets the club's logo, replacing any earlier one: a multipart request with the image in the
   * {@code file} part. JPEG, PNG or WebP by content (the declared type and file name are ignored),
   * at most {@code squadpulse.images.max-size}: 204. 400 for an empty or non-image file or a
   * missing part, 413 if too large.
   *
   * <p>No {@code consumes}: a non-multipart request fails as a {@code MultipartException} (400, as
   * documented in the README), not as a {@code HttpMediaTypeNotSupportedException} (415).
   */
  @PutMapping
  @PreAuthorize("hasAuthority('ADMIN')")
  ResponseEntity<Void> upload(@RequestPart("file") MultipartFile file) {
    clubLogoService.upload(imageValidator.validate(file));
    return ResponseEntity.noContent().build();
  }

  /**
   * The club's logo: the image bytes with their detected {@code Content-Type} and {@code
   * Content-Length}; 404 if there's none. Spring Security adds {@code X-Content-Type-Options:
   * nosniff} and {@code Cache-Control: no-store} to every response.
   */
  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  ResponseEntity<Resource> logo() {
    StoredImage logo = clubLogoService.logo();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(logo.type().mediaType()))
        .contentLength(logo.length())
        .body(new InputStreamResource(logo.content()));
  }

  /** Removes the club's logo: 204, also if there was none. */
  @DeleteMapping
  @PreAuthorize("hasAuthority('ADMIN')")
  ResponseEntity<Void> delete() {
    clubLogoService.delete();
    return ResponseEntity.noContent().build();
  }
}
