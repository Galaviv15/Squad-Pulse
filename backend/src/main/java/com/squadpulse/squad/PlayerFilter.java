package com.squadpulse.squad;

/**
 * The filters of {@code GET /squad/players}, all combined with AND. {@code null} means "don't
 * filter on this". Ages are in completed years and inclusive.
 *
 * @param position matches the <b>primary</b> position only
 */
record PlayerFilter(
    PlayerStatus status,
    Position position,
    Integer minAge,
    Integer maxAge,
    MedicalStatus medicalStatus,
    PreferredFoot preferredFoot) {}
