package com.squadpulse.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /auth/login}. The email is capped at 254 characters — the longest a valid
 * address can be (RFC 5321) — since it becomes part of a Redis key (see {@link
 * LoginThrottleService}).
 */
record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank String password) {}
