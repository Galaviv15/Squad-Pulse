package com.squadpulse.auth;

import java.time.LocalDate;

/**
 * The caller of {@code GET /auth/users/me}: the same fields, JSON names and order as {@link
 * UserResponse}, plus their {@link #club}, so a client can reuse one user type. Never any password,
 * version or session field, and no top-level {@code clubId} — only {@code club.id}.
 *
 * <p>{@link #permissionLevel} is the caller's <b>effective</b> level, taken from their access token
 * — what the server enforces on this request — not the stored one; every other field comes from the
 * database (see {@link CurrentUserService}).
 *
 * <p>{@link #active} is always {@code true} in a response (an inactive caller gets a 401 instead);
 * it's kept for parity with {@link UserResponse}. So is {@link #activated}, always {@code true}
 * too: a user who hasn't set a password can't log in, so can't hold an access token. It's still
 * derived from the stored user, like in {@link UserResponse}, not hard-coded. {@link #hasPhoto}
 * says whether {@code GET /users/me/photo} has an image, so the client can skip that request when
 * it hasn't. {@link #club} is the same {@link ClubResponse} that {@code GET /clubs/me} returns.
 */
record CurrentUserResponse(
    String id,
    String email,
    String fullName,
    Title title,
    PermissionLevel permissionLevel,
    LocalDate dateOfBirth,
    boolean active,
    boolean hasPhoto,
    boolean activated,
    ClubResponse club) {

  static CurrentUserResponse from(
      User user, PermissionLevel effectiveLevel, boolean hasPhoto, Club club, boolean clubHasLogo) {
    return new CurrentUserResponse(
        user.getId(),
        user.getEmail(),
        user.getFullName(),
        user.getTitle(),
        effectiveLevel,
        user.getDateOfBirth(),
        user.isActive(),
        hasPhoto,
        user.getPasswordHash() != null,
        ClubResponse.from(club, clubHasLogo));
  }
}
