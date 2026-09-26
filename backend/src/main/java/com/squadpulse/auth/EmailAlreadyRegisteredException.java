package com.squadpulse.auth;

import com.squadpulse.common.ConflictException;

/**
 * An invite used an email that already belongs to a user — in any club, since email is unique
 * system-wide (see {@link User}).
 */
class EmailAlreadyRegisteredException extends ConflictException {

  EmailAlreadyRegisteredException() {
    super("A user with this email already exists");
  }
}
