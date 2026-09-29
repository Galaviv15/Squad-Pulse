package com.squadpulse.squad;

import com.squadpulse.common.AdultAge;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Body of {@code POST /squad/players}. The constraints mirror {@link Player}'s, which aren't
 * enforced on save, so this is where they're actually checked.
 *
 * <p>Deliberately has no {@code clubId} and no {@code active}: a new player always joins the
 * caller's own club, as an active player. Any such field a client sends is ignored.
 *
 * @param fullName trimmed before validation, exactly as {@link Player#setFullName} stores it
 * @param medicalStatus optional; {@link MedicalStatus#FIT} if absent
 * @param dateOfBirth ISO format ({@code yyyy-MM-dd})
 */
@DistinctPositions
record CreatePlayerRequest(
    @NotBlank @Size(max = 100) String fullName,
    @NotNull Position primaryPosition,
    Position secondaryPosition,
    @Min(1) @Max(99) Integer jerseyNumber,
    @NotNull @AdultAge LocalDate dateOfBirth,
    @Min(140) @Max(220) Integer heightCm,
    @Min(40) @Max(150) Integer weightKg,
    PreferredFoot preferredFoot,
    MedicalStatus medicalStatus)
    implements HasPositions {

  /** Trimmed first, so a whitespace-only name fails {@code @NotBlank} and padding doesn't count. */
  CreatePlayerRequest {
    fullName = fullName == null ? null : fullName.trim();
  }
}
