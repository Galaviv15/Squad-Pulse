package com.squadpulse.match;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestComponent;

/**
 * Test seam: wraps the application's {@link LeagueStandingsSnapshotRepository} so its next {@code
 * save} calls throw a chosen exception, as if each lost a write race. {@code @Import} it; {@link
 * #disarm()} after each test.
 */
@TestComponent
class StandingsSaveFailures implements BeanPostProcessor {

  private final AtomicInteger remaining = new AtomicInteger();
  private volatile Supplier<RuntimeException> failure;

  /** The next {@code times} saves throw what {@code failure} supplies. */
  void failNextSaves(int times, Supplier<RuntimeException> failure) {
    this.failure = failure;
    remaining.set(times);
  }

  void disarm() {
    remaining.set(0);
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (!(bean instanceof LeagueStandingsSnapshotRepository real)) {
      return bean;
    }
    return Proxy.newProxyInstance(
        LeagueStandingsSnapshotRepository.class.getClassLoader(),
        new Class<?>[] {LeagueStandingsSnapshotRepository.class},
        (proxy, method, args) -> {
          if (method.getName().equals("save") && remaining.getAndDecrement() > 0) {
            throw failure.get();
          }
          try {
            return method.invoke(real, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        });
  }
}
