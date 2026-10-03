package com.squadpulse.auth;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own profile, for any authenticated user — unlike {@link UserManagementController},
 * which is {@code ADMIN}-only. No path id: it always answers about the caller (see {@link
 * CurrentUserService}).
 */
@RestController
class CurrentUserController {

  private final CurrentUserService currentUserService;

  CurrentUserController(CurrentUserService currentUserService) {
    this.currentUserService = currentUserService;
  }

  @GetMapping("/auth/users/me")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
    return currentUserService.currentUser(caller);
  }
}
