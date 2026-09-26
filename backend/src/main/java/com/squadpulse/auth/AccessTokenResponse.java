package com.squadpulse.auth;

/**
 * Body of a successful {@code POST /auth/login} or {@code /auth/refresh}. The refresh token is
 * never part of it — it only travels in the HttpOnly cookie.
 *
 * @param expiresIn seconds until the access token expires
 */
record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {}
