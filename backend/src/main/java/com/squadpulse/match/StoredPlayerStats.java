package com.squadpulse.match;

/**
 * One player's row in a {@link PlayerSeasonStatsSnapshot} (KAN-72).
 *
 * @param opaqueId our own id (a random UUID), the only id a response ever carries (as {@code id}).
 *     Not named {@code id}: Spring Data would store that as {@code _id}. Kept across replaces for
 *     as long as {@link #sourcePlayerId} stays in the snapshot; a new {@code sourcePlayerId} gets a
 *     new one.
 * @param sourcePlayerId football.org.il's {@code player_id}: the row's key within the snapshot and
 *     the key of a {@link PlayerStatsLink}. Never exposed (docs/spec.md section 09).
 * @param name as the site shows it (surname first)
 */
record StoredPlayerStats(
    String opaqueId,
    int sourcePlayerId,
    String name,
    int games,
    int goals,
    int yellowCardsLeagueAndCup,
    int yellowCardsToto,
    int redCards,
    int starts,
    int cameOnAsSub,
    int subbedOff,
    int minutes) {}
