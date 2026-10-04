package com.squadpulse.auth;

import java.time.LocalDate;

/**
 * A user as returned by the API — never including the password hash. {@code hasPhoto} says whether
 * {@code GET /users/{id}/photo} has an image (see {@link StaffPhotoService}); the caller computes
 * it, so building a response never queries storage by itself.
 *
 * <p>{@code activated} says whether the user has set a password ({@code passwordHash != null}):
 * {@code false} for an invited user who hasn't used their activation code yet, so can't log in (see
 * {@link UserInvitationService}). It's independent of {@code active}, the Club Manager's
 * deactivation switch. Derived from the {@link User} alone, with no query; it's the only thing
 * about the password a response ever reveals.
 */
record UserResponse(
    String id,
    String email,
    String fullName,
    Title title,
    PermissionLevel permissionLevel,
    LocalDate dateOfBirth,
    boolean active,
    boolean hasPhoto,
    boolean activated) {

  static UserResponse from(User user, boolean hasPhoto) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getFullName(),
        user.getTitle(),
        user.getPermissionLevel(),
        user.getDateOfBirth(),
        user.isActive(),
        hasPhoto,
        user.getPasswordHash() != null);
  }
}
