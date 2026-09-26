package com.squadpulse.auth;

import com.squadpulse.common.AdultAge;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Body of {@code POST /auth/users/invite}. Deliberately has no {@code clubId}: the new user always
 * joins the inviting admin's own club (and any {@code clubId} a client sends is ignored).
 *
 * @param dateOfBirth optional, like on {@link User}; ISO format ({@code yyyy-MM-dd})
 */
record InviteUserRequest(
    @NotBlank @Email String email,
    @NotBlank String fullName,
    @NotNull Title title,
    @NotNull PermissionLevel permissionLevel,
    @AdultAge LocalDate dateOfBirth) {}
