package com.squadpulse.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Counts login attempts in Redis and refuses further ones once there are too many (see {@link
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
 * auth:login-throttle:{normalizedEmail}:{ip}   STRING  number of login attempts in the window
 *                                                      TTL: the window, set at the first attempt
 * </pre>
 *
 * The email isn't hashed as refresh tokens are: it's not a bearer credential, just a rate-limit
 * key.
 *
 * <p>Every attempt is counted <b>before</b> the password is checked, and allowed only if that same
 * increment kept the count within the limit. Redis runs the script atomically, so concurrent
 * attempts get distinct, sequential counts — a burst can't slip past the limit together. A
 * successful login deletes the key, so the attempts a legitimate user spent on typos don't linger;
 * in effect the limit is on failed logins.
 *
 * <p>A fixed window: the TTL is set only when an attempt creates the key, so it runs from the first
 * attempt and later ones don't extend it. Once the window expires the key is gone and the count
 * starts over. Every key has a TTL, so nothing needs sweeping.
 */
@Service
class LoginThrottleService {

  static final String KEY_PREFIX = "auth:login-throttle:";

  /**
   * KEYS: throttle key. ARGV: max attempts, window in milliseconds. Counts the attempt, then
   * returns 0 if it's within the limit, otherwise the remaining window in milliseconds (at least
   * 1). A key that has lost its TTL (only possible if something outside this service touched it)
   * gets a fresh window rather than blocking the pair forever.
   */
  static final RedisScript<Long> ATTEMPT_SCRIPT =
      RedisScript.of(
          """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
          end
          if count <= tonumber(ARGV[1]) then
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
   * Counts a login attempt by the pair, and refuses it if that takes the pair over the limit.
   *
   * @param normalizedEmail the email as {@link User#normalizeEmail} returns it
   * @param ip the client's address
   * @throws LoginThrottledException if the pair has used up its attempts for this window
   */
  void recordAttempt(String normalizedEmail, String ip) {
    Long remainingMillis =
        redis.execute(
            ATTEMPT_SCRIPT,
            List.of(key(normalizedEmail, ip)),
            properties.maxAttempts().toString(),
            String.valueOf(properties.window().toMillis()));
    if (remainingMillis != null && remainingMillis > 0) {
      throw new LoginThrottledException(Duration.ofMillis(remainingMillis));
    }
  }

  /** Forgets the pair's attempts, after a successful login. */
  void reset(String normalizedEmail, String ip) {
    redis.delete(key(normalizedEmail, ip));
  }

  static String key(String normalizedEmail, String ip) {
    return KEY_PREFIX + normalizedEmail + ":" + ip;
  }
}
