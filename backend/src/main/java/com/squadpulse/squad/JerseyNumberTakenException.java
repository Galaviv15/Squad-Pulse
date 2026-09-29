package com.squadpulse.squad;

import com.squadpulse.common.ConflictException;

/**
 * Another active player in the club already wears this number — detected by the {@value
 * Player#JERSEY_NUMBER_INDEX} index rejecting the write, not by a check beforehand (see {@link
 * PlayerService}).
 */
class JerseyNumberTakenException extends ConflictException {

  JerseyNumberTakenException(int jerseyNumber) {
    super("Jersey number " + jerseyNumber + " is already taken by another active player");
  }
}
