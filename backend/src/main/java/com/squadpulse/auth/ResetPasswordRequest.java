package com.squadpulse.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /auth/reset-password} — also the activation step for an invited user.
 *
 * <p>Validated before the code is checked, so a malformed request never uses up a guess or the
 * code. {@code code} must be exactly six ASCII digits ({@code \d} doesn't match other scripts'
 * digits without {@code UNICODE_CHARACTER_CLASS}, and {@code @Pattern} matches the whole value).
 *
 * @param newPassword the password policy is length only, 8–128 characters (counted in UTF-16
 *     units), with no composition rules — following NIST SP 800-63B and OWASP. The upper bound just
 *     keeps hashing input sane; the pepper's HMAC gives Argon2 a fixed-length input anyway (see
 *     {@link PepperedPasswordEncoder}).
 */
record ResetPasswordRequest(
    @NotBlank @Size(max = 254) String email,
    @NotBlank @Pattern(regexp = "\\d{6}") String code,
    @NotBlank @Size(min = 8, max = 128) String newPassword) {}
