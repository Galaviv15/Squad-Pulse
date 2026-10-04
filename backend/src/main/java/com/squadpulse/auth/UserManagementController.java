package com.squadpulse.auth;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User management within the caller's club — only a Club Manager ({@code ADMIN}) may use it: the
 * staff list, invitations and permission-level changes. The caller's own profile, {@code GET
 * /auth/users/me}, is {@link CurrentUserController}'s, open to every authenticated user.
 *
 * <p>Every write here re-reads the caller first and answers the generic 401 if they've been
 * deactivated or no longer exist, even while their access token is still valid (see {@link
 * ActiveCallerCheck}). The read-only staff list doesn't.
 */
@RestController
@RequestMapping("/auth/users")
class UserManagementController {

  private final StaffListService staffListService;
  private final UserInvitationService userInvitationService;
  private final UserPermissionLevelService userPermissionLevelService;
  private final StaffPhotoService staffPhotoService;

  UserManagementController(
      StaffListService staffListService,
      UserInvitationService userInvitationService,
      UserPermissionLevelService userPermissionLevelService,
      StaffPhotoService staffPhotoService) {
    this.staffListService = staffListService;
    this.userInvitationService = userInvitationService;
    this.userPermissionLevelService = userPermissionLevelService;
    this.staffPhotoService = staffPhotoService;
  }

  /**
   * Every user of the caller's club — deactivated and not-yet-activated ones included — as a plain
   * array, in {@link StaffListService#STAFF_ORDER}. Read-only (see {@link StaffListService}).
   *
   * <p>{@code ADMIN} only, deliberately: it exposes emails, dates of birth, permission levels and
   * deactivated users, so it's a management screen, not a staff directory. If every user ever needs
   * a directory, that gets its own slimmer response in a separate ticket rather than opening this
   * one up.
   */
  @GetMapping
  @PreAuthorize("hasAuthority('ADMIN')")
  List<UserResponse> list() {
    return staffListService.list();
  }

  /**
   * Creates a user without a password in the caller's club and emails them an activation code (see
   * {@link UserInvitationService}).
   */
  @PostMapping("/invite")
  @PreAuthorize("hasAuthority('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  UserResponse invite(
      @Valid @RequestBody InviteUserRequest request,
      @AuthenticationPrincipal AuthenticatedUser caller) {
    // A new user can't have a photo yet: no storage query needed.
    return UserResponse.from(userInvitationService.invite(request, caller), false);
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
