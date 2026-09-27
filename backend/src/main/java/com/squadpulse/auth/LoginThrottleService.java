package com.squadpulse.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Counts failed logins in Redis and refuses further attempts once there are too many (see {@link
 * LoginThrottleProperties} for the limits, and {@link AuthService#login} for how it's used).
 *
 * <p>Counted per <b>(email, IP) pair</b>, not per email or per IP alone: per email, anyone who
 * knows a user's address could lock them out by failing their password on purpose; per IP, an
 * attacker could rotate addresses to get around it, while everyone behind one shared address (an
 * office, a NAT) would be throttled together. Unknown emails are counted exactly like registered
 * ones, so being throttled reveals nothing about which emails exist.
 *
 * <h2>Redis key scheme</h2>
 *
 * <pre>
 * auth:login-throttle:{normalizedEmail}:{ip}   STRING  number of failed logins in the window
 *                                                      TTL: the window, set at the first failure
 * </pre>
 *
 * The email isn't hashed as refresh tokens are: it's not a bearer credential, just a rate-limit
 * key.
 *
 * <p>A fixed window: the TTL is set only when a failure creates the key, so it runs from the first
 * failure and later failures don't extend it. Once the window expires the key is gone and the count
 * starts over; a successful login deletes it early. Every key has a TTL, so nothing needs sweeping.
 * Counting runs as one Lua script so the increment and its first {@code EXPIRE} can't be split, and
 * the check reads the count and the remaining TTL in one script so the key can't expire in between.
 *
 * <p>The check comes before the password check and the count after it, so up to as many attempts as
 * a client sends <i>concurrently</i> can pass the check together before any of them is counted —
 * the limit bounds sustained guessing, not a single burst.
 */
@Service
class LoginThrottleService {

  static final String KEY_PREFIX = "auth:login-throttle:";

  /** KEYS: throttle key. ARGV: window in milliseconds. Returns the count after incrementing. */
  static final RedisScript<Long> RECORD_FAILURE_SCRIPT =
      RedisScript.of(
          """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
          end
          return count
          """,
          Long.class);

  /**
   * KEYS: throttle key. ARGV: max attempts, window in milliseconds. Returns 0 if the pair may still
   * try, otherwise the remaining window in milliseconds (at least 1). A key that has lost its TTL
   * (only possible if something outside this service touched it) gets a fresh window rather than
   * blocking the pair forever.
   */
  static final RedisScript<Long> CHECK_SCRIPT =
      RedisScript.of(
          """
          local count = tonumber(redis.call('GET', KEYS[1]) or '0')
          if count < tonumber(ARGV[1]) then
            return 0
          end
          local ttl = redis.call('PTTL', KEYS[1])
          if ttl < 0 then
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return tonumber(ARGV[2])
          end
          -- 0 means "allowed", so a key in its last millisecond still reports 1.
          return math.max(ttl, 1)
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final LoginThrottleProperties properties;

  LoginThrottleService(StringRedisTemplate redis, LoginThrottleProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  /**
   * @param normalizedEmail the email as {@link User#normalizeEmail} returns it
   * @param ip the client's address
   * @throws LoginThrottledException if the pair has used up its failed attempts for this window
   */
  void checkAllowed(String normalizedEmail, String ip) {
    Long remainingMillis =
        redis.execute(
            CHECK_SCRIPT,
            List.of(key(normalizedEmail, ip)),
            properties.maxAttempts().toString(),
            windowMillis());
    if (remainingMillis != null && remainingMillis > 0) {
      throw new LoginThrottledException(Duration.ofMillis(remainingMillis));
    }
  }

  /** Counts one failed login for the pair, starting a new window if there's none running. */
  void recordFailure(String normalizedEmail, String ip) {
    redis.execute(RECORD_FAILURE_SCRIPT, List.of(key(normalizedEmail, ip)), windowMillis());
  }

  /** Forgets the pair's failed logins, after a successful one. */
  void reset(String normalizedEmail, String ip) {
    redis.delete(key(normalizedEmail, ip));
  }

  private String windowMillis() {
    return String.valueOf(properties.window().toMillis());
  }

  static String key(String normalizedEmail, String ip) {
    return KEY_PREFIX + normalizedEmail + ":" + ip;
  }
}
