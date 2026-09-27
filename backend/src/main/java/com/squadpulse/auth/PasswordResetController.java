package com.squadpulse.auth;

import com.squadpulse.common.PublicEndpoint;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forgot / reset password, and an invited user's activation (see {@link PasswordResetService}).
 * Both are public — {@link PublicEndpoint} here and listed in {@link
 * SecurityConfig#PUBLIC_ENDPOINTS}: the caller has no password to log in with (yet), and the reset
 * authenticates by the emailed code instead. Neither uses a cookie.
 */
@RestController
@RequestMapping("/auth")
class PasswordResetController {

  private final PasswordResetService passwordResetService;

  PasswordResetController(PasswordResetService passwordResetService) {
    this.passwordResetService = passwordResetService;
  }

  /**
   * Always {@code 202} with an empty body — whether the email is registered, deactivated or has
   * used up its requests — so the response never reveals which. Never {@code 429}, for the same
   * reason.
   */
  @PostMapping("/forgot-password")
  @PublicEndpoint
  ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
    passwordResetService.requestReset(request.email());
    return ResponseEntity.accepted().build();
  }

  /** {@code 204} once the password is set; the client then logs in normally. */
  @PostMapping("/reset-password")
  @PublicEndpoint
  ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
    passwordResetService.resetPassword(request.email(), request.code(), request.newPassword());
    return ResponseEntity.noContent().build();
  }
}
