package com.squadpulse.auth;

import com.squadpulse.auth.AuthService.IssuedTokens;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login, refresh and logout (see docs/spec.md section 10). All three are public in {@link
 * SecurityConfig}: login authenticates by email + password, refresh and logout by the refresh-token
 * cookie.
 *
 * <p>The access token goes in the JSON body; the refresh token only ever in the {@value
 * #REFRESH_COOKIE} cookie — {@code HttpOnly} (unreadable by page scripts, so XSS can't steal it),
 * {@code Secure}, {@code SameSite=Strict} and scoped to {@code /auth}, so the browser sends it to
 * nothing but these endpoints.
 */
@RestController
@RequestMapping("/auth")
class AuthController {

  static final String REFRESH_COOKIE = "refresh_token";
  static final String REFRESH_COOKIE_PATH = "/auth";
  static final String TOKEN_TYPE = "Bearer";

  private final AuthService authService;
  private final TokenProperties tokenProperties;

  AuthController(AuthService authService, TokenProperties tokenProperties) {
    this.authService = authService;
    this.tokenProperties = tokenProperties;
  }

  @PostMapping("/login")
  ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request) {
    return withTokens(authService.login(request.email(), request.password()));
  }

  @PostMapping("/refresh")
  ResponseEntity<AccessTokenResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    return withTokens(authService.refresh(refreshToken));
  }

  /** Always 204 and clears the cookie — even if the token was already invalid. */
  @PostMapping("/logout")
  ResponseEntity<Void> logout(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    authService.logout(refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
        .build();
  }

  private ResponseEntity<AccessTokenResponse> withTokens(IssuedTokens tokens) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            refreshCookie(tokens.refreshToken(), tokenProperties.refreshTtl()).toString())
        .body(
            new AccessTokenResponse(
                tokens.accessToken().value(), TOKEN_TYPE, tokenProperties.accessTtl().toSeconds()));
  }

  private static ResponseCookie refreshCookie(String value, Duration maxAge) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(REFRESH_COOKIE_PATH)
        .maxAge(maxAge)
        .build();
  }
}
