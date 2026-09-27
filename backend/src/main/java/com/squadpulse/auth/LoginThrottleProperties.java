package com.squadpulse.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on failed {@code POST /auth/login} attempts, bound from {@code
 * squadpulse.security.login-throttle.*} (see application.yml and {@link LoginThrottleService}).
 *
 * <p>Validated like {@link TokenProperties}: a missing or non-positive value stops the application
 * from starting instead of throttling nothing, or everything.
 *
 * @param maxAttempts how many failed logins one (email, IP) pair gets per window; the attempt after
 *     that is refused without checking the password
 * @param window how long the pair's count lives, from its first failure
 */
@Validated
@ConfigurationProperties(prefix = "squadpulse.security.login-throttle")
public record LoginThrottleProperties(
    @NotNull @Positive Integer maxAttempts, @NotNull @DurationMin(seconds = 1) Duration window) {}
