package com.squadpulse.squad;

import com.squadpulse.common.AdultAge;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Body of {@code PUT /squad/players/{id}}: a <b>full replacement</b> of every editable field. An
 * optional field that's absent or {@code null} clears the stored value, so {@code medicalStatus},
 * which a player must always have, is required here (unlike on create).
 *
 * <p>Deliberately has no {@code clubId} and no {@code active}: neither can be changed by an edit
 * (releasing and re-activating are separate operations). Any such field a client sends is ignored.
 *
 * @param fullName trimmed before validation, exactly as {@link Player#setFullName} stores it
 * @param version the {@link Player#getVersion() version} the client loaded; the edit is refused
 *     with a 409 if the player has been saved since
 */
@DistinctPositions
record UpdatePlayerRequest(
    @NotBlank @Size(max = 100) String fullName,
    @NotNull Position primaryPosition,
    Position secondaryPosition,
    @Min(1) @Max(99) Integer jerseyNumber,
    @NotNull @AdultAge LocalDate dateOfBirth,
    @Min(140) @Max(220) Integer heightCm,
    @Min(40) @Max(150) Integer weightKg,
    PreferredFoot preferredFoot,
    @NotNull MedicalStatus medicalStatus,
    @NotNull Long version)
    implements HasPositions {

  /** Trimmed first, so a whitespace-only name fails {@code @NotBlank} and padding doesn't count. */
  UpdatePlayerRequest {
    fullName = fullName == null ? null : fullName.trim();
  }
}
