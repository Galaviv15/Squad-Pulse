package com.squadpulse.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User management within the caller's club — only a Club Manager ({@code ADMIN}) may use it. */
@RestController
@RequestMapping("/auth/users")
class UserManagementController {

  private final UserInvitationService userInvitationService;
  private final UserPermissionLevelService userPermissionLevelService;

  UserManagementController(
      UserInvitationService userInvitationService,
      UserPermissionLevelService userPermissionLevelService) {
    this.userInvitationService = userInvitationService;
    this.userPermissionLevelService = userPermissionLevelService;
  }

  /** Creates a user without a password in the caller's club (see {@link UserInvitationService}). */
  @PostMapping("/invite")
  @PreAuthorize("hasAuthority('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  UserResponse invite(@Valid @RequestBody InviteUserRequest request) {
    return UserResponse.from(userInvitationService.invite(request));
  }

  /**
   * Sets another user's permission level, in the caller's club only; takes effect at that user's
   * next refresh (see {@link UserPermissionLevelService}).
   */
  @PatchMapping("/{id}/permission-level")
  @PreAuthorize("hasAuthority('ADMIN')")
  UserResponse changePermissionLevel(
      @PathVariable String id,
      @Valid @RequestBody UpdatePermissionLevelRequest request,
      @AuthenticationPrincipal AuthenticatedUser caller) {
    return UserResponse.from(
        userPermissionLevelService.changePermissionLevel(id, request.permissionLevel(), caller));
  }
}
