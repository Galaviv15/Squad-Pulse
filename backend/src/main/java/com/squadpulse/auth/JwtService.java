package com.squadpulse.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Issues and validates access tokens: short-lived JWTs, HS256-signed with {@code JWT_SECRET} (see
 * docs/spec.md section 10).
 *
 * <p>Claims: {@code sub} (user id), {@code clubId}, {@code permissionLevel}, plus {@code iss},
 * {@code iat} and {@code exp} — nothing else, and in particular nothing sensitive: a JWT is signed,
 * not encrypted, so anyone holding one can read it. {@code clubId} here is what {@link
 * JwtAuthenticationFilter} puts into {@link com.squadpulse.common.ClubContext} for the request.
 *
 * <p>Refresh tokens are deliberately not JWTs — see {@link RefreshTokenService}.
 */
@Service
class JwtService {

  static final String ISSUER = "squadpulse";
  static final String CLUB_ID_CLAIM = "clubId";
  static final String PERMISSION_LEVEL_CLAIM = "permissionLevel";

  private static final Logger log = LoggerFactory.getLogger(JwtService.class);

  /** A freshly issued access token and when it stops being accepted. */
  record AccessToken(String value, Instant expiresAt) {}

  private final SecretKey key;
  private final TokenProperties tokenProperties;
  private final Clock clock;
  private final JwtParser parser;

  @Autowired
  JwtService(SecurityProperties securityProperties, TokenProperties tokenProperties) {
    this(securityProperties, tokenProperties, Clock.systemUTC());
  }

  /** For tests: {@code clock} decides both the issue time and "now" when validating. */
  JwtService(SecurityProperties securityProperties, TokenProperties tokenProperties, Clock clock) {
    // SecurityProperties guarantees at least 32 characters, i.e. the 256 bits HS256 requires.
    this.key =
        new SecretKeySpec(
            securityProperties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    this.tokenProperties = tokenProperties;
    this.clock = clock;
    // verifyWith() only accepts HMAC-signed tokens, so unsigned ("alg": "none") or asymmetrically
    // signed tokens are rejected outright.
    this.parser =
        Jwts.parser()
            .verifyWith(key)
            .requireIssuer(ISSUER)
            .clock(() -> Date.from(clock.instant()))
            .build();
  }

  AccessToken issue(User user) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(tokenProperties.accessTtl());
    String token =
        Jwts.builder()
            .issuer(ISSUER)
            .subject(user.getId())
            .claim(CLUB_ID_CLAIM, user.getClubId())
            .claim(PERMISSION_LEVEL_CLAIM, user.getPermissionLevel().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    return new AccessToken(token, expiresAt);
  }

  /**
   * Returns the caller the token identifies, or empty if the token is malformed, not signed with
   * {@code JWT_SECRET}, from another issuer, expired, or missing a claim.
   */
  Optional<AuthenticatedUser> parse(String token) {
    try {
      Claims claims = parser.parseSignedClaims(token).getPayload();
      String userId = claims.getSubject();
      String clubId = claims.get(CLUB_ID_CLAIM, String.class);
      String permissionLevel = claims.get(PERMISSION_LEVEL_CLAIM, String.class);
      if (isBlank(userId) || isBlank(clubId) || isBlank(permissionLevel)) {
        log.debug("Rejected access token: missing claim");
        return Optional.empty();
      }
      return Optional.of(
          new AuthenticatedUser(userId, clubId, PermissionLevel.valueOf(permissionLevel)));
    } catch (JwtException | IllegalArgumentException e) {
      // Just the reason — never the token itself.
      log.debug("Rejected access token: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
