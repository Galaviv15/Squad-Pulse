package com.squadpulse.scrapingintegration;

import com.squadpulse.common.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The security chain for {@code /internal/**}: service-to-service endpoints called by the scraper
 * (KAN-66), never by a user. A second {@link SecurityFilterChain}, ordered before {@code
 * auth.SecurityConfig}'s, whose {@code securityMatcher} claims exactly {@code /internal/**} — so
 * Spring Security's {@code FilterChainProxy} hands these requests to this chain alone (the first
 * matching chain wins), and every other request to the user chain.
 *
 * <ul>
 *   <li>Authentication is the scraper's service token only ({@link
 *       ServiceTokenAuthenticationFilter}). There's no {@code JwtAuthenticationFilter} here: a
 *       user's access token, even an {@code ADMIN}'s, authenticates nothing, and no club context is
 *       ever set.
 *   <li>Every request needs {@link #SCRAPER_SERVICE_AUTHORITY}, here and again per handler
 *       ({@code @PreAuthorize}). That authority isn't a {@code PermissionLevel} and isn't in the
 *       role hierarchy: no user level implies it, and it implies none.
 *   <li>Stateless, no CSRF / form login / basic / logout / request cache; failures get the same
 *       JSON 401 / 403 bodies as the user chain.
 * </ul>
 *
 * <p>The user chain also denies {@code /internal/**} outright, so a mis-ordered chain fails closed.
 * Lives here, not in {@code auth}, because it's the scraper's boundary and grants nothing a user
 * can hold.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class InternalSecurityConfig {

  /** Everything under this prefix is internal. */
  static final String INTERNAL_PATHS = "/internal/**";

  /** The one authority the service token grants. Never a {@code PermissionLevel}. */
  static final String SCRAPER_SERVICE_AUTHORITY = "SCRAPER_SERVICE";

  static final String SCRAPER_SERVICE_PRINCIPAL = "scraper-service";

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  SecurityFilterChain internalSecurityFilterChain(
      HttpSecurity http, ScraperProperties scraperProperties, JsonMapper jsonMapper)
      throws Exception {
    http.securityMatcher(INTERNAL_PATHS)
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            requests -> requests.anyRequest().hasAuthority(SCRAPER_SERVICE_AUTHORITY))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, e) ->
                            writeError(
                                response,
                                jsonMapper,
                                HttpStatus.UNAUTHORIZED,
                                "Authentication required"))
                    .accessDeniedHandler(
                        (request, response, e) ->
                            writeError(
                                response, jsonMapper, HttpStatus.FORBIDDEN, "Access denied")))
        .addFilterBefore(
            ServiceTokenAuthenticationFilter.create(scraperProperties),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /** The same JSON shape (and texts) as {@code auth.SecurityConfig}'s failures. */
  private static void writeError(
      HttpServletResponse response, JsonMapper jsonMapper, HttpStatus status, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    jsonMapper.writeValue(
        response.getOutputStream(),
        ApiErrorResponse.of(status.value(), status.getReasonPhrase(), message));
  }
}
