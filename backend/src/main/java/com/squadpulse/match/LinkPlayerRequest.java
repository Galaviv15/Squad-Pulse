package com.squadpulse.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /matches/player-stats/{id}/link} (KAN-72).
 *
 * @param playerId the id of one of the caller's club's own players
 */
record LinkPlayerRequest(@NotBlank @Size(max = 100) String playerId) {}
