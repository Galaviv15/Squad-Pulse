package com.squadpulse.auth;

import com.squadpulse.common.UnauthorizedException;

/**
 * The refresh token is missing, unknown, expired, revoked or was already rotated away. One generic
 * message for all of them — the client's only move is to log in again either way.
 */
class InvalidRefreshTokenException extends UnauthorizedException {

  InvalidRefreshTokenException() {
    super("Invalid or expired refresh token");
  }
}
