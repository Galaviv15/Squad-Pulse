package com.squadpulse.squad;

import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertEvent;

/**
 * A deterministic stand-in for a concurrent edit: runs a write right after the next {@link Player}
 * is loaded from MongoDB — i.e. between an update's load and its save — on the same thread, with no
 * sleeps or timing. The {@link Player} counterpart of {@code auth.UserLoadHook}.
 */
class PlayerLoadHook extends AbstractMongoEventListener<Player> {

  private Runnable write;
  private boolean running;

  void onNextLoad(Runnable write) {
    this.write = write;
  }

  void disarm() {
    write = null;
  }

  @Override
  public void onAfterConvert(AfterConvertEvent<Player> event) {
    if (write == null || running) {
      return;
    }
    Runnable toRun = write;
    write = null;
    running = true;
    try {
      toRun.run();
    } finally {
      running = false;
    }
  }
}
