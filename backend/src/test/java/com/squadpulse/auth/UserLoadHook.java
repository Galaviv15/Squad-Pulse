package com.squadpulse.auth;

import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertEvent;

/**
 * A deterministic stand-in for a concurrent request (KAN-24 tests): runs a write right after a
 * {@link User} has been loaded from MongoDB — i.e. between a flow's load and its save — on the same
 * thread, with no sleeps or timing.
 *
 * <p>Fires on every {@link User} load, including the club-ownership look-up the club-scoped
 * repository does inside {@code save}. Loads made by the write itself don't re-trigger it. The
 * {@code ...Of(userId, ...)} variants fire only on loads of that one user — needed wherever the
 * flow under test reads another user first, e.g. the caller re-check of the user-management writes
 * (see {@link ActiveCallerCheck}), whose load would otherwise trigger the write too early.
 */
class UserLoadHook extends AbstractMongoEventListener<User> {

  private Runnable write;
  private String userId;
  private boolean everyLoad;
  private boolean running;
  private int fired;

  /** Runs {@code write} after the next load of a user only. */
  void onNextLoad(Runnable write) {
    arm(null, write, false);
  }

  /** Runs {@code write} after the next load of the user with this id only. */
  void onNextLoadOf(String userId, Runnable write) {
    arm(userId, write, false);
  }

  /** Runs {@code write} after every load of a user, until {@link #disarm()}. */
  void onEveryLoad(Runnable write) {
    arm(null, write, true);
  }

  /** Runs {@code write} after every load of the user with this id, until {@link #disarm()}. */
  void onEveryLoadOf(String userId, Runnable write) {
    arm(userId, write, true);
  }

  void disarm() {
    write = null;
    fired = 0;
  }

  int timesFired() {
    return fired;
  }

  private void arm(String userId, Runnable write, boolean everyLoad) {
    this.userId = userId;
    this.write = write;
    this.everyLoad = everyLoad;
    this.fired = 0;
  }

  @Override
  public void onAfterConvert(AfterConvertEvent<User> event) {
    if (write == null || running) {
      return;
    }
    if (userId != null && !userId.equals(event.getSource().getId())) {
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
