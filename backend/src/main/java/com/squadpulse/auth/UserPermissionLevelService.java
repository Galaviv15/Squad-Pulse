package com.squadpulse.auth;

import com.squadpulse.common.NotFoundException;
import org.springframework.stereotype.Service;

/**
 * Changes a user's {@link PermissionLevel} within the caller's club (see docs/spec.md section 04:
 * the Club Manager manages Title and Permission Level). Any level may be set, including granting or
 * removing {@code ADMIN}; the user's {@link Title} is left as it is.
 *
 * <p><b>Club isolation.</b> The target is looked up through the club-scoped {@link
 * UserRepository#findById}, so a user in another club is simply not found: same 404, same message
 * as an id that doesn't exist, so this can't be used to probe other clubs' user ids. The save goes
 * through the club-scoped repository too, which re-checks the stored document's real clubId.
 *
 * <p><b>No self-change.</b> A caller can never change their own level, so a club's only {@code
 * ADMIN} can't demote themselves and leave the club with no one able to manage users. This was
 * chosen over a "don't demote the last ADMIN of the club" count: that would be a read-then-write
 * check, and two admins demoting each other concurrently could both pass it. The self-change rule
 * needs no read at all. (Two admins can still demote each other one after the other — each is then
 * a deliberate act by another admin, not a lockout by accident.)
 *
 * <p><b>Inactive or not-yet-activated users</b> ({@code active == false}, or {@code passwordHash ==
 * null} after an invite) can be changed too, deliberately: the level is just stored, and applies
 * whenever they can log in.
 *
 * <p><b>Takes effect at the target's next refresh.</b> An access token already issued to the target
 * keeps its old {@code permissionLevel} claim until it expires (at most one access-token lifetime,
 * see {@link TokenProperties#accessTtl()}); {@link AuthService#refresh} re-reads the user, so the
 * next one carries the new level. The same accepted trade-off as for logout and deactivation (see
 * docs/spec.md section 10): no token revocation, no per-request lookup.
 */
@Service
class UserPermissionLevelService {

  private final UserRepository userRepository;

  UserPermissionLevelService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Setting the level the user already has is a successful no-op.
   *
   * @throws CannotChangeOwnPermissionLevelException if {@code userId} is the caller's own
   * @throws NotFoundException if there's no such user in the caller's club
   */
  User changePermissionLevel(
      String userId, PermissionLevel permissionLevel, AuthenticatedUser caller) {
    if (userId.equals(caller.userId())) {
      throw new CannotChangeOwnPermissionLevelException();
    }
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    if (user.getPermissionLevel() == permissionLevel) {
      return user;
    }
    user.setPermissionLevel(permissionLevel);
    return userRepository.save(user);
  }
}
