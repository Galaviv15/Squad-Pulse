package com.squadpulse.auth;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /auth/login}. */
record LoginRequest(@NotBlank String email, @NotBlank String password) {}
