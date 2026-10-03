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
  private final StaffPhotoService staffPhotoService;

  UserManagementController(
      UserInvitationService userInvitationService,
      UserPermissionLevelService userPermissionLevelService,
      StaffPhotoService staffPhotoService) {
    this.userInvitationService = userInvitationService;
    this.userPermissionLevelService = userPermissionLevelService;
    this.staffPhotoService = staffPhotoService;
  }

  /**
   * Creates a user without a password in the caller's club and emails them an activation code (see
   * {@link UserInvitationService}).
   */
  @PostMapping("/invite")
  @PreAuthorize("hasAuthority('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  UserResponse invite(@Valid @RequestBody InviteUserRequest request) {
    // A new user can't have a photo yet: no storage query needed.
    return UserResponse.from(userInvitationService.invite(request), false);
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
    User user =
        userPermissionLevelService.changePermissionLevel(id, request.permissionLevel(), caller);
    return UserResponse.from(user, staffPhotoService.hasPhoto(user));
  }
}
