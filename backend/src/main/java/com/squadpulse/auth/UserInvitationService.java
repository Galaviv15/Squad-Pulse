package com.squadpulse.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Creates a user invited by a Club Manager into the caller's club (see docs/spec.md sections 04 and
 * 09), and emails them an activation code.
 *
 * <p>The invited user is created with <b>no password</b> ({@code passwordHash == null}), so they
 * can't log in until they set one with that code through {@code POST /auth/reset-password} (see
 * {@link PasswordResetService}). {@code active} keeps its default — it's the Club Manager's
 * deactivation switch, unrelated to whether a password has been set.
 *
 * <p>The Mongo insert and the Redis write of the code aren't one transaction. If issuing or sending
 * the code fails after the insert, the invite fails (a 500) but the user exists — inviting them
 * again is a 409 — and they get a code through forgot-password like anyone else.
 */
@Service
class UserInvitationService {

  private final UserRepository userRepository;
  private final PasswordResetService passwordResetService;

  UserInvitationService(UserRepository userRepository, PasswordResetService passwordResetService) {
    this.userRepository = userRepository;
    this.passwordResetService = passwordResetService;
  }

  /**
   * @throws EmailAlreadyRegisteredException if a user with that email already exists, in any club —
   *     no code is sent then
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
    User invited;
    try {
      invited = userRepository.insert(user);
    } catch (DuplicateKeyException e) {
      throw new EmailAlreadyRegisteredException();
    }
    passwordResetService.sendActivationCode(invited);
    return invited;
  }
}
