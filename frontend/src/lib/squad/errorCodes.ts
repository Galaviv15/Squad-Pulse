/**
 * The squad API's 409 codes (ApiError.code), as the backend's ConflictException subclasses declare
 * them. Codes are stable, so screens compare against these, never against the English message.
 */
export const PLAYER_ERROR_CODES = {
  /** squad.JerseyNumberTakenException: another active player has the number. */
  JERSEY_NUMBER_TAKEN: "JERSEY_NUMBER_TAKEN",
  /** squad.StalePlayerVersionException: the player was saved since the client loaded it. */
  STALE_VERSION: "STALE_VERSION",
  /** squad.ReleasedPlayerException: a released player can't be edited. */
  PLAYER_RELEASED: "PLAYER_RELEASED",
  /** squad.PlayerAlreadyReleasedException: the player to release has been released already. */
  PLAYER_ALREADY_RELEASED: "PLAYER_ALREADY_RELEASED",
  /** squad.PlayerAlreadyActiveException: the player to re-activate is active already. */
  PLAYER_ALREADY_ACTIVE: "PLAYER_ALREADY_ACTIVE",
} as const;
