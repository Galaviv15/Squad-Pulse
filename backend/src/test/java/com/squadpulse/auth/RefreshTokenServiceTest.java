package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.squadpulse.auth.RefreshTokenService.RefreshSession;
import com.squadpulse.auth.RefreshTokenService.Rotation;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for {@link RefreshTokenService}'s own logic, with Redis mocked. The Lua scripts and
 * TTLs themselves are proven against a real Redis in {@link RefreshTokenServiceIntegrationTest}.
 */
class RefreshTokenServiceTest {

  private static final String FAMILY_KEY = RefreshTokenService.FAMILY_KEY_PREFIX + "family-1";

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final RefreshTokenService service =
      new RefreshTokenService(
          redis, new TokenProperties(Duration.ofMinutes(15), Duration.ofDays(30)));

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
  }

  @Test
  void issuesAHighEntropyTokenAndStoresOnlyItsHash() {
    String token = service.issue("user-1", "club-a");

    // 32 random bytes, base64url without padding.
    assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(redis).execute(eq(RefreshTokenService.ISSUE_SCRIPT), keys.capture(), args.capture());

    assertThat(keys.getValue())
        .contains(RefreshTokenService.TOKEN_KEY_PREFIX + RefreshTokenService.hash(token))
        .noneMatch(key -> key.contains(token));
    assertThat(args.getValue())
        .contains(RefreshTokenService.hash(token), "user-1", "club-a", "2592000")
        .doesNotContain(token);
  }

  @Test
  void issuesADifferentTokenEveryTime() {
    assertThat(service.issue("user-1", "club-a")).isNotEqualTo(service.issue("user-1", "club-a"));
  }

  @Test
  void rotatesTheCurrentTokenOfAFamily() {
    when(values.get(tokenKey("old-token"))).thenReturn("family-1");
    whenRotateScriptReturns(List.of("ROTATED", "user-1", "club-a"));

    Rotation rotation = service.rotate("old-token");

    assertThat(rotation.session()).isEqualTo(new RefreshSession("user-1", "club-a"));
    assertThat(rotation.refreshToken()).isNotEqualTo("old-token").hasSize(43);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(redis)
        .execute(
            eq(RefreshTokenService.ROTATE_SCRIPT),
            eq(
                List.of(
                    FAMILY_KEY,
                    RefreshTokenService.TOKEN_KEY_PREFIX
                        + RefreshTokenService.hash(rotation.refreshToken()))),
            args.capture());
    assertThat(args.getValue())
        .startsWith(
            RefreshTokenService.hash("old-token"),
            RefreshTokenService.hash(rotation.refreshToken()));
  }

  /** The script already deleted the family; the service must refuse, not hand out a token. */
  @Test
  void rejectsAnAlreadyRotatedToken() {
    when(values.get(tokenKey("rotated-token"))).thenReturn("family-1");
    whenRotateScriptReturns(List.of("REUSED"));

    assertThatThrownBy(() -> service.rotate("rotated-token"))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void rejectsATokenOfARevokedFamily() {
    when(values.get(tokenKey("token"))).thenReturn("family-1");
    whenRotateScriptReturns(List.of("REVOKED"));

    assertThatThrownBy(() -> service.rotate("token"))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void rejectsAnUnknownOrExpiredTokenWithoutTouchingAnyFamily() {
    assertThatThrownBy(() -> service.rotate("unknown-token"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(redis, never()).execute(any(), anyList(), any(Object[].class));
  }

  @Test
  void rejectsAMissingToken() {
    assertThatThrownBy(() -> service.rotate(null)).isInstanceOf(InvalidRefreshTokenException.class);
    assertThatThrownBy(() -> service.rotate(" ")).isInstanceOf(InvalidRefreshTokenException.class);

    verify(values, never()).get(anyString());
  }

  @Test
  void revokeDeletesTheTokensFamily() {
    when(values.get(tokenKey("token"))).thenReturn("family-1");

    service.revoke("token");

    verify(redis).delete(FAMILY_KEY);
  }

  @Test
  void revokeIgnoresAnUnknownOrMissingToken() {
    service.revoke("unknown-token");
    service.revoke(null);

    verify(redis, never()).delete(anyString());
  }

  private void whenRotateScriptReturns(List<String> result) {
    when(redis.execute(eq(RefreshTokenService.ROTATE_SCRIPT), anyList(), any(Object[].class)))
        .thenReturn(result);
  }

  private static String tokenKey(String token) {
    return RefreshTokenService.TOKEN_KEY_PREFIX + RefreshTokenService.hash(token);
  }
}
