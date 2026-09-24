package com.squadpulse.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * The system owner's credential for creating a new club, bound from {@code
 * squadpulse.bootstrap.owner-secret} — which only application-bootstrap.yml maps, from the {@code
 * OWNER_BOOTSTRAP_SECRET} environment variable.
 *
 * <p>Kept out of {@link SecurityProperties} on purpose: this only exists under the {@value
 * ClubBootstrapRunner#PROFILE} profile, so a normal server process never binds or holds the secret.
 * Validated the same way as the other secrets: if it's missing or too short, the bootstrap task
 * refuses to start.
 */
@Validated
@Profile(ClubBootstrapRunner.PROFILE)
@ConfigurationProperties(prefix = "squadpulse.bootstrap")
public record OwnerBootstrapProperties(
    @NotBlank @Size(min = 32, message = "must be at least 32 characters") String ownerSecret) {}
