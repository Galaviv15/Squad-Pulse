package com.squadpulse.auth;

import com.squadpulse.common.ApiErrorResponse;
import com.squadpulse.common.ClubContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless JWT security for the whole API (see docs/spec.md section 10).
 *
 * <ul>
 *   <li>No HTTP session: authentication lives entirely in the access token (checked per request by
 *       {@link JwtAuthenticationFilter}) and the refresh-token families in Redis.
 *   <li>Only {@link #PUBLIC_ENDPOINTS} are reachable without an access token; everything else
 *       requires one, and gets a 401 without it. Finer-grained RBAC is per method, via
 *       {@code @PreAuthorize("hasAuthority('ADMIN')")} etc. on the {@link PermissionLevel}
 *       authorities the filter grants, expanded by {@link #permissionLevelHierarchy()}; a denial
 *       there is turned into a 403 by {@code GlobalExceptionHandler}.
 *   <li>CSRF protection is off: the API is authenticated by a bearer header, which a browser never
 *       attaches on its own. The one cookie — the refresh token — is {@code SameSite=Strict} and
 *       scoped to {@code /auth}, so a cross-site request can't carry it to {@code /auth/refresh} or
 *       {@code /auth/logout} either.
 * </ul>
 *
 * <p>Only in a web application: the {@code bootstrap} profile runs without a web server, where
 * there's no {@link HttpSecurity} to configure.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {

  /**
   * The message of every 401 for a request without a usable caller: no or an invalid access token
   * here, and a token whose user is deactivated or gone in {@link CurrentUserUnavailableException}
   * — one text, so a client can't tell those cases apart.
   */
  static final String AUTHENTICATION_REQUIRED_MESSAGE = "Authentication required";

  /**
   * Public because they authenticate by other means, or are for someone who can't log in: login by
   * email + password, refresh and logout by the refresh-token cookie (logout has to work after the
   * access token has expired), forgot-password by nothing — it's for a user without a working
   * password, and only ever emails a code to the address itself, answering identically either way —
   * and reset-password by that emailed code.
   *
   * <p>POST only: these paths are permitted solely via {@code requestMatchers(HttpMethod.POST,
   * PUBLIC_ENDPOINTS)} below. A public endpoint with any other HTTP method needs that rule changed
   * as well as {@code PublicEndpointsConsistencyTest}, which assumes the same.
   */
  static final String[] PUBLIC_ENDPOINTS = {
    "/auth/login", "/auth/refresh", "/auth/logout", "/auth/forgot-password", "/auth/reset-password"
  };

  /**
   * {@code ADMIN > EDIT_FULL > EDIT_PARTIAL > VIEW_ONLY}, derived from {@link PermissionLevel}'s
   * declaration order (the one source of truth), so an authority check for one level also admits
   * every higher level. {@link JwtAuthenticationFilter} still grants just the caller's own level;
   * this does the expansion.
   *
   * <p>No role prefix: the filter grants bare authority names ({@code ADMIN}, not {@code
   * ROLE_ADMIN}), and checks use {@code hasAuthority}, which the hierarchy applies to as well.
   *
   * <p>Spring Security 7 picks this bean up on its own for both {@code @PreAuthorize} (via {@code
   * PrePostMethodSecurityConfiguration}) and {@code authorizeHttpRequests}; {@code
   * PermissionLevelHierarchyWebMvcTest} proves the method-security side end to end. {@code static}
   * so it's available to the method-security infrastructure early, without instantiating this
   * class.
   */
  @Bean
  static RoleHierarchy permissionLevelHierarchy() {
    RoleHierarchyImpl.Builder builder = RoleHierarchyImpl.withRolePrefix("");
    PermissionLevel[] levels = PermissionLevel.values();
    for (int i = 0; i < levels.length - 1; i++) {
      builder.role(levels[i].name()).implies(levels[i + 1].name());
    }
    return builder.build();
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtService jwtService, ClubContext clubContext, JsonMapper jsonMapper)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            requests ->
                requests
                    // Lets the servlet container's error page render; it exposes nothing itself.
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, PUBLIC_ENDPOINTS)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, e) ->
                            writeError(
                                response,
                                jsonMapper,
                                HttpStatus.UNAUTHORIZED,
                                AUTHENTICATION_REQUIRED_MESSAGE))
                    .accessDeniedHandler(
                        (request, response, e) ->
                            writeError(
                                response, jsonMapper, HttpStatus.FORBIDDEN, "Access denied")))
        .addFilterBefore(
            new JwtAuthenticationFilter(jwtService, clubContext),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * Same JSON shape as {@code GlobalExceptionHandler}, for failures raised before any controller.
   */
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
