package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.redis.testcontainers.RedisContainer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
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
 * Proves the activation / reset code scheme (see {@link PasswordResetCodeService}) against a real
 * Redis: single use, the attempt limit, re-issuing, expiry, what's stored, atomicity under
 * concurrency, and the per-email request limit. No Spring context — just the service on top of a
 * real connection.
 */
@Testcontainers
class PasswordResetCodeServiceIntegrationTest {

  private static final int MAX_ATTEMPTS = 5;
  private static final int MAX_REQUESTS = 5;
  private static final String EMAIL = "coach@example.com";
  private static final String CODE_KEY = PasswordResetCodeService.CODE_KEY_PREFIX + EMAIL;

  @Container
  static final RedisContainer REDIS_CONTAINER =
      new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;

  private final PasswordResetCodeService service =
      service(Duration.ofMinutes(15), Duration.ofHours(24));

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
  void issuesASixDigitCode() {
    assertThat(service.issue(EMAIL)).matches("\\d{6}");
  }

  @Test
  void aCodeWorksExactlyOnce() {
    String code = service.issue(EMAIL);

    assertThat(service.verify(EMAIL, code)).isTrue();
    assertThat(service.verify(EMAIL, code)).isFalse();
    assertThat(redis.hasKey(CODE_KEY)).isFalse();
  }

  @Test
  void aCodeIsOnlyValidForItsOwnEmail() {
    String code = service.issue(EMAIL);

    assertThat(service.verify("other@example.com", code)).isFalse();
    assertThat(service.verify(EMAIL, code)).isTrue();
  }

  @Test
  void noCodeAtAllIsSimplyInvalid() {
    assertThat(service.verify(EMAIL, "123456")).isFalse();
    assertThat(redis.hasKey(CODE_KEY)).isFalse();
  }

  @Test
  void wrongGuessesAreCountedAndTheLastAllowedOneBurnsTheCode() {
    String code = service.issue(EMAIL);
    String wrong = wrongCode(code);

    for (int i = 1; i < MAX_ATTEMPTS; i++) {
      assertThat(service.verify(EMAIL, wrong)).isFalse();
      assertThat(redis.opsForHash().get(CODE_KEY, PasswordResetCodeService.ATTEMPTS_FIELD))
          .isEqualTo(String.valueOf(i));
    }
    assertThat(service.verify(EMAIL, wrong)).isFalse();

    assertThat(redis.hasKey(CODE_KEY)).isFalse();
    assertThat(service.verify(EMAIL, code)).isFalse();
  }

  @Test
  void theRightCodeStillWorksBeforeTheLimitIsReached() {
    String code = service.issue(EMAIL);
    for (int i = 1; i < MAX_ATTEMPTS; i++) {
      service.verify(EMAIL, wrongCode(code));
    }

    assertThat(service.verify(EMAIL, code)).isTrue();
  }

  @Test
  void reissuingReplacesTheOldCodeAndResetsTheAttempts() {
    String first = service.issue(EMAIL);
    for (int i = 1; i < MAX_ATTEMPTS; i++) {
      service.verify(EMAIL, wrongCode(first));
    }

    String second = service.issue(EMAIL);

    assertThat(redis.opsForHash().get(CODE_KEY, PasswordResetCodeService.ATTEMPTS_FIELD))
        .isEqualTo("0");
    if (!second.equals(first)) { // 1 in 10^6 that they coincide
      assertThat(service.verify(EMAIL, first)).isFalse();
    }
    // A fresh allowance: the old code's wrong guesses don't count against the new one.
    assertThat(service.verify(EMAIL, second)).isTrue();
  }

  @Test
  void aCodeExpires() {
    PasswordResetCodeService shortLived = service(Duration.ofSeconds(1), Duration.ofHours(24));
    String code = shortLived.issue(EMAIL);

    await().atMost(Duration.ofSeconds(5)).until(() -> !redis.hasKey(CODE_KEY));

    assertThat(shortLived.verify(EMAIL, code)).isFalse();
  }

  @Test
  void theCodeKeyHasTheCodeTtl() {
    service.issue(EMAIL);

    assertThat(redis.getExpire(CODE_KEY)).isBetween(14 * 60L, 15 * 60L);
  }

  @Test
  void redisHoldsAPepperedHmacNeverTheCodeOrAPlainHashOfIt() throws Exception {
    String code = service.issue(EMAIL);

    String stored =
        (String) redis.opsForHash().get(CODE_KEY, PasswordResetCodeService.CODE_HASH_FIELD);

    assertThat(stored).isEqualTo(service.hash(code));
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    for (String input : List.of(code, "reset-code:" + code)) {
      byte[] digest = sha256.digest(input.getBytes(StandardCharsets.UTF_8));
      assertThat(stored)
          .doesNotContain(code)
          .isNotEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
          .isNotEqualTo(Base64.getEncoder().encodeToString(digest))
          .isNotEqualTo(HexFormat.of().formatHex(digest));
    }
    // Keyed with the pepper: another pepper gives another value.
    assertThat(stored)
        .isNotEqualTo(serviceWithPepper("another-test-only-pepper-32-chars!!").hash(code));
  }

  /** The heart of single use: only one of many simultaneous correct submissions gets through. */
  @Test
  void concurrentCorrectSubmissionsSucceedExactlyOnce() throws Exception {
    String code = service.issue(EMAIL);

    List<Boolean> results = concurrently(20, () -> service.verify(EMAIL, code));

    assertThat(results).containsOnlyOnce(true);
  }

