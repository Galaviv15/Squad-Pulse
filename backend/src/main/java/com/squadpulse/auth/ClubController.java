package com.squadpulse.auth;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's club's settings (see {@link ClubSettingsService}). Reading needs {@code VIEW_ONLY};
 * changing them {@code ADMIN}. "me" is the caller's club, from the access token: there's never a
 * club id in the path or body, and none is ever read from the request.
 *
 * <p><b>Deliberately not under {@code /auth}</b>, for the same reason as {@link
 * ClubLogoController}: the refresh-token cookie is scoped to {@code Path=/auth} ({@link
 * AuthController#REFRESH_COOKIE_PATH}), and requests that don't need it mustn't carry it.
 */
@RestController
@RequestMapping("/clubs/me")
class ClubController {

  private final ClubSettingsService clubSettingsService;

  ClubController(ClubSettingsService clubSettingsService) {
    this.clubSettingsService = clubSettingsService;
  }

  /** The caller's club: {@code id}, {@code name}, {@code hasLogo}. */
  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  ClubResponse club(@AuthenticationPrincipal AuthenticatedUser caller) {
    return clubSettingsService.club(caller);
  }

  /**
   * A partial update of the club's settings, returning the club as stored. Today {@code name} is
   * the only setting, so it's required (trimmed, then 1–{@value Club#NAME_MAX_LENGTH} characters);
   * settings added later are optional fields of this same body, absent meaning "unchanged". No
   * {@code version}: the later of two renames wins. Re-checks the caller first, like the
   * user-management writes: a deactivated caller gets the generic 401.
   */
  @PatchMapping
  @PreAuthorize("hasAuthority('ADMIN')")
  ClubResponse update(
      @Valid @RequestBody UpdateClubRequest request,
      @AuthenticationPrincipal AuthenticatedUser caller) {
    return clubSettingsService.update(caller, request);
  }
}
