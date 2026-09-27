package com.squadpulse.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Issues and checks the one-time 6-digit codes behind account activation and password reset, kept
 * in Redis (see docs/spec.md section 10, and {@link PasswordResetProperties} for the limits).
 *
 * <p>A code is {@code SecureRandom.nextInt(1_000_000)}, zero-padded — only 10^6 possibilities, so
 * what protects it is the attempt limit, not its entropy: a code is deleted by its own {@code
 * maxAttempts}-th wrong guess, and by its first correct one (single use).
 *
 * <h2>Why the request limit has a long window</h2>
 *
 * Each code an attacker has sent to a victim's email is worth {@code maxAttempts} guesses at 1 in
 * 10^6. So the number of codes that can be requested, not the per-code limit, is what bounds a
 * persistent brute-force attempt: at the default 5 requests per 24 hours that's 25 guesses a day,
 * about a 1% chance over a whole year. A 15-minute window like login's would allow about 480
 * guesses a day. Requests are counted per email, registered or not, <b>before</b> anything else
 * (see {@link #recordRequest}); issuing for an invite doesn't count (see {@link #issue}).
 *
 * <h2>Redis key scheme</h2>
 *
 * <pre>
 * auth:reset-code:{normalizedEmail}      HASH    codeHash - HMAC of the current code
 *                                                attempts - wrong guesses so far
 *                                                TTL: code TTL, set on issue
 * auth:reset-request:{normalizedEmail}   STRING  number of reset requests in the window
 *                                                TTL: request window, set at the first request
 * </pre>
 *
 * {@code codeHash} is base64url(HMAC-SHA256(pepper, "reset-code:" + code)). Never the code itself
 * or a bare SHA-256 of it: with only 10^6 codes, a plain hash is reversed instantly by anyone with
 * a Redis dump; without the pepper it can't be. The {@code reset-code:} prefix separates this use
 * of the pepper from password peppering (see {@link PepperedPasswordEncoder}), so the two can never
 * produce each other's values. The email isn't hashed — like in {@link LoginThrottleService}, it's
 * just a key, not a credential.
 *
 * <p>Issuing, verifying and counting a request each run as one Lua script, so they're atomic: two
 * concurrent correct submissions can't both succeed, and concurrent wrong guesses can't together
 * exceed the attempt limit. Every key has a TTL, so nothing needs sweeping. Like {@link
 * RefreshTokenService}, this assumes a single Redis node, not Redis Cluster.
 */
@Service
class PasswordResetCodeService {

  static final String CODE_KEY_PREFIX = "auth:reset-code:";
  static final String REQUEST_KEY_PREFIX = "auth:reset-request:";

  static final String CODE_HASH_FIELD = "codeHash";
  static final String ATTEMPTS_FIELD = "attempts";

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String HMAC_DOMAIN = "reset-code:";
  private static final int CODE_SPACE = 1_000_000;

  /**
   * KEYS: code key. ARGV: code hash, TTL in milliseconds. Replaces any previous code for the email
   * and resets its attempts.
   */
  static final RedisScript<Long> ISSUE_SCRIPT =
      RedisScript.of(
          """
          redis.call('DEL', KEYS[1])
          redis.call('HSET', KEYS[1], 'codeHash', ARGV[1], 'attempts', 0)
          redis.call('PEXPIRE', KEYS[1], ARGV[2])
          return 1
          """,
          Long.class);

  /**
   * KEYS: code key. ARGV: presented code hash, max attempts. Returns 1 if the code matches (and
   * deletes it), otherwise 0 — for no code at all, too. A wrong guess is counted, and the one that
   * reaches max attempts deletes the code.
   */
  static final RedisScript<Long> VERIFY_SCRIPT =
      RedisScript.of(
          """
          local stored = redis.call('HGET', KEYS[1], 'codeHash')
          if not stored then
            return 0
          end
          if stored == ARGV[1] then
            redis.call('DEL', KEYS[1])
            return 1
          end
          local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
          if attempts >= tonumber(ARGV[2]) then
            redis.call('DEL', KEYS[1])
          end
          return 0
          """,
          Long.class);

  /**
   * KEYS: request key. ARGV: max requests, window in milliseconds. Counts the request and returns 1
   * if it's within the limit, otherwise 0. Same fixed-window shape as {@link
   * LoginThrottleService#ATTEMPT_SCRIPT}, including a fresh window for a key that has lost its TTL.
   */
  static final RedisScript<Long> REQUEST_SCRIPT =
      RedisScript.of(
          """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
          end
          if count <= tonumber(ARGV[1]) then
            return 1
          end
          if redis.call('PTTL', KEYS[1]) < 0 then
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
          end
          return 0
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final PasswordResetProperties properties;
  private final SecretKeySpec pepperKey;
  private final SecureRandom secureRandom = new SecureRandom();

  PasswordResetCodeService(
      StringRedisTemplate redis,
      PasswordResetProperties properties,
      SecurityProperties securityProperties) {
    this.redis = redis;
    this.properties = properties;
    // SecurityProperties guarantees a pepper of at least 32 characters.
    this.pepperKey =
        new SecretKeySpec(
            securityProperties.passwordPepper().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
  }

  /**
   * Counts a {@code POST /auth/forgot-password} request for the email — registered or not.
   *
   * @param normalizedEmail the email as {@link User#normalizeEmail} returns it
   * @return whether the request is within the limit; if not, the caller must issue nothing
   */
  boolean recordRequest(String normalizedEmail) {
    Long allowed =
        redis.execute(
            REQUEST_SCRIPT,
            List.of(REQUEST_KEY_PREFIX + normalizedEmail),
            properties.maxRequests().toString(),
            String.valueOf(properties.requestWindow().toMillis()));
    return allowed != null && allowed == 1;
  }

  /**
   * Issues a new code for the email, replacing any earlier one, and returns it — the only time the
   * plaintext code exists outside the email. Doesn't count against the request limit: that's up to
   * the caller ({@link #recordRequest}).
   */
  String issue(String normalizedEmail) {
    String code = "%06d".formatted(secureRandom.nextInt(CODE_SPACE));
    redis.execute(
        ISSUE_SCRIPT,
        List.of(CODE_KEY_PREFIX + normalizedEmail),
        hash(code),
        String.valueOf(properties.codeTtl().toMillis()));
    return code;
  }

  /**
   * Checks a code, consuming it if it's right and counting the guess if it's wrong.
   *
   * @return {@code true} only if {@code code} is the email's current, unexpired code; {@code false}
   *     for a wrong code, or when there's no code (never issued, expired, used, or burned by too
   *     many wrong guesses)
   */
  boolean verify(String normalizedEmail, String code) {
    Long matched =
        redis.execute(
            VERIFY_SCRIPT,
            List.of(CODE_KEY_PREFIX + normalizedEmail),
            hash(code),
            properties.maxAttempts().toString());
    return matched != null && matched == 1;
  }

  /** base64url(HMAC-SHA256(pepper, "reset-code:" + code)) — what Redis holds for a code. */
  String hash(String code) {
    try {
      // Mac isn't thread-safe, so one per call.
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(pepperKey);
      byte[] digest = mac.doFinal((HMAC_DOMAIN + code).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
