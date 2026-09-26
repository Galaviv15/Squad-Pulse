package com.squadpulse.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Issues, rotates and revokes refresh tokens, kept in Redis (see docs/spec.md section 10).
 *
 * <p>A refresh token is an opaque 256-bit random value (base64url, from {@link SecureRandom}) — not
 * a JWT, so it carries nothing and can only be checked against Redis, which is what makes it
 * revocable. It's only ever sent to the client in an HttpOnly cookie (see {@link AuthController}).
 *
 * <h2>Token families</h2>
 *
 * Every login starts a new <b>family</b>: one login session on one device. Each refresh
 * <b>rotates</b> the family — a new token replaces the presented one, which stops being valid. Only
 * the newest token of a family is ever accepted. Presenting an older, already-rotated token means
 * two parties hold tokens of the same family — i.e. one was stolen — so the whole family is
 * revoked, logging out the thief and the legitimate device alike (the next refresh from either
 * fails). The user's other families (other devices) are unaffected. Logout revokes the caller's
 * family the same way.
 *
 * <h2>Redis key scheme</h2>
 *
 * <pre>
 * auth:refresh:token:{tokenHash}   STRING  familyId
 *                                          TTL: refresh TTL from when this token was issued
 * auth:refresh:family:{familyId}   HASH    userId  - the user the family was issued to
 *                                          clubId  - that user's club
 *                                          current - tokenHash of the family's only valid token
 *                                          TTL: refresh TTL, reset on every rotation
 * </pre>
 *
 * {@code tokenHash} is base64url(SHA-256(token)): Redis never holds a usable token, so a leaked
 * dump or a {@code KEYS} listing can't be replayed. {@code familyId} is a random UUID.
 *
 * <p>For a presented token this answers: (a) is it valid — its token key exists, its family key
 * exists, and {@code current} equals its hash; (b) whose is it — the family's {@code userId}/{@code
 * clubId}; (c) was it rotated away — its token key still exists but {@code current} holds another
 * hash. A token key outlives its rotation until its own TTL runs out, which is what makes (c)
 * detectable for the token's whole lifetime.
 *
 * <p>Revoking a family is a single {@code DEL} of its family key: its token keys then point at
 * nothing and are rejected until they expire on their own. Every key has a TTL, so nothing needs
 * sweeping. Issuing and rotating each run as one Lua script, so they're atomic — two concurrent
 * refreshes with the same token can't both succeed; the second is treated as reuse. The scripts
 * touch a token key and a family key whose slots differ, so this assumes a single Redis node, not
 * Redis Cluster.
 */
@Service
class RefreshTokenService {

  static final String TOKEN_KEY_PREFIX = "auth:refresh:token:";
  static final String FAMILY_KEY_PREFIX = "auth:refresh:family:";

  static final String USER_ID_FIELD = "userId";
  static final String CLUB_ID_FIELD = "clubId";
  static final String CURRENT_FIELD = "current";

  private static final int TOKEN_BYTES = 32;

  private static final String ROTATED = "ROTATED";
  private static final String REUSED = "REUSED";

  /** KEYS: family key, token key. ARGV: token hash, userId, clubId, familyId, TTL in seconds. */
  static final RedisScript<Long> ISSUE_SCRIPT =
      RedisScript.of(
          """
          redis.call('HSET', KEYS[1], 'userId', ARGV[2], 'clubId', ARGV[3], 'current', ARGV[1])
          redis.call('EXPIRE', KEYS[1], ARGV[5])
          redis.call('SET', KEYS[2], ARGV[4], 'EX', ARGV[5])
          return 1
          """,
          Long.class);

