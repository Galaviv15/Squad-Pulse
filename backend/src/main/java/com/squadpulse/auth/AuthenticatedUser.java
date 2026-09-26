package com.squadpulse.auth;

/**
 * The caller of an authenticated request, as carried by its access token (see {@link JwtService}) —
 * the Spring Security principal set by {@link JwtAuthenticationFilter}.
 *
 * <p>Read from the token, not the database, so it can lag behind a change to the user by up to one
 * access-token lifetime (see {@link TokenProperties#accessTtl()}).
 */
public record AuthenticatedUser(String userId, String clubId, PermissionLevel permissionLevel) {}
