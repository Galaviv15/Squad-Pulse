package com.squadpulse.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Creates a user invited by a Club Manager into the caller's club (see docs/spec.md sections 04 and
 * 09).
 *
 * <p>The invited user is created with <b>no password</b> ({@code passwordHash == null}), so they
 * can't log in yet: setting the password through an activation code is KAN-21. {@code active} keeps
 * its default — it's the Club Manager's deactivation switch, unrelated to whether a password has
 * been set.
 */
@Service
class UserInvitationService {

  private final UserRepository userRepository;

  UserInvitationService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * @throws EmailAlreadyRegisteredException if a user with that email already exists, in any club
   * @throws com.squadpulse.common.MissingClubContextException if there's no club context
   */
  User invite(InviteUserRequest request) {
    User user = new User();
    user.setEmail(request.email());
    user.setFullName(request.fullName());
    user.setTitle(request.title());
    user.setPermissionLevel(request.permissionLevel());
    user.setDateOfBirth(request.dateOfBirth());
    // clubId is deliberately left unset: the club-scoped repository stamps the caller's clubId from
    // ClubContext, which the JWT filter set from the caller's access token.
    try {
      return userRepository.insert(user);
    } catch (DuplicateKeyException e) {
      throw new EmailAlreadyRegisteredException();
    }
  }
}