  /**
   * KEYS: family key, new token key. ARGV: presented token hash, new token hash, familyId, TTL in
   * seconds. Returns {@code [ROTATED, userId, clubId]}, {@code [REUSED]} (family deleted) or {@code
   * [REVOKED]} (no such family).
   */
  @SuppressWarnings("rawtypes")
  static final RedisScript<List> ROTATE_SCRIPT =
      RedisScript.of(
          """
          local family = redis.call('HMGET', KEYS[1], 'current', 'userId', 'clubId')
          if not family[1] then
            return {'REVOKED'}
          end
          if family[1] ~= ARGV[1] then
            redis.call('DEL', KEYS[1])
            return {'REUSED'}
          end
          redis.call('HSET', KEYS[1], 'current', ARGV[2])
          redis.call('EXPIRE', KEYS[1], ARGV[4])
          redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
          return {'ROTATED', family[2], family[3]}
          """,
          List.class);

  private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

  /** Who a refresh token was issued to. */
  record RefreshSession(String userId, String clubId) {}

  /** The token that replaced the presented one, and whose family it belongs to. */
  record Rotation(String refreshToken, RefreshSession session) {}

  private final StringRedisTemplate redis;
  private final TokenProperties tokenProperties;
  private final SecureRandom secureRandom = new SecureRandom();

  RefreshTokenService(StringRedisTemplate redis, TokenProperties tokenProperties) {
    this.redis = redis;
    this.tokenProperties = tokenProperties;
  }

  /** Starts a new family for a fresh login and returns its first token. */
  String issue(String userId, String clubId) {
    String token = newToken();
    String familyId = UUID.randomUUID().toString();
    redis.execute(
        ISSUE_SCRIPT,
        List.of(FAMILY_KEY_PREFIX + familyId, TOKEN_KEY_PREFIX + hash(token)),
        hash(token),
        userId,
        clubId,
        familyId,
        ttlSeconds());
    return token;
  }

  /**
   * Replaces {@code presentedToken} with a new token of the same family.
   *
   * @throws InvalidRefreshTokenException if the token is missing, unknown, expired or its family is
   *     revoked — or if it was already rotated away, in which case its whole family is revoked
   */
  Rotation rotate(String presentedToken) {
    String presentedHash = hashOrReject(presentedToken);
    String familyId = redis.opsForValue().get(TOKEN_KEY_PREFIX + presentedHash);
    if (familyId == null) {
      throw new InvalidRefreshTokenException();
    }

    String newToken = newToken();
    List<?> result =
        redis.execute(
            ROTATE_SCRIPT,
            List.of(FAMILY_KEY_PREFIX + familyId, TOKEN_KEY_PREFIX + hash(newToken)),
            presentedHash,
            hash(newToken),
            familyId,
            ttlSeconds());

    String outcome = result == null || result.isEmpty() ? null : String.valueOf(result.get(0));
    if (ROTATED.equals(outcome)) {
      return new Rotation(
          newToken,
          new RefreshSession(String.valueOf(result.get(1)), String.valueOf(result.get(2))));
    }
    if (REUSED.equals(outcome)) {
      log.warn(
          "Refresh token reuse detected — revoked token family {} (possible token theft)",
          familyId);
    }
    throw new InvalidRefreshTokenException();
  }

  /**
   * Revokes the family {@code presentedToken} belongs to, so none of its tokens can be used again.
   * A no-op for a missing or unknown token — logging out twice isn't an error.
   */
  void revoke(String presentedToken) {
    if (presentedToken == null || presentedToken.isBlank()) {
      return;
    }
    String familyId = redis.opsForValue().get(TOKEN_KEY_PREFIX + hash(presentedToken));
    if (familyId != null) {
      redis.delete(FAMILY_KEY_PREFIX + familyId);
    }
  }

  private String hashOrReject(String presentedToken) {
    if (presentedToken == null || presentedToken.isBlank()) {
      throw new InvalidRefreshTokenException();
    }
    return hash(presentedToken);
  }

  private String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String ttlSeconds() {
    return String.valueOf(tokenProperties.refreshTtl().toSeconds());
  }

  static String hash(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
