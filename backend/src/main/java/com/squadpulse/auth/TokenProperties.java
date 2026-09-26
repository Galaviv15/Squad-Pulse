package com.squadpulse.auth;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token lifetimes, bound from {@code squadpulse.security.token.*} (see application.yml).
 *
 * <p>Not secrets, so they're kept out of {@link SecurityProperties}, but validated the same way: a
 * missing or non-positive value stops the application from starting instead of issuing tokens that
 * never, or immediately, expire.
 *
 * @param accessTtl how long an access-token JWT stays valid (see {@link JwtService})
 * @param refreshTtl how long a refresh token stays valid after it's issued; every rotation issues a
 *     new one with a fresh lifetime (see {@link RefreshTokenService})
 */
@Validated
@ConfigurationProperties(prefix = "squadpulse.security.token")
public record TokenProperties(
    @NotNull @DurationMin(seconds = 1) Duration accessTtl,
    @NotNull @DurationMin(seconds = 1) Duration refreshTtl) {}
