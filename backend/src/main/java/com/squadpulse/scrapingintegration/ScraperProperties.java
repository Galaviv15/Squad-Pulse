package com.squadpulse.scrapingintegration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The scraper's settings, bound from {@code squadpulse.scraper.*} (see application.yml).
 *
 * <ul>
 *   <li>{@code serviceToken} ({@code SCRAPER_SERVICE_TOKEN}): the shared secret, both ways — the
 *       scraper sends it as {@code Authorization: Bearer <token>} on {@code /internal/**} (KAN-66),
 *       and the backend sends it on the scraper's {@code POST /scrape} (KAN-68). Optional, unlike
 *       {@code JWT_SECRET}: empty or unset, the application starts, every {@code /internal/**}
 *       request gets a 401 and no refresh can start (fail closed — dev, CI and E2E need no
 *       scraper). Set but weak — shorter than 32 characters, or containing whitespace — and the
 *       application refuses to start, like {@code auth.SecurityProperties}.
 *   <li>{@code url} ({@code SCRAPER_URL}, KAN-68): the scraper's base URL, e.g. {@code
 *       http://127.0.0.1:8090}. Optional, no default: unset (or without a token), {@code POST
 *       /matches/refresh} answers {@code SCRAPER_UNAVAILABLE} and touches no lock. Set but not an
 *       {@code http} / {@code https} URL with a host and without user-info, a path (a single
 *       trailing {@code /} is fine), a query or a fragment, and the application refuses to start:
 *       the scraper serves only {@code /scrape}, at its root.
 *   <li>{@code refreshCooldown} (default {@value #DEFAULT_COOLDOWN_MINUTES} minutes): how long a
 *       team's and a league's locks hold once the scraper has accepted a trigger — the primary
 *       limit on how often football.org.il is asked ({@link ScrapeTriggerService}).
 *   <li>{@code runTimeout} (default {@value #DEFAULT_RUN_TIMEOUT_MINUTES} minutes): a run with no
 *       result by then is {@code FAILED NO_RESULT} ({@link ScrapeRunStatusService}). Must not
 *       exceed the cooldown, or a second scrape could start while the first may still be running.
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "squadpulse.scraper")
public record ScraperProperties(
    @Pattern(
            regexp = "|\\S{32,}",
            message =
                "must be empty (internal endpoints disabled) or at least 32 characters without"
                    + " whitespace")
        String serviceToken,
    String url,
    @DurationMin(millis = 1) Duration refreshCooldown,
    @DurationMin(millis = 1) Duration runTimeout) {

  static final long DEFAULT_COOLDOWN_MINUTES = 10;
  static final long DEFAULT_RUN_TIMEOUT_MINUTES = 10;

  /** An unset or empty URL is none; unset durations get their defaults. */
  public ScraperProperties {
    if (url != null && url.isEmpty()) {
      url = null;
    }
    if (refreshCooldown == null) {
      refreshCooldown = Duration.ofMinutes(DEFAULT_COOLDOWN_MINUTES);
    }
    if (runTimeout == null) {
      runTimeout = Duration.ofMinutes(DEFAULT_RUN_TIMEOUT_MINUTES);
    }
  }

  /** Whether a token is configured; without one, {@code /internal/**} always answers 401. */
  boolean enabled() {
    return serviceToken != null && !serviceToken.isEmpty();
  }

  /** Whether the backend can trigger the scraper: a URL to call and a token to call it with. */
  boolean triggerEnabled() {
    return url != null && enabled();
  }

  @AssertTrue(
      message =
          "url must be empty, or an http / https URL with a host and without user-info, a path, a"
              + " query or a fragment")
  boolean isUrlValid() {
    if (url == null) {
      return true;
    }
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      return (scheme.equals("http") || scheme.equals("https"))
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && (uri.getRawPath() == null
              || uri.getRawPath().isEmpty()
              || uri.getRawPath().equals("/"))
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null;
    } catch (Exception e) {
      return false;
    }
  }

  @AssertTrue(message = "runTimeout must not be longer than refreshCooldown")
  boolean isRunTimeoutWithinCooldown() {
    return refreshCooldown == null
        || runTimeout == null
        || runTimeout.compareTo(refreshCooldown) <= 0;
  }

  /** Never prints the token, nor the URL (an invalid one could hold credentials). */
  @Override
  public String toString() {
    return "ScraperProperties[serviceToken="
        + (enabled() ? "<set>" : "<unset>")
        + ", url="
        + (url == null ? "<unset>" : "<set>")
        + ", refreshCooldown="
        + refreshCooldown
        + ", runTimeout="
        + runTimeout
        + "]";
  }
}
