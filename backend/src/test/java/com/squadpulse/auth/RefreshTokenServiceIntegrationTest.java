package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.redis.testcontainers.RedisContainer;
import com.squadpulse.auth.RefreshTokenService.RefreshSession;
import com.squadpulse.auth.RefreshTokenService.Rotation;
import java.time.Duration;
import java.util.Set;
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
 * Proves the refresh-token family scheme (see {@link RefreshTokenService}) against a real Redis:
 * the Lua scripts, reuse detection revoking the family, and TTL-based expiry. No Spring context —
 * just the service on top of a real connection.
 */
@Testcontainers
class RefreshTokenServiceIntegrationTest {

  private static final Duration REFRESH_TTL = Duration.ofDays(30);

  @Container
  static final RedisContainer REDIS_CONTAINER =
      new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;

  private final RefreshTokenService service = service(REFRESH_TTL);

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
  void rotatingTheCurrentTokenReturnsANewOneOfTheSameSession() {
    String token = service.issue("user-1", "club-a");

    Rotation rotation = service.rotate(token);

    assertThat(rotation.session()).isEqualTo(new RefreshSession("user-1", "club-a"));
    assertThat(rotation.refreshToken()).isNotEqualTo(token);
    // The new token is itself rotatable.
    assertThat(service.rotate(rotation.refreshToken()).session())
        .isEqualTo(new RefreshSession("user-1", "club-a"));
  }

  @Test
  void reusingARotatedTokenRevokesTheWholeFamily() {
    String first = service.issue("user-1", "club-a");
    String second = service.rotate(first).refreshToken();

    // The stolen, already-rotated token shows up again...
    assertThatThrownBy(() -> service.rotate(first))
        .isInstanceOf(InvalidRefreshTokenException.class);

    // ...so the family's current token — whoever holds it — is dead too.
    assertThatThrownBy(() -> service.rotate(second))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(familyKeys()).isEmpty();
  }

  @Test
  void reuseRevokesOnlyThatFamilyNotTheUsersOtherSessions() {
    String laptop = service.issue("user-1", "club-a");
    String phone = service.issue("user-1", "club-a");
    service.rotate(laptop);

    assertThatThrownBy(() -> service.rotate(laptop))
        .isInstanceOf(InvalidRefreshTokenException.class);

    assertThat(service.rotate(phone).session()).isEqualTo(new RefreshSession("user-1", "club-a"));
  }

  @Test
  void revokingATokenKillsItsFamilyOnly() {
    String laptop = service.issue("user-1", "club-a");
    String phone = service.issue("user-1", "club-a");

    service.revoke(laptop);

    assertThatThrownBy(() -> service.rotate(laptop))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(service.rotate(phone)).isNotNull();
  }

  @Test
  void rejectsAnUnknownToken() {
    service.issue("user-1", "club-a");

    assertThatThrownBy(() -> service.rotate("never-issued"))
        .isInstanceOf(InvalidRefreshTokenException.class);
    // An unknown token isn't evidence of theft: no family is touched.
    assertThat(familyKeys()).hasSize(1);
  }

  @Test
  void redisNeverHoldsTheTokenItselfAndEveryKeyExpires() {
    String token = service.issue("user-1", "club-a");
    String rotated = service.rotate(token).refreshToken();

    Set<String> keys = redis.keys("auth:refresh:*");
    assertThat(keys).hasSize(3); // two token keys, one family key
    for (String key : keys) {
      assertThat(key).doesNotContain(token).doesNotContain(rotated);
      assertThat(redis.getExpire(key)).isPositive().isLessThanOrEqualTo(REFRESH_TTL.toSeconds());
      String value =
          key.startsWith(RefreshTokenService.FAMILY_KEY_PREFIX)
              ? redis.opsForHash().entries(key).toString()
              : redis.opsForValue().get(key);
      assertThat(value).doesNotContain(token).doesNotContain(rotated);
    }
  }

  @Test
  void anExpiredTokenIsRejected() {
    RefreshTokenService shortLived = service(Duration.ofSeconds(1));
    String token = shortLived.issue("user-1", "club-a");

    await().atMost(Duration.ofSeconds(5)).until(() -> redis.keys("auth:refresh:*").isEmpty());

    assertThatThrownBy(() -> shortLived.rotate(token))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  private static RefreshTokenService service(Duration refreshTtl) {
    return new RefreshTokenService(redis, new TokenProperties(Duration.ofMinutes(15), refreshTtl));
  }

  private static Set<String> familyKeys() {
    return redis.keys(RefreshTokenService.FAMILY_KEY_PREFIX + "*");
  }
}
