package com.squadpulse.squad;

import com.squadpulse.common.BadRequestException;

/**
 * A player-list age filter whose {@code minAge} is above its {@code maxAge} — blamed on {@code
 * minAge}, so it's reported like the other query-parameter errors.
 */
class InvalidAgeRangeException extends BadRequestException {

  InvalidAgeRangeException() {
    super("minAge", "must be less than or equal to maxAge");
  }
}
