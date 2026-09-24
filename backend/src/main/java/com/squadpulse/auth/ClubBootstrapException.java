package com.squadpulse.auth;

/**
 * Club bootstrap was refused before anything was written — a missing or wrong owner secret, or
 * invalid input. Messages never contain the owner secret or the password.
 */
class ClubBootstrapException extends RuntimeException {

  ClubBootstrapException(String message) {
    super(message);
  }
}
