package com.squadpulse.auth;

import org.springframework.stereotype.Component;

/**
 * Whether the caller named by an access token can still act: they exist in the token's club and are
 * active. The one definition of that, shared by {@code GET /auth/users/me} (see {@link
 * CurrentUserService}), every user-management write (invite, permission level, deactivate,
 * re-activate — see {@link UserManagementController}) and the club-settings write ({@code PATCH
 * /clubs/me}, see {@link ClubSettingsService}).
 *
 * <p><b>Why the writes re-check at all</b> (KAN-37). {@link JwtAuthenticationFilter} doesn't look
 * the user up, so an access token keeps working for up to one access-token lifetime after its user
 * is deactivated (an accepted trade-off, docs/spec.md section 10). For most endpoints that window
 * is harmless, but a deactivated {@code ADMIN} could use it to deactivate or demote the club's last
 * remaining {@code ADMIN}, leaving nobody able to manage users — recoverable only by hand in the
 * database; or rename the club. Re-reading the caller closes that for the endpoints where it
 * matters, at the cost of one extra club-scoped read per such write. Nowhere else does a
 * per-request lookup: the read-only staff list, for instance, doesn't call this.
 *
 * <p><b>Club isolation.</b> The caller is loaded through the club-scoped {@link
 * UserRepository#findById}, i.e. with the clubId {@link JwtAuthenticationFilter} took from the same
 * token, never from the request; a token whose {@code sub} names a user of another club finds
 * nobody.
 *
 * <p><b>Called once per request, before any retry loop</b> — it guards who may make the change, not
 * the target's state, so a conflict on the target doesn't make it worth re-reading the caller. It's
 * a read-then-act check: two admins deactivating each other in the very same instant could both
 * pass it. That's accepted — both acts are deliberate, by admins, and concurrent within
 * milliseconds.
 */
@Component
class ActiveCallerCheck {

  private final UserRepository userRepository;

  ActiveCallerCheck(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * The caller's current record, in one club-scoped read.
   *
   * @throws CurrentUserUnavailableException if the caller doesn't exist in their token's club, or
   *     is deactivated — the generic 401 a request without a token gets, so it doesn't reveal which
   */
  User requireActive(AuthenticatedUser caller) {
    return userRepository
        .findById(caller.userId())
        .filter(User::isActive)
        .orElseThrow(CurrentUserUnavailableException::new);
  }
}
