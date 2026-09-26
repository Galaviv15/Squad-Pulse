package com.squadpulse.auth;

import java.time.LocalDate;

/** A user as returned by the API — never including the password hash. */
record UserResponse(
    String id,
    String email,
    String fullName,
    Title title,
    PermissionLevel permissionLevel,
    LocalDate dateOfBirth,
    boolean active) {

  static UserResponse from(User user) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getFullName(),
        user.getTitle(),
        user.getPermissionLevel(),
        user.getDateOfBirth(),
        user.isActive());
  }
}
