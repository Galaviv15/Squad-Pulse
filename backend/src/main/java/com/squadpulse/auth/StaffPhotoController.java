package com.squadpulse.auth;

import com.squadpulse.common.ImageValidator;
import com.squadpulse.common.StoredImage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Staff users' profile photos (see {@link StaffPhotoService}): {@code /users/me/photo} for the
 * caller's own, {@code /users/{id}/photo} for any user of the caller's club.
 *
 * <p><b>Permissions.</b> Every authenticated user ({@code VIEW_ONLY}) may read, set and remove
 * their <b>own</b> photo — the first endpoints where {@code VIEW_ONLY} writes anything. That's
 * deliberate: it's the caller's own profile, never another user's or club data. "me" is always the
 * user id from the access token ({@link AuthenticatedUser#userId()}), never read from the request.
 * Reading any club member's photo also needs {@code VIEW_ONLY}; setting or removing another user's
 * needs {@code ADMIN} (an admin may use the {@code {id}} form on themselves too). The literal
 * {@code me} segment takes precedence over {@code {id}} in Spring's path matching, so {@code PUT
 * /users/me/photo} is never caught by the {@code ADMIN} rule ({@code StaffPhotoControllerTest}
 * proves it).
 *
 * <p><b>Deliberately not under {@code /auth}</b>, although the other user endpoints are ({@code
 * /auth/users/...}): for the same reason as {@link ClubLogoController}. The refresh-token cookie is
 * scoped to {@code Path=/auth} ({@link AuthController#REFRESH_COOKIE_PATH}), so the browser
 * attaches it to every request below {@code /auth}, and staff photos are fetched often (app header,
 * staff lists) — don't move this to {@code /auth/users/...} to "match".
 *
 * <p>The handlers mirror {@link ClubLogoController}'s, with the same {@link ImageValidator} and
 * limits.
 */
@RestController
@RequestMapping("/users")
class StaffPhotoController {

  private final StaffPhotoService staffPhotoService;
  private final ImageValidator imageValidator;

  StaffPhotoController(StaffPhotoService staffPhotoService, ImageValidator imageValidator) {
    this.staffPhotoService = staffPhotoService;
    this.imageValidator = imageValidator;
  }

  // The "me" handlers and the {id} ones share private helpers rather than calling each other: a
  // call to another handler would only skip its @PreAuthorize because it's a self-invocation.

  /** Sets the caller's own photo; see {@link #upload}. */
  @PutMapping("/me/photo")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  ResponseEntity<Void> uploadOwn(
      @RequestPart("file") MultipartFile file, @AuthenticationPrincipal AuthenticatedUser caller) {
    return store(caller.userId(), file);
  }

  /** The caller's own photo; see {@link #photo}. */
  @GetMapping("/me/photo")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  ResponseEntity<Resource> ownPhoto(@AuthenticationPrincipal AuthenticatedUser caller) {
    return serve(caller.userId());
  }

  /** Removes the caller's own photo; see {@link #delete}. */
  @DeleteMapping("/me/photo")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  ResponseEntity<Void> deleteOwn(@AuthenticationPrincipal AuthenticatedUser caller) {
    return remove(caller.userId());
  }

  /**
   * Sets the user's photo, replacing any earlier one: a multipart request with the image in the
   * {@code file} part. JPEG, PNG or WebP by content (the declared type and file name are ignored),
   * at most {@code squadpulse.images.max-size}: 204. 400 for an empty or non-image file or a
   * missing part, 404 if there's no such user in the caller's club, 409 if the user is deactivated,
   * 413 if too large.
   *
   * <p>No {@code consumes}: a non-multipart request fails as a {@code MultipartException} (400, as
   * documented in the README), not as a {@code HttpMediaTypeNotSupportedException} (415).
   */
  @PutMapping("/{id}/photo")
  @PreAuthorize("hasAuthority('ADMIN')")
  ResponseEntity<Void> upload(@PathVariable String id, @RequestPart("file") MultipartFile file) {
    return store(id, file);
  }

  /**
   * The user's photo, deactivated users' too: the image bytes with their detected {@code
   * Content-Type} and {@code Content-Length}; 404 if there's no such user in the caller's club or
   * they have no photo. Spring Security adds {@code X-Content-Type-Options: nosniff} and {@code
   * Cache-Control: no-store} to every response.
   */
  @GetMapping("/{id}/photo")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  ResponseEntity<Resource> photo(@PathVariable String id) {
    return serve(id);
  }

  /**
   * Removes the user's photo: 204, also if there was none. 404 if there's no such user in the
   * caller's club, 409 if the user is deactivated.
   */
  @DeleteMapping("/{id}/photo")
  @PreAuthorize("hasAuthority('ADMIN')")
  ResponseEntity<Void> delete(@PathVariable String id) {
    return remove(id);
  }

  private ResponseEntity<Void> store(String userId, MultipartFile file) {
    staffPhotoService.upload(userId, imageValidator.validate(file));
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<Resource> serve(String userId) {
    StoredImage photo = staffPhotoService.photo(userId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(photo.type().mediaType()))
        .contentLength(photo.length())
        .body(new InputStreamResource(photo.content()));
  }

  private ResponseEntity<Void> remove(String userId) {
    staffPhotoService.delete(userId);
    return ResponseEntity.noContent().build();
  }
}
