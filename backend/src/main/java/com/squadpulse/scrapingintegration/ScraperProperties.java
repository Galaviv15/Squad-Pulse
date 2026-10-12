package com.squadpulse.scrapingintegration;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The scraper's service token, bound from {@code squadpulse.scraper.service-token} (the {@code
 * SCRAPER_SERVICE_TOKEN} environment variable, see application.yml): the shared secret the scraper
 * sends as {@code Authorization: Bearer <token>} on {@code /internal/**} (KAN-66).
 *
 * <p>Optional, unlike {@code JWT_SECRET}: empty or unset, the application starts and every {@code
 * /internal/**} request gets a 401 (fail closed — dev, CI and E2E need no scraper). Set but weak —
 * shorter than 32 characters, or containing whitespace — and the application refuses to start, like
 * {@code auth.SecurityProperties}.
 */
@Validated
@ConfigurationProperties(prefix = "squadpulse.scraper")
public record ScraperProperties(
    @Pattern(
            regexp = "|\\S{32,}",
            message =
                "must be empty (internal endpoints disabled) or at least 32 characters without"
                    + " whitespace")
        String serviceToken) {

  /** Whether a token is configured; without one, {@code /internal/**} always answers 401. */
  boolean enabled() {
    return serviceToken != null && !serviceToken.isEmpty();
  }

  /** Never prints the token. */
  @Override
  public String toString() {
    return "ScraperProperties[serviceToken=" + (enabled() ? "<set>" : "<unset>") + "]";
  }
}
