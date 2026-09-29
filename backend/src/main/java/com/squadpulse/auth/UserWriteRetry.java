package com.squadpulse.auth;

import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Bounded retry for a load-modify-save of a {@link User} that lost an optimistic-locking race (see
 * {@link User#getVersion()}, KAN-24).
 *
 * <p>The attempt must <b>reload</b> the user and re-apply its change every time — re-saving the
 * stale copy would just fail again — and re-check anything that decides whether the change is still
 * allowed (e.g. {@code active}), since the concurrent write may have changed exactly that.
 *
 * <p>Only for an absolute change that a reload can't turn into a wrong one: "set X to this value",
 * on fields the concurrent writer didn't touch. A change based on what a person saw on screen (a
 * multi-field edit form) should instead let the conflict become a 409 — see {@code
 * common.GlobalExceptionHandler}.
 */
final class UserWriteRetry {

  static final int MAX_ATTEMPTS = 3;

  private UserWriteRetry() {}

  /**
   * Runs {@code attempt} until it completes without an {@link OptimisticLockingFailureException},
   * at most {@link #MAX_ATTEMPTS} times; then rethrows the last one. Any other exception ends it
   * immediately.
   */
  static <T> T withRetry(Supplier<T> attempt) {
    for (int attemptNumber = 1; ; attemptNumber++) {
      try {
        return attempt.get();
      } catch (OptimisticLockingFailureException e) {
        if (attemptNumber >= MAX_ATTEMPTS) {
          throw e;
        }
      }
    }
  }
}
