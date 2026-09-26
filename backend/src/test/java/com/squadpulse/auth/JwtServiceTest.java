package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.auth.JwtService.AccessToken;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  private static final String SECRET = "test-only-jwt-secret-not-a-real-secret";
  private static final String OTHER_SECRET = "another-test-only-jwt-secret-not-real";
  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final Duration ACCESS_TTL = Duration.ofMinutes(15);

  private final JwtService jwtService = service(SECRET, NOW);

  @Test
  void aFreshlyIssuedTokenIdentifiesTheUser() {
    AccessToken token = jwtService.issue(user());

    assertThat(token.expiresAt()).isEqualTo(NOW.plus(ACCESS_TTL));
    assertThat(jwtService.parse(token.value()))
        .contains(new AuthenticatedUser("user-1", "club-a", PermissionLevel.ADMIN));
  }

  @Test
  void theTokenCarriesOnlyTheExpectedClaims() {
    User user = user();
    user.setPasswordHash("$argon2id$some-hash");

    String payload = decodedPayload(jwtService.issue(user).value());

    assertThat(payload)
        .contains("\"sub\":\"user-1\"")
        .contains("\"clubId\":\"club-a\"")
        .contains("\"permissionLevel\":\"ADMIN\"")
        .contains("\"iss\":\"squadpulse\"")
        .doesNotContain("argon2")
        .doesNotContain("coach@example.com");
  }

  @Test
  void isStillAcceptedJustBeforeItExpires() {
    String token = jwtService.issue(user()).value();

    JwtService later = service(SECRET, NOW.plus(ACCESS_TTL).minusSeconds(1));

    assertThat(later.parse(token)).isPresent();
  }

  @Test
  void rejectsAnExpiredToken() {
    String token = jwtService.issue(user()).value();

    JwtService later = service(SECRET, NOW.plus(ACCESS_TTL).plusSeconds(1));

    assertThat(later.parse(token)).isEmpty();
  }

  @Test
  void rejectsATokenWhosePayloadWasTamperedWith() {
    String[] parts = jwtService.issue(user()).value().split("\\.");
    String forgedPayload =
        new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            .replace("club-a", "club-b");
    String forged =
        parts[0]
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
            + "."
            + parts[2];

    assertThat(jwtService.parse(forged)).isEmpty();
  }

  @Test
  void rejectsATokenSignedWithADifferentSecret() {
    String token = service(OTHER_SECRET, NOW).issue(user()).value();

    assertThat(jwtService.parse(token)).isEmpty();
  }

  @Test
  void rejectsAnUnsignedToken() {
    String unsigned =
        Jwts.builder()
            .issuer(JwtService.ISSUER)
            .subject("user-1")
            .claim(JwtService.CLUB_ID_CLAIM, "club-a")
            .claim(JwtService.PERMISSION_LEVEL_CLAIM, "ADMIN")
            .expiration(Date.from(NOW.plus(ACCESS_TTL)))
            .compact();

    assertThat(jwtService.parse(unsigned)).isEmpty();
  }

  @Test
  void rejectsATokenFromAnotherIssuer() {
    String token =
        Jwts.builder()
            .issuer("someone-else")
            .subject("user-1")
            .claim(JwtService.CLUB_ID_CLAIM, "club-a")
            .claim(JwtService.PERMISSION_LEVEL_CLAIM, "ADMIN")
            .expiration(Date.from(NOW.plus(ACCESS_TTL)))
            .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
            .compact();

    assertThat(jwtService.parse(token)).isEmpty();
  }

  @Test
  void rejectsATokenWithoutAClubId() {
    String token =
        Jwts.builder()
            .issuer(JwtService.ISSUER)
            .subject("user-1")
            .claim(JwtService.PERMISSION_LEVEL_CLAIM, "ADMIN")
            .expiration(Date.from(NOW.plus(ACCESS_TTL)))
            .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
            .compact();

    assertThat(jwtService.parse(token)).isEmpty();
  }

  @Test
  void rejectsGarbage() {
    assertThat(jwtService.parse("not-a-jwt")).isEmpty();
    assertThat(jwtService.parse("")).isEmpty();
  }

  private static JwtService service(String secret, Instant now) {
    return new JwtService(
        new SecurityProperties(secret, "test-only-pepper-not-a-real-secret"),
        new TokenProperties(ACCESS_TTL, Duration.ofDays(30)),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  private static User user() {
    User user = new User();
    user.setId("user-1");
    user.setClubId("club-a");
    user.setEmail("coach@example.com");
    user.setPermissionLevel(PermissionLevel.ADMIN);
    user.setTitle(Title.CLUB_MANAGER);
    user.setFullName("Dana Levi");
    return user;
  }

  private static String decodedPayload(String token) {
    return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
  }
}
