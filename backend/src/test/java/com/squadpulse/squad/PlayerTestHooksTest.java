package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveEvent;
import org.springframework.stereotype.Component;

/**
 * The race-test hooks are inert unless a test arms them, and never picked up by component scanning
 * — so a context that doesn't {@code @Import} them (every test but {@link
 * PlayerApiIntegrationTest}) can't be affected by them.
 */
class PlayerTestHooksTest {

  @Test
  void neitherHookIsAComponent() {
    assertThat(PlayerLoadHook.class.isAnnotationPresent(Component.class)).isFalse();
    assertThat(PlayerInsertBarrier.class.isAnnotationPresent(Component.class)).isFalse();
  }

  @Test
  void anUnarmedLoadHookDoesNothing() {
    PlayerLoadHook hook = new PlayerLoadHook();

    assertThatCode(() -> hook.onAfterConvert(afterConvert())).doesNotThrowAnyException();
  }

  /** Runs once, then disarms itself: the next load does nothing. */
  @Test
  void anArmedLoadHookRunsItsWriteOnce() {
    PlayerLoadHook hook = new PlayerLoadHook();
    int[] runs = {0};
    hook.onNextLoad(() -> runs[0]++);

    hook.onAfterConvert(afterConvert());
    hook.onAfterConvert(afterConvert());

    assertThat(runs[0]).isEqualTo(1);
  }

  /** Unarmed, a write passes straight through instead of waiting for a second writer. */
  @Test
  void anUnarmedBarrierLetsAWriteThroughImmediately() {
    PlayerInsertBarrier barrier = new PlayerInsertBarrier();

    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> barrier.onBeforeSave(beforeSave()));
  }

  @Test
  void aDisarmedBarrierLetsAWriteThroughImmediately() {
    PlayerInsertBarrier barrier = new PlayerInsertBarrier();
    barrier.arm(2);
    barrier.disarm();

    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> barrier.onBeforeSave(beforeSave()));
  }

  private static AfterConvertEvent<Player> afterConvert() {
    return new AfterConvertEvent<>(new Document(), new Player(), "players");
  }

  private static BeforeSaveEvent<Player> beforeSave() {
    return new BeforeSaveEvent<>(new Player(), new Document(), "players");
  }
}
