package com.squadpulse.squad;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A player as returned by the API. Never includes {@code clubId}: it's always the caller's own.
 *
 * @param version what the client must send back in {@link UpdatePlayerRequest#version()}
 */
record PlayerResponse(
    String id,
    String fullName,
    Position primaryPosition,
    Position secondaryPosition,
    Integer jerseyNumber,
    LocalDate dateOfBirth,
    Integer heightCm,
    Integer weightKg,
    PreferredFoot preferredFoot,
    MedicalStatus medicalStatus,
    boolean active,
    Long version,
    Instant createdAt,
    Instant updatedAt) {

  static PlayerResponse from(Player player) {
    return new PlayerResponse(
        player.getId(),
        player.getFullName(),
        player.getPrimaryPosition(),
        player.getSecondaryPosition(),
        player.getJerseyNumber(),
        player.getDateOfBirth(),
        player.getHeightCm(),
        player.getWeightKg(),
        player.getPreferredFoot(),
        player.getMedicalStatus(),
        player.isActive(),
        player.getVersion(),
        player.getCreatedAt(),
        player.getUpdatedAt());
  }
}
