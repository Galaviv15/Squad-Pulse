package com.squadpulse.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /clubs/me}: the club settings to change. Today the name is the only setting,
 * so it's required; a setting added later becomes an optional component here, absent meaning
 * "unchanged".
 *
 * <p>Deliberately has no {@code id} or {@code clubId}: the club is always the caller's own, from
 * the access token. Unknown fields a client sends are ignored, so they can't reach the service.
 *
 * @param name trimmed before validation, exactly as {@link Club#setName} stores it; then 1–{@value
 *     Club#NAME_MAX_LENGTH} characters
 */
record UpdateClubRequest(@NotBlank @Size(max = Club.NAME_MAX_LENGTH) String name) {

  /** Trimmed first, so a whitespace-only name fails {@code @NotBlank} and padding doesn't count. */
  UpdateClubRequest {
    name = name == null ? null : name.trim();
  }
}
