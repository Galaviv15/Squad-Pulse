package com.squadpulse.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Secrets the auth module needs, bound from {@code squadpulse.security.*} (which reads the {@code
 * JWT_SECRET} and {@code PASSWORD_PEPPER} environment variables — see application.yml).
 *
 * <p>Validated at startup on purpose: if either secret is missing or too short the application
 * refuses to start, rather than running with an empty JWT secret or pepper (see docs/spec.md
 * section 10).
 */
@Validated
@ConfigurationProperties(prefix = "squadpulse.security")
public record SecurityProperties(
    @NotBlank @Size(min = 32, message = "must be at least 32 characters") String jwtSecret,
    @NotBlank @Size(min = 32, message = "must be at least 32 characters") String passwordPepper) {}
