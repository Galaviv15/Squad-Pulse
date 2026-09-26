package com.squadpulse.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User management within the caller's club — only a Club Manager ({@code ADMIN}) may use it. */
@RestController
@RequestMapping("/auth/users")
class UserInvitationController {

  private final UserInvitationService userInvitationService;

  UserInvitationController(UserInvitationService userInvitationService) {
    this.userInvitationService = userInvitationService;
  }

  /** Creates a user without a password in the caller's club (see {@link UserInvitationService}). */
  @PostMapping("/invite")
  @PreAuthorize("hasAuthority('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  UserResponse invite(@Valid @RequestBody InviteUserRequest request) {
    return UserResponse.from(userInvitationService.invite(request));
  }
}
