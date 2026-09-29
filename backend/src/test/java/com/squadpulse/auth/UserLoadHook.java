package com.squadpulse.auth;

import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertEvent;

/**
 * A deterministic stand-in for a concurrent request (KAN-24 tests): runs a write right after a
 * {@link User} has been loaded from MongoDB — i.e. between a flow's load and its save — on the same
 * thread, with no sleeps or timing.
 *
 * <p>Fires on every {@link User} load, including the club-ownership look-up the club-scoped
 * repository does inside {@code save}. Loads made by the write itself don't re-trigger it.
 */
class UserLoadHook extends AbstractMongoEventListener<User> {

  private Runnable write;
  private boolean everyLoad;
  private boolean running;
  private int fired;

  /** Runs {@code write} after the next load of a user only. */
  void onNextLoad(Runnable write) {
    arm(write, false);
  }

  /** Runs {@code write} after every load of a user, until {@link #disarm()}. */
  void onEveryLoad(Runnable write) {
    arm(write, true);
  }

  void disarm() {
    write = null;
    fired = 0;
  }

  int timesFired() {
    return fired;
  }

  private void arm(Runnable write, boolean everyLoad) {
    this.write = write;
    this.everyLoad = everyLoad;
    this.fired = 0;
  }

  @Override
  public void onAfterConvert(AfterConvertEvent<User> event) {
    if (write == null || running) {
      return;
    }
    Runnable toRun = write;
    if (!everyLoad) {
      write = null;
    }
    running = true;
    try {
      fired++;
      toRun.run();
    } finally {
      running = false;
    }
  }
}
