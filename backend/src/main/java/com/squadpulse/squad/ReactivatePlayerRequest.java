package com.squadpulse.squad;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /squad/players/{id}/reactivate}. Any other field a client sends is ignored.
 *
 * @param version the {@link Player#getVersion() version} the client loaded; the re-activation is
 *     refused with a 409 if the player has been saved since
 * @param jerseyNumber the number the player comes back with — a <b>full replacement</b>, as on
 *     {@code PUT}: absent or {@code null} means no number, not "keep the old one". This is the only
 *     way to change a released player's number, so one whose old number has been taken can still
 *     come back.
 */
record ReactivatePlayerRequest(@NotNull Long version, @Min(1) @Max(99) Integer jerseyNumber) {}
