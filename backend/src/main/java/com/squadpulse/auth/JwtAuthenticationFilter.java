package com.squadpulse.auth;

import com.squadpulse.common.ClubContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from its {@code Authorization: Bearer <access token>} header, and scopes
 * it to the caller's club: this is what populates {@link ClubContext} for every authenticated
 * request (see docs/spec.md section 03).
 *
 * <p>For a valid token it sets a Spring Security {@link
 * org.springframework.security.core.Authentication} — principal {@link AuthenticatedUser}, one
 * authority named after the caller's {@link PermissionLevel} (so {@code hasAuthority('ADMIN')}
 * works) — and the token's {@code clubId} into {@link ClubContext}. The {@link ClubContext} is
 * cleared in a {@code finally} once the rest of the chain returns, even if it threw, so a clubId
 * never leaks onto the next request served by the same pooled thread. It's also cleared up front,
 * so a request without a valid token never runs with a leftover clubId.
 *
 * <p>A missing or invalid token doesn't fail the request here: it just continues unauthenticated,
 * and {@link SecurityConfig} decides — public endpoints proceed, everything else gets a 401.
 *
 * <p>Deliberately not a {@code @Component}: Spring Boot would otherwise also register it as a plain
 * servlet filter, running it a second time outside the security chain.
 */
class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;
  private final ClubContext clubContext;
  private final SecurityContextHolderStrategy securityContextHolderStrategy =
      SecurityContextHolder.getContextHolderStrategy();

  JwtAuthenticationFilter(JwtService jwtService, ClubContext clubContext) {
    this.jwtService = jwtService;
    this.clubContext = clubContext;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    clubContext.clear();
    try {
      bearerToken(request).flatMap(jwtService::parse).ifPresent(this::authenticate);
      chain.doFilter(request, response);
    } finally {
      clubContext.clear();
    }
  }

  private void authenticate(AuthenticatedUser user) {
    UsernamePasswordAuthenticationToken authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            user, null, List.of(new SimpleGrantedAuthority(user.permissionLevel().name())));
    SecurityContext context = securityContextHolderStrategy.createEmptyContext();
    context.setAuthentication(authentication);
    securityContextHolderStrategy.setContext(context);
    clubContext.setClubId(user.clubId());
  }

  private static Optional<String> bearerToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null
        || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return Optional.empty();
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }
}
