package com.squadpulse.scrapingintegration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the scraper on {@code /internal/**} by its service token (KAN-66): {@code
 * Authorization: Bearer <token>} compared with {@link ScraperProperties#serviceToken()}. On a match
 * the request gets exactly one authority, {@link InternalSecurityConfig#SCRAPER_SERVICE_AUTHORITY},
 * and <b>no club context</b>: the ingest finds its clubs from the scraped ids. Anything else — no
 * header, a wrong token, a user's access token, or no token configured at all — continues
 * unauthenticated, and {@link InternalSecurityConfig} answers 401.
 *
 * <p>The comparison is constant-time on SHA-256 digests of both values ({@link
 * MessageDigest#isEqual}), so neither the content nor the length of the configured token leaks
 * through timing. The token is never logged.
 *
 * <p>Deliberately not a {@code @Component} or {@code @Bean}: Spring Boot would also register it as
 * a plain servlet filter on every path. It's created inside {@link InternalSecurityConfig}'s chain
 * only, as {@code auth.SecurityConfig} does with {@code JwtAuthenticationFilter}.
 */
class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(ServiceTokenAuthenticationFilter.class);

  private static final String BEARER_PREFIX = "Bearer ";

  /** The configured token's digest, or {@code null} when none is configured: nothing matches. */
  private final byte[] expectedDigest;

  private final SecurityContextHolderStrategy securityContextHolderStrategy =
      SecurityContextHolder.getContextHolderStrategy();

  private ServiceTokenAuthenticationFilter(byte[] expectedDigest) {
    this.expectedDigest = expectedDigest;
  }

  /** Logs one WARN when no token is configured: every {@code /internal/**} request is then 401. */
  static ServiceTokenAuthenticationFilter create(ScraperProperties properties) {
    if (!properties.enabled()) {
      log.warn(
          "SCRAPER_SERVICE_TOKEN is not set: internal endpoints disabled, every /internal/**"
              + " request is answered 401");
      return new ServiceTokenAuthenticationFilter(null);
    }
    return new ServiceTokenAuthenticationFilter(sha256(properties.serviceToken()));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (expectedDigest != null && matches(request.getHeader(HttpHeaders.AUTHORIZATION))) {
      SecurityContext context = securityContextHolderStrategy.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              InternalSecurityConfig.SCRAPER_SERVICE_PRINCIPAL,
              null,
              List.of(
                  new SimpleGrantedAuthority(InternalSecurityConfig.SCRAPER_SERVICE_AUTHORITY))));
      securityContextHolderStrategy.setContext(context);
    }
    chain.doFilter(request, response);
  }

  private boolean matches(String header) {
    if (header == null
        || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return false;
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return !token.isEmpty() && MessageDigest.isEqual(sha256(token), expectedDigest);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every JVM", e);
    }
  }
}
