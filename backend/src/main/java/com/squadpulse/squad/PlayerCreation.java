package com.squadpulse.squad;

/**
 * Creating a player from outside {@code squad} (KAN-77): {@code match} creates a player from a
 * scraped statistics row and links it in the same transaction. Implemented by {@link
 * PlayerService}, so it's the same code path as {@code POST /squad/players} — the same field
 * mapping, the same club-scoped {@code insert} (the clubId comes from {@code ClubContext}), the
 * same jersey-number translation — never a second copy of it.
 *
 * <p>The caller validates {@code request} first ({@code @Valid} on the body), exactly as {@code
 * POST /squad/players} does: {@link Player}'s own constraints aren't run on save.
 */
public interface PlayerCreation {

  /**
   * Adds an active player to the current club.
   *
   * @return the stored player
   * @throws JerseyNumberTakenException (a 409 {@code JERSEY_NUMBER_TAKEN}) if another active player
   *     in the club has that number
   */
  Player create(CreatePlayerRequest request);
}
