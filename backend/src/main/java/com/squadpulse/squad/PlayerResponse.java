package com.squadpulse.squad;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A player as returned by the API. Public only for {@code match}'s create-and-link endpoint
 * (KAN-77), which answers exactly as {@code POST /squad/players} does, through {@link #ofNew}.
 * Never includes {@code clubId}: it's always the caller's own.
 *
 * @param version what the client must send back in {@link UpdatePlayerRequest#version()}
 * @param hasPhoto whether {@code GET /squad/players/{id}/photo} has a photo to return
 */
public record PlayerResponse(
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
    Instant updatedAt,
    boolean hasPhoto) {

  /**
   * A player just created — through {@link PlayerCreation} too (KAN-77): a new player can't have a
   * photo yet, so no storage query is needed.
   */
  public static PlayerResponse ofNew(Player player) {
    return from(player, false);
  }

  static PlayerResponse from(Player player, boolean hasPhoto) {
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
        player.getUpdatedAt(),
        hasPhoto);
  }
}
