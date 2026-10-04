package com.squadpulse.auth;

import com.squadpulse.common.NotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
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
 * <p><b>Caller re-check.</b> Before anything else, the caller is re-read (see {@link
 * ActiveCallerCheck}): a deactivated or deleted {@code ADMIN} gets the generic 401 even while their
 * access token is still valid, so they can't use that window to demote the club's remaining admins.
 * Done once, before the retry loop. Order: caller (401), self-change (409), target (404).
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
 * docs/spec.md section 10): no token revocation, and no per-request lookup of the target. (The
 * caller is re-read, see above, but only on user-management writes, and only to refuse a
 * deactivated one — never to pick up a changed level.)
 *
 * <p><b>Concurrent writes: reload and retry, not 409</b> (KAN-24). If the user is saved by someone
 * else between the load and the save (e.g. their own password reset), the save fails on the version
 * check. The request is "set the level to X" — an absolute value, on a field no other writer
 * touches — so reloading and applying it again loses nothing and needs no decision from the admin;
 * it's retried (see {@link UserWriteRetry}), and only if every attempt conflicts does it become a
 * 409. That reasoning is specific to this endpoint: a future multi-field "edit user" endpoint,
 * where the admin submits changes based on what they saw, should probably answer 409 on a conflict
 * instead, so the admin reviews the newer state rather than silently overwriting it.
 */
@Service
class UserPermissionLevelService {

  private final UserRepository userRepository;
  private final ActiveCallerCheck activeCallerCheck;

  UserPermissionLevelService(UserRepository userRepository, ActiveCallerCheck activeCallerCheck) {
    this.userRepository = userRepository;
    this.activeCallerCheck = activeCallerCheck;
  }

  /**
   * Setting the level the user already has is a successful no-op — checked against the reloaded
   * user on every attempt, so a concurrent change to the same level isn't saved again.
   *
   * @throws CurrentUserUnavailableException if the caller no longer exists in their club or has
   *     been deactivated
   * @throws CannotChangeOwnPermissionLevelException if {@code userId} is the caller's own
   * @throws NotFoundException if there's no such user in the caller's club — including one deleted
   *     between attempts
   * @throws OptimisticLockingFailureException if all {@link UserWriteRetry#MAX_ATTEMPTS} attempts
   *     lost a race
   */
  User changePermissionLevel(
      String userId, PermissionLevel permissionLevel, AuthenticatedUser caller) {
    activeCallerCheck.requireActive(caller);
    if (userId.equals(caller.userId())) {
      throw new CannotChangeOwnPermissionLevelException();
    }
    return UserWriteRetry.withRetry(
        () -> {
          User user =
              userRepository
                  .findById(userId)
                  .orElseThrow(() -> new NotFoundException("User not found"));
          if (user.getPermissionLevel() == permissionLevel) {
            return user;
          }
          user.setPermissionLevel(permissionLevel);
          return userRepository.save(user);
        });
  }
}
