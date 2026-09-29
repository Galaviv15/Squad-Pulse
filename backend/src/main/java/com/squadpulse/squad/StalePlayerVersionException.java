package com.squadpulse.squad;

import com.squadpulse.common.ConflictException;

/**
 * An edit based on an out-of-date copy: someone saved the player after the client loaded it —
 * either before this edit was checked (the {@code version} the client sent isn't the stored one) or
 * in the moment between that check and the save. The client must reload and re-apply its changes on
 * top of the newer state, never overwrite it blindly.
 */
class StalePlayerVersionException extends ConflictException {

  StalePlayerVersionException() {
    super(
        "This player was changed by someone else since you loaded it; reload it and apply your"
            + " changes again");
  }
}
