package com.squadpulse.auth;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.AuthService.IssuedTokens;
import com.squadpulse.auth.JwtService.AccessToken;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract of {@link AuthController} — status codes, body, and the refresh cookie — behind
 * the real security chain, with {@link AuthService} mocked.
 */
@WebMvcTest(
    controllers = AuthController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import(AuthWebMvcTestConfig.class)
class AuthControllerTest {

  private static final IssuedTokens TOKENS =
      new IssuedTokens(
          new AccessToken("the-access-token", Instant.now().plusSeconds(900)), "the-refresh-token");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AuthService authService;

  @Test
  void loginReturnsTheAccessTokenInTheBodyAndTheRefreshTokenOnlyInASecureCookie() throws Exception {
    when(authService.login("coach@example.com", "secret")).thenReturn(TOKENS);

    mockMvc
        .perform(login("{\"email\":\"coach@example.com\",\"password\":\"secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("the-access-token"))
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresIn").value(900))
        .andExpect(content().string(not(containsString("the-refresh-token"))))
        .andExpect(
            header()
                .string(
                    HttpHeaders.SET_COOKIE,
                    allOf(
                        containsString("refresh_token=the-refresh-token"),
                        containsString("HttpOnly"),
                        containsString("Secure"),
                        containsString("SameSite=Strict"),
                        containsString("Path=/auth"),
                        containsString("Max-Age=2592000"))));
  }

  @Test
  void loginWithBadCredentialsIsAGeneric401WithNoCookie() throws Exception {
    when(authService.login(anyString(), anyString())).thenThrow(new InvalidCredentialsException());

    mockMvc
        .perform(login("{\"email\":\"coach@example.com\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid email or password"))
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
  }

  @Test
  void loginWithBlankFieldsIs400() throws Exception {
    mockMvc.perform(login("{\"email\":\"\",\"password\":\"\"}")).andExpect(status().isBadRequest());

    verify(authService, never()).login(anyString(), anyString());
  }

  @Test
  void loginWithAMalformedBodyIs400() throws Exception {
    mockMvc.perform(login("{not json")).andExpect(status().isBadRequest());
  }

  @Test
  void refreshWithAValidCookieRotatesIt() throws Exception {
    when(authService.refresh("old-refresh-token")).thenReturn(TOKENS);

    mockMvc
        .perform(post("/auth/refresh").cookie(new Cookie("refresh_token", "old-refresh-token")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("the-access-token"))
        .andExpect(
            header()
                .string(HttpHeaders.SET_COOKIE, containsString("refresh_token=the-refresh-token")));
  }

  @Test
  void refreshWithAnInvalidOrReusedCookieIs401() throws Exception {
    when(authService.refresh("reused-token")).thenThrow(new InvalidRefreshTokenException());

    mockMvc
        .perform(post("/auth/refresh").cookie(new Cookie("refresh_token", "reused-token")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid or expired refresh token"));
  }

  @Test
  void refreshWithoutACookieIs401() throws Exception {
    when(authService.refresh(null)).thenThrow(new InvalidRefreshTokenException());

    mockMvc.perform(post("/auth/refresh")).andExpect(status().isUnauthorized());
  }

  @Test
  void logoutRevokesTheSessionAndClearsTheCookieWithoutNeedingAnAccessToken() throws Exception {
    mockMvc
        .perform(post("/auth/logout").cookie(new Cookie("refresh_token", "the-refresh-token")))
        .andExpect(status().isNoContent())
        .andExpect(
            header()
                .string(
                    HttpHeaders.SET_COOKIE,
                    allOf(containsString("refresh_token=;"), containsString("Max-Age=0"))));

    verify(authService).logout("the-refresh-token");
  }

  private static MockHttpServletRequestBuilder login(String body) {
    return post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body);
  }
}
