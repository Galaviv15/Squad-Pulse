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
 * it's kept for parity with {@link UserResponse}.
 */
record CurrentUserResponse(
    String id,
    String email,
    String fullName,
    Title title,
    PermissionLevel permissionLevel,
    LocalDate dateOfBirth,
    boolean active,
    ClubSummary club) {

  /** The caller's club, as much of it as the app header needs. */
  record ClubSummary(String id, String name) {}

  static CurrentUserResponse from(User user, PermissionLevel effectiveLevel, Club club) {
    return new CurrentUserResponse(
        user.getId(),
        user.getEmail(),
        user.getFullName(),
        user.getTitle(),
        effectiveLevel,
        user.getDateOfBirth(),
        user.isActive(),
        new ClubSummary(club.getId(), club.getName()));
  }
}
