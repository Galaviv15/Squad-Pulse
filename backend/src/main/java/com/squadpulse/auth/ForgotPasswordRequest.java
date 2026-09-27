package com.squadpulse.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /auth/forgot-password}. The email is capped at 254 characters, like in {@link
 * LoginRequest}, since it becomes part of a Redis key (see {@link PasswordResetCodeService}).
 */
record ForgotPasswordRequest(@NotBlank @Size(max = 254) String email) {}
