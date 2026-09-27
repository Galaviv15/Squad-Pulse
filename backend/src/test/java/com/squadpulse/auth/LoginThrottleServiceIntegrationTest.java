package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.redis.testcontainers.RedisContainer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the login-attempt counting (see {@link LoginThrottleService}) against a real Redis: the
 * Lua script, atomicity under concurrency, per-(email, IP) isolation, reset, and the window
 * expiring. No Spring context — just the service on top of a real connection.
 */
@Testcontainers
class LoginThrottleServiceIntegrationTest {

  private static final int MAX_ATTEMPTS = 5;
  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final String EMAIL = "coach@example.com";
  private static final String IP = "203.0.113.7";

  @Container
  static final RedisContainer REDIS_CONTAINER =
      new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;

  private final LoginThrottleService service = service(WINDOW);

  @BeforeAll
  static void connect() {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(
                REDIS_CONTAINER.getRedisHost(), REDIS_CONTAINER.getRedisPort()));
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();
    redis = new StringRedisTemplate(connectionFactory);
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @AfterEach
  void flush() {
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Test
  void allowsAttemptsUpToTheLimit() {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      assertThatCode(() -> service.recordAttempt(EMAIL, IP)).doesNotThrowAnyException();
    }
  }

  @Test
  void refusesTheAttemptAfterTheLimitWithTheRemainingWindow() {
    attempt(service, EMAIL, IP, MAX_ATTEMPTS);

    assertThatThrownBy(() -> service.recordAttempt(EMAIL, IP))
        .isInstanceOfSatisfying(
            LoginThrottledException.class,
            e ->
                assertThat(e.getRetryAfter())
                    .isPositive()
                    .isLessThanOrEqualTo(WINDOW)
                    .isGreaterThan(WINDOW.minusMinutes(1)));
  }

  /** The whole point of counting before checking: a burst can't slip past the limit together. */
  @Test
  void concurrentAttemptsAreCountedOneByOne() throws Exception {
    int threads = 20;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    service.recordAttempt(EMAIL, IP);
                    return true;
                  } catch (LoginThrottledException e) {
                    return false;
                  }
                }));
      }
      start.countDown();

      int allowed = 0;
      for (Future<Boolean> result : results) {
        allowed += result.get() ? 1 : 0;
      }
      assertThat(allowed).isEqualTo(MAX_ATTEMPTS);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void theWindowRunsFromTheFirstAttemptAndIsNotExtendedByLaterOnes() {
    service.recordAttempt(EMAIL, IP);
    redis.expire(LoginThrottleService.key(EMAIL, IP), Duration.ofSeconds(60));

    attempt(service, EMAIL, IP, MAX_ATTEMPTS - 1);

    assertThat(redis.getExpire(LoginThrottleService.key(EMAIL, IP))).isBetween(1L, 60L);
    assertThat(redis.opsForValue().get(LoginThrottleService.key(EMAIL, IP))).isEqualTo("5");
  }

  @Test
  void resetClearsTheCount() {
    attempt(service, EMAIL, IP, MAX_ATTEMPTS);

    service.reset(EMAIL, IP);

    attempt(service, EMAIL, IP, MAX_ATTEMPTS);
  }

  @Test
  void theSameEmailFromAnotherIpHasItsOwnCount() {
    attempt(service, EMAIL, IP, MAX_ATTEMPTS);

    assertThatCode(() -> service.recordAttempt(EMAIL, "198.51.100.23")).doesNotThrowAnyException();
  }

  @Test
  void anotherEmailFromTheSameIpHasItsOwnCount() {
    attempt(service, EMAIL, IP, MAX_ATTEMPTS);

    assertThatCode(() -> service.recordAttempt("other@example.com", IP)).doesNotThrowAnyException();
  }

  @Test
  void aKeyThatLostItsTtlGetsANewWindowInsteadOfBlockingForever() {
    attempt(service, EMAIL, IP, MAX_ATTEMPTS);
    redis.persist(LoginThrottleService.key(EMAIL, IP));

    assertThatThrownBy(() -> service.recordAttempt(EMAIL, IP))
        .isInstanceOf(LoginThrottledException.class);

    assertThat(redis.getExpire(LoginThrottleService.key(EMAIL, IP))).isPositive();
  }

  @Test
  void thePairIsAllowedAgainOnceTheWindowExpires() {
    LoginThrottleService shortWindow = service(Duration.ofSeconds(1));
    attempt(shortWindow, EMAIL, IP, MAX_ATTEMPTS);
    assertThatThrownBy(() -> shortWindow.recordAttempt(EMAIL, IP))
        .isInstanceOf(LoginThrottledException.class);

    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> redis.keys(LoginThrottleService.KEY_PREFIX + "*").isEmpty());

    assertThatCode(() -> shortWindow.recordAttempt(EMAIL, IP)).doesNotThrowAnyException();
  }

  /** Makes {@code times} attempts, all of which must be allowed. */
  private static void attempt(LoginThrottleService service, String email, String ip, int times) {
    for (int i = 0; i < times; i++) {
      service.recordAttempt(email, ip);
    }
  }

  private static LoginThrottleService service(Duration window) {
    return new LoginThrottleService(redis, new LoginThrottleProperties(MAX_ATTEMPTS, window));
  }
}
