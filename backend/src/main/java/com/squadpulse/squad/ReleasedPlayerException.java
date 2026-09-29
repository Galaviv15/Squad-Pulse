package com.squadpulse.squad;

import com.squadpulse.common.ConflictException;

/**
 * An edit to a released player ({@code active == false}). A released player is read-only history
 * until they're re-activated.
 */
class ReleasedPlayerException extends ConflictException {

  ReleasedPlayerException() {
    super("This player has been released; re-activate them before editing");
  }
}
