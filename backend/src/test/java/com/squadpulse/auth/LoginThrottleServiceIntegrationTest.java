package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.redis.testcontainers.RedisContainer;
import java.time.Duration;
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
 * Proves the failed-login counting (see {@link LoginThrottleService}) against a real Redis: the Lua
 * scripts, per-(email, IP) isolation, reset, and the window expiring. No Spring context — just the
 * service on top of a real connection.
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
  void allowsAttemptsUnderTheLimit() {
    fail(service, EMAIL, IP, MAX_ATTEMPTS - 1);

    assertThatCode(() -> service.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
  }

  @Test
  void blocksOnceTheLimitIsReachedUntilTheWindowEnds() {
    fail(service, EMAIL, IP, MAX_ATTEMPTS);

    assertThatThrownBy(() -> service.checkAllowed(EMAIL, IP))
        .isInstanceOfSatisfying(
            LoginThrottledException.class,
            e ->
                assertThat(e.getRetryAfter())
                    .isPositive()
                    .isLessThanOrEqualTo(WINDOW)
                    .isGreaterThan(WINDOW.minusMinutes(1)));
  }

  @Test
  void theWindowRunsFromTheFirstFailureAndIsNotExtendedByLaterOnes() {
    service.recordFailure(EMAIL, IP);
    redis.expire(LoginThrottleService.key(EMAIL, IP), Duration.ofSeconds(60));

    fail(service, EMAIL, IP, MAX_ATTEMPTS - 1);

    assertThat(redis.getExpire(LoginThrottleService.key(EMAIL, IP))).isBetween(1L, 60L);
    assertThat(redis.opsForValue().get(LoginThrottleService.key(EMAIL, IP))).isEqualTo("5");
  }

  @Test
  void resetClearsTheCount() {
    fail(service, EMAIL, IP, MAX_ATTEMPTS - 1);

    service.reset(EMAIL, IP);
    fail(service, EMAIL, IP, MAX_ATTEMPTS - 1);

    assertThatCode(() -> service.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
  }

  @Test
  void theSameEmailFromAnotherIpHasItsOwnCount() {
    fail(service, EMAIL, IP, MAX_ATTEMPTS);

    assertThatCode(() -> service.checkAllowed(EMAIL, "198.51.100.23")).doesNotThrowAnyException();
  }

  @Test
  void anotherEmailFromTheSameIpHasItsOwnCount() {
    fail(service, EMAIL, IP, MAX_ATTEMPTS);

    assertThatCode(() -> service.checkAllowed("other@example.com", IP)).doesNotThrowAnyException();
  }

  @Test
  void aKeyThatLostItsTtlGetsANewWindowInsteadOfBlockingForever() {
    fail(service, EMAIL, IP, MAX_ATTEMPTS);
    redis.persist(LoginThrottleService.key(EMAIL, IP));

    assertThatThrownBy(() -> service.checkAllowed(EMAIL, IP))
        .isInstanceOf(LoginThrottledException.class);

    assertThat(redis.getExpire(LoginThrottleService.key(EMAIL, IP))).isPositive();
  }

  @Test
  void thePairIsAllowedAgainOnceTheWindowExpires() {
    LoginThrottleService shortWindow = service(Duration.ofSeconds(1));
    fail(shortWindow, EMAIL, IP, MAX_ATTEMPTS);
    assertThatThrownBy(() -> shortWindow.checkAllowed(EMAIL, IP))
        .isInstanceOf(LoginThrottledException.class);

    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> redis.keys(LoginThrottleService.KEY_PREFIX + "*").isEmpty());

    assertThatCode(() -> shortWindow.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
  }

  private static void fail(LoginThrottleService service, String email, String ip, int times) {
    for (int i = 0; i < times; i++) {
      service.recordFailure(email, ip);
    }
  }

  private static LoginThrottleService service(Duration window) {
    return new LoginThrottleService(redis, new LoginThrottleProperties(MAX_ATTEMPTS, window));
  }
}
