package com.squadpulse.auth;

import java.time.LocalDate;

/**
 * A user as returned by the API — never including the password hash. {@code hasPhoto} says whether
 * {@code GET /users/{id}/photo} has an image (see {@link StaffPhotoService}); the caller computes
 * it, so building a response never queries storage by itself.
 */
record UserResponse(
    String id,
    String email,
    String fullName,
    Title title,
    PermissionLevel permissionLevel,
    LocalDate dateOfBirth,
    boolean active,
    boolean hasPhoto) {

  static UserResponse from(User user, boolean hasPhoto) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getFullName(),
        user.getTitle(),
        user.getPermissionLevel(),
        user.getDateOfBirth(),
        user.isActive(),
        hasPhoto);
  }
}
