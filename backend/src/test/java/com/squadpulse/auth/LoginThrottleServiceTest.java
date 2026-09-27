package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unit tests for {@link LoginThrottleService}'s own logic, with Redis mocked. The Lua script, the
 * counting behavior and TTL expiry are proven against a real Redis in {@link
 * LoginThrottleServiceIntegrationTest}.
 */
class LoginThrottleServiceTest {

  private static final String KEY = "auth:login-throttle:coach@example.com:203.0.113.7";

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final LoginThrottleService service =
      new LoginThrottleService(redis, new LoginThrottleProperties(5, Duration.ofMinutes(15)));

  @Test
  void keysByEmailAndIp() {
    assertThat(LoginThrottleService.key("coach@example.com", "203.0.113.7")).isEqualTo(KEY);
  }

  @Test
  void recordAttemptPassesTheLimitsToTheScriptAndAllowsWhenItReturnsZero() {
    whenAttemptScriptReturns(0L);

    assertThatCode(() -> service.recordAttempt("coach@example.com", "203.0.113.7"))
        .doesNotThrowAnyException();

    verify(redis).execute(LoginThrottleService.ATTEMPT_SCRIPT, List.of(KEY), "5", "900000");
  }

  @Test
  void recordAttemptThrowsWithTheRemainingWindowWhenTheScriptReportsOne() {
    whenAttemptScriptReturns(42_500L);

    assertThatThrownBy(() -> service.recordAttempt("coach@example.com", "203.0.113.7"))
        .isInstanceOfSatisfying(
            LoginThrottledException.class,
            e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofMillis(42_500)))
        .hasMessage("Too many failed login attempts, try again later");
  }

  @Test
  void resetDeletesThePairsKey() {
    service.reset("coach@example.com", "203.0.113.7");

    verify(redis).delete(KEY);
  }

  private void whenAttemptScriptReturns(long result) {
    when(redis.execute(eq(LoginThrottleService.ATTEMPT_SCRIPT), anyList(), any(Object[].class)))
        .thenReturn(result);
  }
}
