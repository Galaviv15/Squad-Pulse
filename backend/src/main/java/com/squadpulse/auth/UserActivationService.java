package com.squadpulse.auth;

import com.squadpulse.common.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Deactivates and re-activates a user of the caller's club (KAN-37): the Club Manager's switch for
 * cutting a departed staff member's access without deleting the account (see {@link
 * User#isActive()}).
 *
 * <p><b>What deactivation does.</b> It sets {@code active = false}, which login, refresh,
 * forgot-password, reset-password, {@code GET /auth/users/me} and staff photo writes already refuse
 * (see {@link AuthService}, {@link PasswordResetService}, {@link CurrentUserService}, {@link
 * StaffPhotoService}), and ends every refresh session the user has by setting {@link
 * User#getSessionsInvalidatedAt()}. Everything else is kept: password hash, permission level,
 * title, photo, and the user's place in the staff list. An access token already issued keeps
 * working until it expires (at most one access-token lifetime, see {@link
 * TokenProperties#accessTtl()}) — the accepted trade-off of docs/spec.md section 10 — except on
 * {@code /me} and on the user-management writes (see {@link ActiveCallerCheck}).
 *
 * <p><b>Why both operations set {@code sessionsInvalidatedAt}.</b> {@link AuthService#refresh}
 * checks {@code active} only when a refresh actually happens. Without the timestamp, a refresh
 * cookie that simply wasn't presented while the user was deactivated would still be in Redis, and
 * would come back to life on re-activation. Deactivation sets it to end those sessions.
 * Re-activation sets it <b>too</b>: while a user is inactive, login is refused, so no legitimate
 * session can have started since — invalidating again costs nothing — and it also covers users
 * deactivated directly in the database before this endpoint existed, who never got a deactivation
 * timestamp.
 *
 * <p><b>Idempotent: already in the requested state is a 200 no-op.</b> The current user is returned
 * and nothing is saved — no {@code version} bump, no {@code updatedAt} or {@code
 * sessionsInvalidatedAt} change. This differs on purpose from players, where releasing a released
 * player is a 409: a player edit carries the client's {@code version} because it's a form based on
 * what someone saw, while these are absolute single-field commands ("make this user inactive") with
 * no version in the request, so a repeat has nothing to conflict with.
 *
 * <p><b>Order of checks:</b> the caller is still active in their club (else the generic 401, see
 * {@link ActiveCallerCheck}), then not acting on themselves (409, {@link
 * CannotChangeOwnActiveStatusException}), then the target exists in the caller's club (404). The
 * self-rule follows {@link UserPermissionLevelService}'s: a club's only {@code ADMIN} can never
 * deactivate themselves, with no read-then-write "last ADMIN" count that two concurrent requests
 * could both pass. Deactivating <b>another</b> {@code ADMIN} is allowed; their level is unchanged.
 *
 * <p><b>Club isolation.</b> The target is looked up through the club-scoped {@link
 * UserRepository#findById}, so another club's user is the same 404 as an id that doesn't exist —
 * checked before the no-op, so it can't confirm the id either. The save goes through the
 * club-scoped repository, which re-checks the stored document's real clubId.
 *
 * <p><b>Concurrent writes: reload and retry</b> (KAN-24). Each is an absolute change to a field no
 * other writer sets, so a lost optimistic-locking race is retried through {@link UserWriteRetry}:
 * every attempt reloads the target, re-checks the no-op, and re-applies the change — so a
 * concurrent permission-level change or password reset survives alongside it. Only if every attempt
 * conflicts does it become a 409.
 */
@Service
class UserActivationService {

  private final UserRepository userRepository;
  private final ActiveCallerCheck activeCallerCheck;
  private final Clock clock;

  @Autowired
  UserActivationService(UserRepository userRepository, ActiveCallerCheck activeCallerCheck) {
    this(userRepository, activeCallerCheck, Clock.systemUTC());
  }

  /** For tests: {@code clock} decides the {@code sessionsInvalidatedAt} that a change sets. */
  UserActivationService(
      UserRepository userRepository, ActiveCallerCheck activeCallerCheck, Clock clock) {
    this.userRepository = userRepository;
    this.activeCallerCheck = activeCallerCheck;
    this.clock = clock;
  }

  /**
   * Sets {@code active = false} and ends every refresh session of the user. A user who is already
   * deactivated is returned unchanged, without a save.
   *
   * @throws CurrentUserUnavailableException if the caller no longer exists in their club or has
   *     been deactivated
   * @throws CannotChangeOwnActiveStatusException if {@code userId} is the caller's own
   * @throws NotFoundException if there's no such user in the caller's club — including one deleted
   *     between attempts
   * @throws OptimisticLockingFailureException if all {@link UserWriteRetry#MAX_ATTEMPTS} attempts
   *     lost a race
   */
  User deactivate(String userId, AuthenticatedUser caller) {
    return setActive(userId, false, caller);
  }

  /**
   * Sets {@code active = true} and ends every refresh session of the user (see the class comment
   * for why). A user who is already active is returned unchanged, without a save. Sends no email
   * and needs no new password; a user who never activated stays without one.
   *
   * @throws CurrentUserUnavailableException if the caller no longer exists in their club or has
   *     been deactivated
   * @throws CannotChangeOwnActiveStatusException if {@code userId} is the caller's own
   * @throws NotFoundException if there's no such user in the caller's club — including one deleted
   *     between attempts
   * @throws OptimisticLockingFailureException if all {@link UserWriteRetry#MAX_ATTEMPTS} attempts
   *     lost a race
   */
  User reactivate(String userId, AuthenticatedUser caller) {
    return setActive(userId, true, caller);
  }

  private User setActive(String userId, boolean active, AuthenticatedUser caller) {
    activeCallerCheck.requireActive(caller);
    if (userId.equals(caller.userId())) {
      throw new CannotChangeOwnActiveStatusException();
    }
    return UserWriteRetry.withRetry(
        () -> {
          User user =
              userRepository
                  .findById(userId)
                  .orElseThrow(() -> new NotFoundException("User not found"));
          if (user.isActive() == active) {
            return user;
          }
          user.setActive(active);
          user.setSessionsInvalidatedAt(
              notBefore(user.getSessionsInvalidatedAt(), clock.instant()));
          return userRepository.save(user);
        });
  }

  /**
   * {@code now}, unless the stored value is already later — {@code sessionsInvalidatedAt} never
   * moves backwards. {@code now} is read inside each attempt, after the reload, so it's normally
   * later than anything already stored. But a concurrent password reset may have written an instant
   * from a clock ahead of this one (another app instance, or this clock stepped back); overwriting
   * it with an earlier one would let a session started between the two survive that reset. The same
   * goes for a reading taken before a retry, which is why it's never reused across attempts.
   */
  private static Instant notBefore(Instant stored, Instant now) {
    return stored != null && stored.isAfter(now) ? stored : now;
  }
}
