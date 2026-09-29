package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squadpulse.common.NotFoundException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

/** Only an {@link OptimisticLockingFailureException} is retried; anything else ends it at once. */
class UserWriteRetryTest {

  private final AtomicInteger attempts = new AtomicInteger();

  @Test
  void returnsTheFirstSuccessfulResult() {
    assertThat(UserWriteRetry.withRetry(countingAttempt(() -> "done"))).isEqualTo("done");
    assertThat(attempts).hasValue(1);
  }

  @Test
  void retriesAnOptimisticLockingFailureUntilItSucceeds() {
    String result =
        UserWriteRetry.withRetry(
            countingAttempt(
                () -> {
                  if (attempts.get() < UserWriteRetry.MAX_ATTEMPTS) {
                    throw new OptimisticLockingFailureException("conflict");
                  }
                  return "done";
                }));

    assertThat(result).isEqualTo("done");
    assertThat(attempts).hasValue(UserWriteRetry.MAX_ATTEMPTS);
  }

  @Test
  void rethrowsTheLastOptimisticLockingFailureAfterMaxAttempts() {
    assertThatThrownBy(
            () ->
                UserWriteRetry.withRetry(
                    countingAttempt(
                        () -> {
                          throw new OptimisticLockingFailureException("attempt " + attempts.get());
                        })))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .hasMessage("attempt " + UserWriteRetry.MAX_ATTEMPTS);
    assertThat(attempts).hasValue(UserWriteRetry.MAX_ATTEMPTS);
  }

  @Test
  void anInvalidResetCodePropagatesWithoutARetry() {
    assertNotRetried(new InvalidResetCodeException());
  }

  @Test
  void aNotFoundPropagatesWithoutARetry() {
    assertNotRetried(new NotFoundException("User not found"));
  }

  /** Neither its parent class nor another data-access failure counts as a version conflict. */
  @Test
  void otherDataAccessFailuresPropagateWithoutARetry() {
    assertNotRetried(new ConcurrencyFailureException("not a version conflict"));
    assertNotRetried(new DuplicateKeyException("duplicate"));
  }

  private void assertNotRetried(RuntimeException exception) {
    attempts.set(0);
    assertThatThrownBy(
            () ->
                UserWriteRetry.withRetry(
                    countingAttempt(
                        () -> {
                          throw exception;
                        })))
        .isSameAs(exception);
    assertThat(attempts).hasValue(1);
  }

  private <T> Supplier<T> countingAttempt(Supplier<T> attempt) {
    return () -> {
      attempts.incrementAndGet();
      return attempt.get();
    };
  }
}
