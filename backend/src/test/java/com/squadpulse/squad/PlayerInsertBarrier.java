package com.squadpulse.squad;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveEvent;

/**
 * Holds each {@link Player} write at the last moment before it reaches MongoDB until {@code
 * parties} writes are all there, then releases them together. So in a race test every writer has
 * already done everything it does before writing — none of them can have seen another's write — and
 * the outcome depends only on what the database allows, not on timing.
 */
class PlayerInsertBarrier extends AbstractMongoEventListener<Player> {

  private volatile CyclicBarrier barrier;

  void arm(int parties) {
    barrier = new CyclicBarrier(parties);
  }

  void disarm() {
    barrier = null;
  }

  @Override
  public void onBeforeSave(BeforeSaveEvent<Player> event) {
    CyclicBarrier current = barrier;
    if (current == null) {
      return;
    }
    try {
      // A timeout rather than a sleep: only reached if a writer never arrives, i.e. a broken test.
      current.await(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("Not every writer reached the barrier", e);
    }
  }
}
