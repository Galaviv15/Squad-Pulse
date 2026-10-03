package com.squadpulse.auth;

import org.springframework.stereotype.Service;

/**
 * Who the caller is, for the frontend's header and for hiding actions they can't perform (KAN-34).
 * Read-only: never writes the user, so it can't conflict with a concurrent write.
 *
 * <p><b>Club isolation.</b> The user is loaded through the club-scoped {@link
 * UserRepository#findById}, with the clubId {@link JwtAuthenticationFilter} took from the access
 * token; the club by the same token clubId. Nothing is ever read from the request itself. A token
 * whose {@code sub} names a user of another club therefore finds no user — a 401, exactly like a
 * deleted user.
 *
 * <p><b>Effective permission level.</b> The response's level is the one in the caller's access
 * token, i.e. what every endpoint enforces right now; every other field is read from the database.
 * After an admin changes the caller's level, the new one shows up here only after the caller's next
 * {@code /auth/refresh} — the same moment it takes effect everywhere else (see {@link
 * UserPermissionLevelService}).
 *
 * <p><b>401 for a deactivated or deleted user</b> — a deliberate exception to "an access token
 * keeps working after deactivation until it expires" (docs/spec.md section 10): the frontend calls
 * this on every app load, so it gets a 401, its {@code /auth/refresh} fails too, and the user lands
 * on the login screen. The message is the generic one a request without a token gets, so it doesn't
 * reveal which case applied. {@code sessionsInvalidatedAt} and {@code passwordHash} are
 * deliberately not checked: access tokens stay valid after a password reset (docs/spec.md section
 * 10), and {@link AuthenticatedUser} doesn't carry the token's issue time to compare against.
 */
@Service
class CurrentUserService {

  private final UserRepository userRepository;
  private final ClubRepository clubRepository;

  CurrentUserService(UserRepository userRepository, ClubRepository clubRepository) {
    this.userRepository = userRepository;
    this.clubRepository = clubRepository;
  }

  /**
   * @throws CurrentUserUnavailableException if the caller doesn't exist in their token's club, or
   *     is deactivated
   * @throws IllegalStateException if the caller's club doesn't exist — a data-integrity bug, so a
   *     generic 500
   */
  CurrentUserResponse currentUser(AuthenticatedUser caller) {
    User user =
        userRepository
            .findById(caller.userId())
            .filter(User::isActive)
            .orElseThrow(CurrentUserUnavailableException::new);
    Club club =
        clubRepository
            .findById(caller.clubId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Club " + caller.clubId() + " of user " + user.getId() + " not found"));
    return CurrentUserResponse.from(user, caller.permissionLevel(), club);
  }
}
