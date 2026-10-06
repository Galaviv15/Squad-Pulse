package com.squadpulse.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract of {@link PasswordResetController} — status codes, bodies, and validation
 * running before the service — behind the real security chain, with {@link PasswordResetService}
 * mocked. Neither endpoint needs an access token.
 */
@WebMvcTest(
    controllers = PasswordResetController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import(AuthWebMvcTestConfig.class)
class PasswordResetControllerTest {

  private static final String VALID_PASSWORD = "correct-horse-battery";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private PasswordResetService passwordResetService;

  @Test
  void forgotPasswordIs202WithAnEmptyBodyAndNoCookie() throws Exception {
    mockMvc
        .perform(forgotPassword("{\"email\":\"coach@example.com\"}"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(""))
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

    verify(passwordResetService).requestReset("coach@example.com");
  }

  @Test
  void forgotPasswordWithABlankOrOverlongEmailIs400() throws Exception {
    mockMvc.perform(forgotPassword("{\"email\":\"\"}")).andExpect(status().isBadRequest());
    mockMvc
        .perform(
            forgotPassword(
                "{\"email\":\"%s\"}".formatted("a".repeat(243) + "@example.com"))) // 255 chars
        .andExpect(status().isBadRequest());

    verify(passwordResetService, never()).requestReset(anyString());
  }

  @Test
  void resetPasswordIs204() throws Exception {
    mockMvc
        .perform(resetPassword("coach@example.com", "042137", VALID_PASSWORD))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""))
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

    verify(passwordResetService).resetPassword("coach@example.com", "042137", VALID_PASSWORD);
  }

  @Test
  void resetPasswordWithAnInvalidCodeIsAGeneric401() throws Exception {
    doThrow(new InvalidResetCodeException())
        .when(passwordResetService)
        .resetPassword("coach@example.com", "042137", VALID_PASSWORD);

    mockMvc
        .perform(resetPassword("coach@example.com", "042137", VALID_PASSWORD))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid or expired code"));
  }

  /** Every retry lost a race (KAN-24): a generic 409 — never a 204, never a 500. */
  @Test
  void resetPasswordWhoseRetriesAllConflictedIs409() throws Exception {
    doThrow(new OptimisticLockingFailureException("Cannot save entity user-1 with version 3"))
        .when(passwordResetService)
        .resetPassword("coach@example.com", "042137", VALID_PASSWORD);

    mockMvc
        .perform(resetPassword("coach@example.com", "042137", VALID_PASSWORD))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(
            jsonPath("$.message").value("The resource was modified concurrently, please retry"));
  }

  /** Rejected before the service runs, so the code isn't consumed or charged an attempt. */
  @ParameterizedTest
  @ValueSource(strings = {"", "12345", "1234567", "12345a", " 12345", "١٢٣٤٥٦", "12 345"})
  void resetPasswordWithAMalformedCodeIs400(String code) throws Exception {
    mockMvc
        .perform(resetPassword("coach@example.com", code, VALID_PASSWORD))
        .andExpect(status().isBadRequest());

    verify(passwordResetService, never()).resetPassword(anyString(), anyString(), anyString());
  }

  @Test
  void resetPasswordWithATooShortOrTooLongPasswordIs400() throws Exception {
    mockMvc
        .perform(resetPassword("coach@example.com", "042137", "1234567"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(resetPassword("coach@example.com", "042137", "a".repeat(129)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(resetPassword("coach@example.com", "042137", "        "))
        .andExpect(status().isBadRequest());

    verify(passwordResetService, never()).resetPassword(anyString(), anyString(), anyString());
  }

  /** Length is the whole policy: no composition rules. */
  @Test
  void resetPasswordAcceptsAnyPasswordOf8To128Characters() throws Exception {
    for (String password : new String[] {"aaaaaaaa", "a".repeat(128), "סיסמה ארוכה"}) {
      mockMvc
          .perform(resetPassword("coach@example.com", "042137", password))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void resetPasswordWithoutAnEmailIs400() throws Exception {
    mockMvc
        .perform(
            post("/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"042137\",\"newPassword\":\"%s\"}".formatted(VALID_PASSWORD)))
        .andExpect(status().isBadRequest());
  }

  private static MockHttpServletRequestBuilder forgotPassword(String body) {
    return post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static MockHttpServletRequestBuilder resetPassword(
      String email, String code, String newPassword) {
    return post("/auth/reset-password")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"%s\"}"
                .formatted(email, code, newPassword));
  }
}
