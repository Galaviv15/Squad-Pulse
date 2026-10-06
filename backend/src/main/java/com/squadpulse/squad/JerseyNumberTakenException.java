package com.squadpulse.squad;

import com.squadpulse.common.ConflictException;

/**
 * Another active player in the club already wears this number — detected by the {@value
 * Player#JERSEY_NUMBER_INDEX} index rejecting the write, not by a check beforehand (see {@link
 * PlayerService}).
 */
class JerseyNumberTakenException extends ConflictException {

  static final String CODE = "JERSEY_NUMBER_TAKEN";

  JerseyNumberTakenException(int jerseyNumber) {
    super(CODE, "Jersey number " + jerseyNumber + " is already taken by another active player");
  }
}