  @Test
  void concurrentWrongGuessesBurnTheCodeAfterExactlyMaxAttempts() throws Exception {
    String code = service.issue(EMAIL);
    String wrong = wrongCode(code);
    List<Long> attemptsSeen = new ArrayList<>();

    // Each thread's guess is counted atomically; once the limit is hit the key is gone.
    concurrently(
        20,
        () -> {
          boolean ok = service.verify(EMAIL, wrong);
          synchronized (attemptsSeen) {
            Object attempts =
                redis.opsForHash().get(CODE_KEY, PasswordResetCodeService.ATTEMPTS_FIELD);
            if (attempts != null) {
              attemptsSeen.add(Long.parseLong((String) attempts));
            }
          }
          return ok;
        });

    assertThat(redis.hasKey(CODE_KEY)).isFalse();
    assertThat(attemptsSeen).allMatch(attempts -> attempts < MAX_ATTEMPTS);
    assertThat(service.verify(EMAIL, code)).isFalse();
  }

  /** Exactly {@code maxAttempts} wrong guesses, not fewer: one fewer leaves the code usable. */
  @Test
  void concurrentWrongGuessesBelowTheLimitLeaveTheCodeUsable() throws Exception {
    String code = service.issue(EMAIL);
    String wrong = wrongCode(code);

    concurrently(MAX_ATTEMPTS - 1, () -> service.verify(EMAIL, wrong));

    assertThat(redis.opsForHash().get(CODE_KEY, PasswordResetCodeService.ATTEMPTS_FIELD))
        .isEqualTo(String.valueOf(MAX_ATTEMPTS - 1));
    assertThat(service.verify(EMAIL, code)).isTrue();
  }

  @Test
  void requestsAreAllowedUpToTheLimitThenRefused() {
    for (int i = 0; i < MAX_REQUESTS; i++) {
      assertThat(service.recordRequest(EMAIL)).isTrue();
    }
    assertThat(service.recordRequest(EMAIL)).isFalse();
    assertThat(service.recordRequest(EMAIL)).isFalse();

    // Per email: another address is unaffected.
    assertThat(service.recordRequest("other@example.com")).isTrue();
  }

  @Test
  void theRequestWindowRunsFromTheFirstRequest() {
    service.recordRequest(EMAIL);

    assertThat(redis.getExpire(PasswordResetCodeService.REQUEST_KEY_PREFIX + EMAIL))
        .isBetween(23 * 3600L, 24 * 3600L);
  }

  @Test
  void concurrentRequestsAreCountedOneByOne() throws Exception {
    List<Boolean> results = concurrently(20, () -> service.recordRequest(EMAIL));

    assertThat(results.stream().filter(allowed -> allowed)).hasSize(MAX_REQUESTS);
  }

  @Test
  void requestsAreAllowedAgainOnceTheWindowExpires() {
    PasswordResetCodeService shortWindow = service(Duration.ofMinutes(15), Duration.ofSeconds(1));
    for (int i = 0; i < MAX_REQUESTS; i++) {
      shortWindow.recordRequest(EMAIL);
    }
    assertThat(shortWindow.recordRequest(EMAIL)).isFalse();

    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> redis.keys(PasswordResetCodeService.REQUEST_KEY_PREFIX + "*").isEmpty());

    assertThat(shortWindow.recordRequest(EMAIL)).isTrue();
  }

  @Test
  void aRequestKeyThatLostItsTtlGetsANewWindowInsteadOfBlockingForever() {
    for (int i = 0; i < MAX_REQUESTS; i++) {
      service.recordRequest(EMAIL);
    }
    redis.persist(PasswordResetCodeService.REQUEST_KEY_PREFIX + EMAIL);

    assertThat(service.recordRequest(EMAIL)).isFalse();

    assertThat(redis.getExpire(PasswordResetCodeService.REQUEST_KEY_PREFIX + EMAIL)).isPositive();
  }

  /** Invites issue codes without going through the public request limit. */
  @Test
  void issuingDoesNotCountAsARequest() {
    for (int i = 0; i < MAX_REQUESTS + 1; i++) {
      service.issue(EMAIL);
    }

    assertThat(redis.hasKey(PasswordResetCodeService.REQUEST_KEY_PREFIX + EMAIL)).isFalse();
    assertThat(service.recordRequest(EMAIL)).isTrue();
  }

  private static String wrongCode(String code) {
    return code.equals("000000") ? "000001" : "000000";
  }

  private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return task.call();
                }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get());
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  private static PasswordResetCodeService service(Duration codeTtl, Duration requestWindow) {
    return new PasswordResetCodeService(
        redis,
        new PasswordResetProperties(codeTtl, MAX_ATTEMPTS, MAX_REQUESTS, requestWindow),
        new SecurityProperties(
            "test-only-jwt-secret-not-a-real-secret", "test-only-pepper-not-a-real-secret"));
  }

  private static PasswordResetCodeService serviceWithPepper(String pepper) {
    return new PasswordResetCodeService(
        redis,
        new PasswordResetProperties(
            Duration.ofMinutes(15), MAX_ATTEMPTS, MAX_REQUESTS, Duration.ofHours(24)),
        new SecurityProperties("test-only-jwt-secret-not-a-real-secret", pepper));
  }
}
