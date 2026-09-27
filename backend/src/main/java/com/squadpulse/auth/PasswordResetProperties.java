package com.squadpulse.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on activation / password-reset codes, bound from {@code
 * squadpulse.security.password-reset.*} (see application.yml and {@link PasswordResetCodeService}).
 *
 * <p>Validated like {@link LoginThrottleProperties}: a missing or non-positive value stops the
 * application from starting instead of issuing codes that never expire, or allowing unlimited
 * guesses.
 *
 * @param codeTtl how long a code stays usable after it's issued
 * @param maxAttempts wrong guesses a code survives; the guess that reaches this deletes it
 * @param maxRequests {@code POST /auth/forgot-password} requests one email gets per window; beyond
 *     that, requests are silently ignored
 * @param requestWindow how long an email's request count lives, from its first request
 */
@Validated
@ConfigurationProperties(prefix = "squadpulse.security.password-reset")
public record PasswordResetProperties(
    @NotNull @DurationMin(seconds = 1) Duration codeTtl,
    @NotNull @Positive Integer maxAttempts,
    @NotNull @Positive Integer maxRequests,
    @NotNull @DurationMin(seconds = 1) Duration requestWindow) {}
