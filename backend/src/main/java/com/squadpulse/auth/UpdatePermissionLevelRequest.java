package com.squadpulse.auth;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /auth/users/{id}/permission-level}. Carries only the new level: any other
 * field a client sends ({@code clubId}, {@code title}, {@code email}, ...) is ignored.
 */
record UpdatePermissionLevelRequest(@NotNull PermissionLevel permissionLevel) {}
